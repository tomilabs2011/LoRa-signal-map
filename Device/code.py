# LoRa Signal Map - mobile "Device" firmware
# Raspberry Pi Pico W + RFM9x (SX1276) running CircuitPython 8.x / 9.x / 10.x
#
# What it does
#   * Sends a short beacon ("Tomi" + 2 character sequence tag) to the repeater,
#     waits for the echo and measures RSSI / SNR of the reply.
#   * Works with the ORIGINAL repeater (plain echo) and with the v2 repeater
#     (echo + "|rssi|snr" as measured on the roof).
#   * Reports every beacon - successful or not - to the phone app as one JSON
#     line, over USB (preferred) and over Wi-Fi/UDP (phone hotspot) at the same
#     time. The phone de-duplicates using (boot, seq).
#
# Protocol: see docs/PROTOCOL.md.  Radio settings must match Repeater/code.py.

FW_VERSION = "2.0.0"

import gc
import json
import os
import time

import adafruit_rfm9x
import board
import busio
import digitalio
import microcontroller
import supervisor

try:
    import usb_cdc
except ImportError:
    usb_cdc = None

try:
    import socketpool
    import wifi
except ImportError:  # plain Pico (no W) or a build without wifi
    wifi = None
    socketpool = None


# ---------------------------------------------------------------- settings --
def _env(name, default):
    """Read an optional value from settings.toml, falling back to default."""
    try:
        value = os.getenv(name)
    except Exception:
        value = None
    if value is None:
        return default
    if isinstance(default, int):
        try:
            return int(value)
        except (TypeError, ValueError):
            return default
    return str(value)


def _clean_id(text):
    out = ""
    for ch in str(text):
        if ch.isalpha() or ch.isdigit() or ch in "-_":
            out += ch
    return out[:16] or "Tomi"


NODE_ID = _clean_id(_env("LSM_ID", "Tomi"))
RX_WINDOW_MS = max(1500, _env("LSM_RX_WINDOW_MS", 3500))  # listen time after TX ends
CYCLE_GAP_MS = max(0, _env("LSM_CYCLE_GAP_MS", 1000))  # pause after each cycle
MIN_PERIOD_MS = max(0, _env("LSM_MIN_PERIOD_MS", 0))  # min time between beacon starts
UDP_PORT = _env("LSM_UDP_PORT", 47820)
STATUS_EVERY_MS = 5000
PEER_TIMEOUT_MS = 20000  # forget the phone's UDP address after this much silence

# Radio settings - MUST match the repeater.
FREQ_MHZ = 869.5
TX_POWER = 20
BANDWIDTH = 125000
CODING_RATE = 8
SPREADING_FACTOR = 12
PREAMBLE = 8

_B36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"


# ------------------------------------------------------------------ helpers --
def now_ms():
    return time.monotonic_ns() // 1000000


def seq_tag(seq):
    n = seq % 1296
    return _B36[n // 36] + _B36[n % 36]


def lora_toa_ms(n_bytes):
    """Semtech time-on-air for an explicit-header packet of n_bytes (incl. 4 byte header)."""
    tsym = (1 << SPREADING_FACTOR) * 1000.0 / BANDWIDTH
    de = 1 if tsym > 16 else 0
    num = 8 * n_bytes - 4 * SPREADING_FACTOR + 28 + 16
    den = 4 * (SPREADING_FACTOR - 2 * de)
    payload_symbols = 8 + max(-(-num // den), 0) * CODING_RATE
    return int((PREAMBLE + 4.25 + payload_symbols) * tsym + 0.5)


def fnv1a_hex(text):
    h = 0x811C9DC5
    for b in text.encode():
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return "%08x" % h


def short_err(e):
    msg = "%s: %s" % (type(e).__name__, e)
    return msg[:120]


try:
    from microcontroller import watchdog as _wdt
    from watchdog import WatchDogMode

    _wdt.timeout = 8
    _wdt.mode = WatchDogMode.RESET
except Exception:
    _wdt = None


def feed():
    if _wdt is not None:
        try:
            _wdt.feed()
        except Exception:
            pass


class Led:
    def __init__(self):
        try:
            self._pin = digitalio.DigitalInOut(board.LED)
            self._pin.direction = digitalio.Direction.OUTPUT
        except Exception:
            self._pin = None

    def set(self, on):
        if self._pin is not None:
            try:
                self._pin.value = on
            except Exception:
                pass


# ------------------------------------------------- persistent configuration --
# Wi-Fi settings pushed from the phone app are kept in microcontroller.nvm so
# they survive power cycles (the CIRCUITPY drive is read-only to code.py).
_NVM_MAGIC = b"LSM1"


def nvm_load():
    nvm = getattr(microcontroller, "nvm", None)
    try:
        if nvm is None or len(nvm) < 8 or bytes(nvm[0:4]) != _NVM_MAGIC:
            return {}
        n = (nvm[4] << 8) | nvm[5]
        if n == 0 or n > len(nvm) - 6:
            return {}
        data = json.loads(bytes(nvm[6 : 6 + n]).decode())
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def nvm_save(data):
    nvm = getattr(microcontroller, "nvm", None)
    if nvm is None:
        raise RuntimeError("no nvm")
    raw = json.dumps(data).encode()
    blob = _NVM_MAGIC + bytes([len(raw) >> 8, len(raw) & 0xFF]) + raw
    if len(blob) > len(nvm):
        raise ValueError("config too big")
    nvm[0 : len(blob)] = blob


# --------------------------------------------------------------- USB link --
class UsbLink:
    """Line based link over USB CDC. Uses the dedicated data port when boot.py
    enabled it, otherwise the REPL console port."""

    def __init__(self):
        self.port = None
        self.kind = "none"
        self._buf = b""
        if usb_cdc is None:
            return
        data = getattr(usb_cdc, "data", None)
        console = getattr(usb_cdc, "console", None)
        if data is not None:
            self.port, self.kind = data, "data"
        elif console is not None:
            self.port, self.kind = console, "console"
        if self.port is not None:
            try:
                self.port.timeout = 0
                self.port.write_timeout = 0.25
            except Exception:
                pass

    def connected(self):
        try:
            return self.port is not None and self.port.connected
        except Exception:
            return False

    def send_line(self, line):
        if self.kind == "data":
            if self.connected():
                try:
                    self.port.write((line + "\n").encode())
                except Exception:
                    pass
        else:
            print(line)  # console: dropped by CircuitPython when no host is listening

    def poll(self):
        if self.port is None:
            return []
        try:
            n = self.port.in_waiting
            if not n:
                return []
            chunk = self.port.read(n)
        except Exception:
            return []
        if not chunk:
            return []
        self._buf += chunk
        if len(self._buf) > 4096:  # garbage without newlines - drop it
            self._buf = b""
            return []
        lines = []
        while True:
            i = self._buf.find(b"\n")
            if i < 0:
                break
            line = self._buf[:i].strip()
            self._buf = self._buf[i + 1 :]
            if line:
                lines.append(line)
        return lines


# ----------------------------------------------------------- Wi-Fi link --
class WifiLink:
    """Joins the phone's hotspot and talks UDP. The phone is the hotspot's
    gateway, so the Pico can send to it before it has heard from the app."""

    def __init__(self, ssid, password):
        self.ssid = ssid
        self.password = password
        self.state = "off"
        self.error = None
        self.ip = None
        self._pool = None
        self._sock = None
        self._peer = None
        self._peer_seen = 0
        self._next_try = 0
        self._backoff = 15000
        self._rx = bytearray(512)

    def configure(self, ssid, password):
        if ssid == self.ssid and password == self.password:
            return
        self.ssid, self.password = ssid, password
        self._close()
        self.error = None
        self._next_try = 0
        self._backoff = 15000
        try:
            if wifi is not None and wifi.radio.ipv4_address is not None:
                wifi.radio.stop_station()
        except Exception:
            pass

    def _link_up(self):
        try:
            return wifi.radio.ipv4_address is not None
        except Exception:
            return False

    def _close(self):
        if self._sock is not None:
            try:
                self._sock.close()
            except Exception:
                pass
        self._sock = None
        self._peer = None
        self.ip = None

    def _open(self):
        if self._pool is None:
            self._pool = socketpool.SocketPool(wifi.radio)
        sock = self._pool.socket(self._pool.AF_INET, self._pool.SOCK_DGRAM)
        try:
            sock.setblocking(False)
            sock.bind(("0.0.0.0", UDP_PORT))
        except Exception:
            sock.close()
            raise
        self._sock = sock
        self.ip = str(wifi.radio.ipv4_address)

    def maintain(self, usb_connected):
        """Keep the link up. May block up to ~5 s while (re)connecting."""
        if wifi is None or not self.ssid:
            self.state = "off"
            self._close()
            return False
        if self._link_up():
            if self._sock is None:
                try:
                    self._open()
                except Exception as e:
                    self.error = short_err(e)
                    self._close()
                    return False
            self.state = "up"
            return False
        changed = self.state == "up"
        self._close()
        t = now_ms()
        if t < self._next_try:
            self.state = "waiting"
            return changed
        self.state = "connecting"
        feed()
        try:
            wifi.radio.connect(self.ssid, self.password, timeout=5)
            feed()
            self._open()
            self.state = "up"
            self.error = None
            self._backoff = 15000
            return True
        except Exception as e:
            feed()
            self.error = short_err(e)
            self._close()
            self.state = "waiting"
            self._next_try = now_ms() + self._backoff
            limit = 120000 if usb_connected else 30000
            self._backoff = min(self._backoff * 2, limit)
            return True

    def _dest(self):
        if self._peer is not None and now_ms() - self._peer_seen < PEER_TIMEOUT_MS:
            return self._peer
        try:
            gw = wifi.radio.ipv4_gateway
        except Exception:
            gw = None
        return (str(gw), UDP_PORT) if gw is not None else None

    def send_line(self, line):
        if self._sock is None:
            return
        dest = self._dest()
        if dest is None:
            return
        try:
            self._sock.sendto(line.encode(), dest)
        except Exception:
            pass  # e.g. ARP not resolved yet; next message will retry

    def poll(self):
        if self._sock is None:
            return []
        out = []
        for _ in range(4):
            try:
                n, addr = self._sock.recvfrom_into(self._rx)
            except OSError as e:
                code = e.args[0] if e.args else None
                if code not in (11, 116):  # EAGAIN / ETIMEDOUT -> nothing waiting
                    self._close()
                break
            except Exception:
                break
            if n:
                self._peer = (addr[0], addr[1])
                self._peer_seen = now_ms()
                for line in bytes(self._rx[:n]).split(b"\n"):
                    line = line.strip()
                    if line:
                        out.append(line)
        return out


# --------------------------------------------------------------- the app --
class Mapper:
    def __init__(self):
        self.boot = "".join("%02x" % b for b in os.urandom(4))
        self.started = now_ms()
        self.seq = 0
        self.led = Led()
        self.usb = UsbLink()
        cfg = nvm_load()
        ssid = cfg.get("ssid") if cfg.get("ssid") else _env("LSM_WIFI_SSID", "")
        password = cfg.get("pass", "") if cfg.get("ssid") else _env("LSM_WIFI_PASSWORD", "")
        self.wifi = WifiLink(ssid, password)
        self.radio = None
        self.radio_error = None
        self._radio_failures = 0
        self._next_radio_try = 0
        self._spi = busio.SPI(board.GP18, MOSI=board.GP19, MISO=board.GP16)
        self._cs = digitalio.DigitalInOut(board.GP17)
        self._reset = digitalio.DigitalInOut(board.GP20)
        self.prev_error = None
        try:
            tb = supervisor.get_previous_traceback()
            if tb:
                self.prev_error = tb.strip().split("\n")[-1][:160]
        except Exception:
            pass
        self.last_status = None

    # ----- radio
    def init_radio(self):
        self._next_radio_try = now_ms() + 5000
        feed()
        try:
            r = adafruit_rfm9x.RFM9x(
                self._spi, self._cs, self._reset, FREQ_MHZ, baudrate=1_000_000
            )
            r.tx_power = TX_POWER
            r.signal_bandwidth = BANDWIDTH
            r.coding_rate = CODING_RATE
            r.spreading_factor = SPREADING_FACTOR
            r.preamble_length = PREAMBLE
            r.enable_crc = True
            try:
                r.low_datarate_optimize = True  # required at SF12/BW125
            except Exception:
                pass
            r.xmit_timeout = 4.0
            self.radio = r
            self.radio_error = None
            self._radio_failures = 0
            print("radio ready")
        except Exception as e:
            self.radio = None
            self.radio_error = short_err(e)
            print("radio init failed:", self.radio_error)

    def _radio_fault(self, e):
        self.radio_error = short_err(e)
        self._radio_failures += 1
        if self._radio_failures >= 3:
            self.radio = None  # re-initialise in a moment
            self._next_radio_try = now_ms() + 2000

    # ----- messaging
    def emit(self, obj):
        line = json.dumps(obj)
        self.usb.send_line(line)
        self.wifi.send_line(line)

    def status(self):
        cfg_hash = fnv1a_hex(self.wifi.ssid + "\n" + self.wifi.password) if self.wifi.ssid else ""
        st = {
            "lsm": 1,
            "t": "st",
            "boot": self.boot,
            "fw": FW_VERSION,
            "up": now_ms() - self.started,
            "id": NODE_ID,
            "seq": self.seq,
            "radio": "ok" if self.radio is not None else "error",
            "rerr": self.radio_error,
            "freq": FREQ_MHZ,
            "sf": SPREADING_FACTOR,
            "bw": BANDWIDTH,
            "cr": CODING_RATE,
            "txp": TX_POWER,
            "rxw": RX_WINDOW_MS,
            "gap": CYCLE_GAP_MS,
            "usb": self.usb.kind,
            "wifi": self.wifi.state,
            "ip": self.wifi.ip,
            "ssid": self.wifi.ssid,
            "wcfg": cfg_hash,
            "werr": self.wifi.error,
            "mem": gc.mem_free(),
        }
        if self.prev_error:
            st["prev"] = self.prev_error
        self.emit(st)
        self.last_status = now_ms()

    def ack(self, cmd, ok, msg=None):
        self.emit({"lsm": 1, "t": "ack", "boot": self.boot, "cmd": cmd, "ok": 1 if ok else 0, "msg": msg})

    def handle(self, raw):
        try:
            msg = json.loads(raw.decode())
        except Exception:
            return
        if not isinstance(msg, dict):
            return
        cmd = msg.get("cmd")
        if cmd == "hello":
            if now_ms() - self.last_status > 2500:
                self.status()
        elif cmd == "ping":
            self.ack("ping", True)
        elif cmd == "set":
            ssid = str(msg.get("wifi_ssid", ""))
            password = str(msg.get("wifi_pass", ""))
            if len(ssid) > 32 or (password and not 8 <= len(password) <= 63):
                self.ack("set", False, "ssid max 32 chars, password 8-63 chars or empty")
                return
            try:
                nvm_save({"ssid": ssid, "pass": password} if ssid else {})
            except Exception as e:
                self.ack("set", False, short_err(e))
                return
            if not ssid:
                ssid = _env("LSM_WIFI_SSID", "")
                password = _env("LSM_WIFI_PASSWORD", "")
            self.wifi.configure(ssid, password)
            self.ack("set", True)
            self.status()

    def service_links(self):
        for line in self.usb.poll():
            self.handle(line)
        for line in self.wifi.poll():
            self.handle(line)

    # ----- one beacon cycle
    def cycle(self):
        radio = self.radio
        self.seq += 1
        payload = NODE_ID + seq_tag(self.seq)
        data = payload.encode()
        res = {
            "lsm": 1,
            "t": "res",
            "boot": self.boot,
            "seq": self.seq,
            "pl": payload,
            "toa": lora_toa_ms(len(data) + 4),
        }
        ok = False
        rssi = snr = rrssi = rsnr = rtt = None
        other = 0
        crc_before = getattr(radio, "crc_error_count", 0)

        feed()
        self.led.set(True)
        t0 = now_ms()
        try:
            sent = radio.send(data)
            tx_ok = sent is not False  # very old library versions return None
        except Exception as e:
            tx_ok = False
            self._radio_fault(e)
        self.led.set(False)
        t_tx_end = now_ms()
        res["txms"] = t_tx_end - t0

        if tx_ok:
            deadline = t_tx_end + RX_WINDOW_MS
            while True:
                remaining = deadline - now_ms()
                if remaining <= 0:
                    break
                feed()
                try:
                    packet = radio.receive(timeout=remaining / 1000)
                except Exception as e:
                    self._radio_fault(e)
                    break
                if packet is None:
                    continue  # timeout (loop ends) or CRC error (keep listening)
                t_rx = now_ms()
                try:
                    text = str(packet, "ascii")
                except Exception:
                    other += 1
                    continue
                if text == payload:
                    ok = True  # original repeater: plain echo
                elif text.startswith(payload + "|"):
                    ok = True  # v2 repeater: echo|rssi|snr
                    parts = text[len(payload) + 1 :].split("|")
                    try:
                        rrssi = int(float(parts[0]))
                        rsnr = float(parts[1]) if len(parts) > 1 else None
                    except (ValueError, IndexError):
                        pass
                else:
                    other += 1
                    continue
                rssi = radio.last_rssi
                snr = getattr(radio, "last_snr", None)
                rtt = t_rx - t0
                break
            if self.radio is not None:
                self._radio_failures = 0
        else:
            res["err"] = "tx_failed"

        if ok:
            self.led.set(True)
            time.sleep(0.03)
            self.led.set(False)

        res["ok"] = 1 if ok else 0
        res["rssi"] = rssi
        res["snr"] = snr
        res["rrssi"] = rrssi
        res["rsnr"] = rsnr
        res["rtt"] = rtt
        res["crc"] = getattr(radio, "crc_error_count", 0) - crc_before
        res["other"] = other
        res["age"] = now_ms() - t0
        self.emit(res)
        if ok:
            print("#%d %s reply rssi=%s snr=%s rep_rssi=%s rep_snr=%s" % (self.seq, payload, rssi, snr, rrssi, rsnr))
        else:
            print("#%d %s no reply" % (self.seq, payload))

    # ----- main loop
    def run(self):
        print("LoRa Signal Map device fw", FW_VERSION, "boot", self.boot, "id", NODE_ID)
        self.led.set(True)
        time.sleep(0.3)
        self.led.set(False)
        self.init_radio()
        self.status()
        next_beacon = now_ms()
        errors = 0
        while True:
            feed()
            try:
                self.service_links()
                if self.wifi.maintain(self.usb.connected()):
                    self.status()  # Wi-Fi state changed
                    self.service_links()
                if self.radio is None and now_ms() >= self._next_radio_try:
                    self.init_radio()
                if now_ms() - self.last_status >= STATUS_EVERY_MS:
                    self.status()
                if self.radio is not None and now_ms() >= next_beacon:
                    start = now_ms()
                    self.cycle()
                    gc.collect()
                    next_beacon = max(now_ms() + CYCLE_GAP_MS, start + MIN_PERIOD_MS)
                else:
                    time.sleep(0.01)
                errors = 0
            except MemoryError:
                gc.collect()
                errors += 1
            except Exception as e:
                errors += 1
                print("loop error:", short_err(e))
                try:
                    self.emit({"lsm": 1, "t": "log", "boot": self.boot, "lvl": "error", "msg": short_err(e)})
                except Exception:
                    pass
                time.sleep(0.2)
            if errors > 25:
                raise RuntimeError("too many consecutive errors")


def run():
    # Pico W: writes to CIRCUITPY by the phone must not restart the mapper.
    try:
        supervisor.runtime.autoreload = False
    except Exception:
        try:
            supervisor.disable_autoreload()
        except Exception:
            pass
    try:
        Mapper().run()
    except KeyboardInterrupt:
        raise  # Ctrl-C: drop to the REPL as usual
    except Exception as e:
        print("fatal:", short_err(e), "- restarting in 5 s")
        for _ in range(10):
            feed()
            time.sleep(0.5)
        supervisor.reload()


if not globals().get("_LSM_TEST"):
    run()
