"""CPython simulation of Device/code.py with fake CircuitPython modules.

Run:  python3 tools/firmware-sim/test_sim.py   and   python3 tools/firmware-sim/test_repeater.py
"""
import json
import os
import random
import sys
import types

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


class Stop(KeyboardInterrupt):
    pass


class Clock:
    def __init__(self):
        self.ns = 1_000_000_000_000
        self.limit_ns = None

    def advance(self, seconds):
        self.ns += int(seconds * 1e9)
        if self.limit_ns is not None and self.ns > self.limit_ns:
            raise Stop()


def make_env(scenario):
    clock = Clock()
    rnd = random.Random(scenario.get("seed", 1))
    mods = {}

    # time
    t = types.ModuleType("time")
    t.monotonic_ns = lambda: clock.ns
    t.monotonic = lambda: clock.ns / 1e9
    t.sleep = lambda s: clock.advance(s)
    mods["time"] = t

    # gc
    g = types.ModuleType("gc")
    g.collect = lambda: None
    g.mem_free = lambda: 123456
    mods["gc"] = g

    # board / busio / digitalio
    b = types.ModuleType("board")
    for name in ("GP16", "GP17", "GP18", "GP19", "GP20", "LED"):
        setattr(b, name, name)
    mods["board"] = b
    busio = types.ModuleType("busio")

    class SPI:
        def __init__(self, *a, **k):
            pass

    busio.SPI = SPI
    mods["busio"] = busio
    dio = types.ModuleType("digitalio")

    class DigitalInOut:
        def __init__(self, pin):
            self.pin = pin
            self.value = False
            self.direction = None

    class Direction:
        OUTPUT = "out"

    dio.DigitalInOut = DigitalInOut
    dio.Direction = Direction
    mods["digitalio"] = dio

    # watchdog / microcontroller
    wd = types.ModuleType("watchdog")

    class WatchDogMode:
        RESET = "reset"

    wd.WatchDogMode = WatchDogMode
    mods["watchdog"] = wd

    class WDT:
        def __init__(self):
            self.timeout = None
            self.mode = None
            self.last = clock.ns
            self.max_gap = 0

        def feed(self):
            gap = (clock.ns - self.last) / 1e9
            self.max_gap = max(self.max_gap, gap)
            self.last = clock.ns

    mc = types.ModuleType("microcontroller")
    mc.nvm = bytearray(4096)
    mc.watchdog = WDT()
    mods["microcontroller"] = mc

    sup = types.ModuleType("supervisor")
    sup.runtime = types.SimpleNamespace(autoreload=True)
    sup.get_previous_traceback = lambda: scenario.get("prev_tb")

    def reload():
        raise SystemExit("reload")

    sup.reload = reload
    mods["supervisor"] = sup

    # usb_cdc
    class FakeSerial:
        def __init__(self):
            self.connected = scenario.get("usb_connected", True)
            self.timeout = None
            self.write_timeout = None
            self.inbox = b""
            self.out = []

        @property
        def in_waiting(self):
            return len(self.inbox)

        def read(self, n):
            d, self.inbox = self.inbox[:n], self.inbox[n:]
            return d

        def write(self, data):
            self.out.append((clock.ns, bytes(data)))
            return len(data)

    usb = types.ModuleType("usb_cdc")
    usb.console = FakeSerial()
    usb.data = FakeSerial() if scenario.get("data_port", True) else None
    mods["usb_cdc"] = usb

    # wifi / socketpool
    net = {"up": False, "sent": [], "rx": [], "connects": 0}
    if scenario.get("wifi_module", True):
        wifi = types.ModuleType("wifi")

        class Radio:
            enabled = True

            @property
            def ipv4_address(self):
                return "192.168.43.20" if net["up"] else None

            @property
            def ipv4_gateway(self):
                return "192.168.43.1" if net["up"] else None

            def connect(self, ssid, password, timeout=None):
                net["connects"] += 1
                if ssid == scenario.get("hotspot") and password == scenario.get("hotspot_pass"):
                    clock.advance(2.0)
                    net["up"] = True
                    return
                clock.advance(min(timeout or 8, 3.0))
                raise ConnectionError("No network with that ssid")

            def stop_station(self):
                net["up"] = False

        wifi.radio = Radio()
        mods["wifi"] = wifi
        sp = types.ModuleType("socketpool")

        class Sock:
            def __init__(self):
                self.closed = False

            def setblocking(self, flag):
                pass

            def bind(self, addr):
                self.bound = addr

            def sendto(self, data, dest):
                if not net["up"]:
                    raise OSError(113)
                net["sent"].append((clock.ns, dest, bytes(data)))

            def recvfrom_into(self, buf):
                if not net["rx"]:
                    raise OSError(11)
                data, addr = net["rx"].pop(0)
                buf[: len(data)] = data
                return len(data), addr

            def close(self):
                self.closed = True

        class SocketPool:
            AF_INET = 2
            SOCK_DGRAM = 2

            def __init__(self, radio):
                pass

            def socket(self, family, kind):
                return Sock()

        sp.SocketPool = SocketPool
        mods["socketpool"] = sp

    # adafruit_rfm9x with a simulated repeater
    rf = types.ModuleType("adafruit_rfm9x")
    radio_state = {"inits": 0, "tx": []}

    class RFM9x:
        def __init__(self, spi, cs, reset, freq, baudrate=None):
            radio_state["inits"] += 1
            if radio_state["inits"] <= scenario.get("radio_init_failures", 0):
                raise RuntimeError("Failed to find rfm9x with expected version -- check wiring")
            self.crc_error_count = 0
            self.last_rssi = 0.0
            self.last_snr = 0.0
            self._pending = []  # (time_ns, packet, rssi, snr)

        def send(self, data):
            text = bytes(data).decode() if not isinstance(data, str) else data
            radio_state["tx"].append((clock.ns, text))
            clock.advance(1.19)
            if rnd.random() < scenario.get("noise", 0.0):
                self._pending.append((clock.ns + int(0.3e9), None, -120, -15.0))
            if rnd.random() < scenario.get("other_traffic", 0.0):
                self._pending.append((clock.ns + int(0.2e9), b"\xff\xfe binary", -90, 5.0))
            if rnd.random() < scenario.get("success", 0.8):
                delay = 0.15 + rnd.randint(1, 5) * 0.1
                rssi = rnd.randint(-125, -60)
                snr = rnd.randint(-80, 40) / 4
                if scenario.get("v2"):
                    reply = "%s|%d|%s" % (text, rssi - 3, snr - 1)
                    airtime = 1.71
                else:
                    reply = text
                    airtime = 1.19
                self._pending.append((clock.ns + int((delay + airtime) * 1e9), reply.encode(), rssi, snr))
            self._pending.sort(key=lambda p: p[0])
            return True

        def receive(self, timeout=0.5, keep_listening=True):
            end = clock.ns + int(timeout * 1e9)
            if self._pending and self._pending[0][0] <= end:
                when, pkt, rssi, snr = self._pending.pop(0)
                if when > clock.ns:
                    clock.advance((when - clock.ns) / 1e9)
                if pkt is None:
                    self.crc_error_count += 1
                    return None
                self.last_rssi, self.last_snr = rssi, snr
                return bytearray(pkt)
            clock.advance(timeout)
            self._pending = [p for p in self._pending if p[0] > clock.ns]
            return None

    rf.RFM9x = RFM9x
    mods["adafruit_rfm9x"] = rf
    return clock, mods, usb, net, radio_state, mc


def run(scenario, seconds, actions=()):
    clock, mods, usb, net, radio_state, mc = make_env(scenario)
    saved = {k: sys.modules.get(k) for k in mods}
    sys.modules.update(mods)
    for k in ("socketpool", "wifi"):
        if k not in mods:
            sys.modules[k] = None  # make the import fail
    try:
        src = open(REPO + "/Device/code.py").read()
        g = {"_LSM_TEST": True, "__name__": "lsm_sim"}
        exec(compile(src, "code.py", "exec"), g)
        mapper = g["Mapper"]()
        # schedule actions: (at_seconds, fn)
        start = clock.ns
        pending = sorted(actions, key=lambda a: a[0])
        orig_sleep = mods["time"].sleep

        def sleep_hook(s):
            orig_sleep(s)
            while pending and clock.ns - start >= pending[0][0] * 1e9:
                pending.pop(0)[1](usb, net, mapper)

        mods["time"].sleep = sleep_hook
        clock.limit_ns = start + int(seconds * 1e9)
        try:
            mapper.run()
        except Stop:
            pass
        return {
            "mapper": mapper,
            "usb": usb,
            "net": net,
            "radio": radio_state,
            "wdt_gap": mc.watchdog.max_gap,
            "nvm": mc.nvm,
            "clock": clock,
            "g": g,
        }
    finally:
        for k, v in saved.items():
            if v is None:
                sys.modules.pop(k, None)
            else:
                sys.modules[k] = v


def lines_from(port):
    out = []
    for _, data in port.out:
        for line in data.decode().split("\n"):
            if line.strip():
                out.append(json.loads(line))
    return out
