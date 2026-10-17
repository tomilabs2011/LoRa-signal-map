import json
import sys

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
import sim  # noqa: E402

failures = 0


def check(cond, msg):
    global failures
    if not cond:
        failures += 1
        print("FAIL:", msg)
    else:
        print("ok:  ", msg)


# 1. legacy repeater over USB data port
r = sim.run({"success": 0.7, "seed": 3, "noise": 0.1, "other_traffic": 0.1}, 300)
msgs = sim.lines_from(r["usb"].data)
res = [m for m in msgs if m["t"] == "res"]
st = [m for m in msgs if m["t"] == "st"]
check(len(res) > 40, "legacy: %d results in 300 s" % len(res))
check([m["seq"] for m in res] == list(range(1, len(res) + 1)), "legacy: seq contiguous")
check(all(m["pl"] == "Tomi" + sim_tag for m, sim_tag in zip(res, [r["g"]["seq_tag"](i) for i in range(1, len(res) + 1)])), "payload tags")
oks = [m for m in res if m["ok"]]
fails = [m for m in res if not m["ok"]]
check(0.5 < len(oks) / len(res) < 0.9, "legacy: success ratio %.2f" % (len(oks) / len(res)))
check(all(m["rssi"] is not None and m["rtt"] is not None and m["rrssi"] is None for m in oks), "legacy: ok has rssi/rtt, no rrssi")
check(all(m["rssi"] is None and m["rtt"] is None for m in fails), "legacy: fail has no rssi")
check(all(m["age"] >= (m["rtt"] or 0) for m in res), "age >= rtt")
check(all(m["toa"] == 1188 for m in res), "toa = 1188 ms")
check(len(st) >= 300 / 7, "status at least every ~6 s (%d)" % len(st))
check(r["wdt_gap"] < 8, "watchdog max gap %.2f s" % r["wdt_gap"])
check(any(m["crc"] > 0 for m in res), "crc errors counted")
check(any(m["other"] > 0 for m in res), "foreign packets counted")
check(st[0]["usb"] == "data" and st[0]["wifi"] == "off", "status fields")
tx = r["radio"]["tx"]
gaps = [(b[0] - a[0]) / 1e9 for a, b in zip(tx, tx[1:])]
print("   cycle period min %.2f max %.2f" % (min(gaps), max(gaps)))
check(min(gaps) > 2.3 and max(gaps) < 6.0, "cycle period sane")

# 2. v2 repeater
r = sim.run({"success": 0.9, "v2": True, "seed": 5}, 120)
res = [m for m in sim.lines_from(r["usb"].data) if m["t"] == "res"]
oks = [m for m in res if m["ok"]]
check(len(oks) > 10 and all(isinstance(m["rrssi"], int) and isinstance(m["rsnr"], float) for m in oks), "v2: repeater rssi/snr parsed")

# 3. Wi-Fi pushed over USB, then UDP peer learned
def push_wifi(usb, net, mapper):
    usb.data.inbox += b'garbage\n{"lsm":1,"cmd":"set","wifi_ssid":"Hotspot","wifi_pass":"secret123"}\n'


def app_hello(usb, net, mapper):
    net["rx"].append((b'{"lsm":1,"cmd":"hello","app":"1.0"}\n', ("192.168.43.7", 47820)))


def junk_udp(usb, net, mapper):
    net["rx"].append((b"\x00\x01\x02", ("192.168.43.7", 47820)))


r = sim.run({"hotspot": "Hotspot", "hotspot_pass": "secret123"}, 120, [(10, push_wifi), (40, junk_udp), (50, app_hello)])
usb_msgs = sim.lines_from(r["usb"].data)
acks = [m for m in usb_msgs if m["t"] == "ack"]
check(acks and acks[0]["cmd"] == "set" and acks[0]["ok"] == 1, "set ack")
cfg = bytes(r["nvm"][:4])
check(cfg == b"LSM1", "config stored in nvm")
sent = r["net"]["sent"]
check(len(sent) > 10, "udp messages sent: %d" % len(sent))
dests = set(d for _, d, _ in sent)
check(("192.168.43.1", 47820) in dests and ("192.168.43.7", 47820) in dests, "sent to gateway then peer: %s" % dests)
udp_res = [json.loads(p) for _, _, p in sent if json.loads(p)["t"] == "res"]
usb_res = [m for m in usb_msgs if m["t"] == "res"]
check(set(m["seq"] for m in udp_res) <= set(m["seq"] for m in usb_res), "udp results are duplicates of usb results")
st = [m for m in usb_msgs if m["t"] == "st"]
check(st[-1]["wifi"] == "up" and st[-1]["ip"] == "192.168.43.20" and len(st[-1]["wcfg"]) == 8, "status shows wifi up")

# 3b. nvm config survives a reboot
mods_nvm = r["nvm"]
g = r["g"]
import types  # noqa: E402
fake_mc = types.SimpleNamespace(nvm=mods_nvm)
g["microcontroller"] = fake_mc
check(g["nvm_load"]() == {"ssid": "Hotspot", "pass": "secret123"}, "nvm_load round trip")

# 4. Wrong hotspot: backoff, watchdog
r = sim.run({"hotspot": "Other", "hotspot_pass": "x" * 8}, 600, [(1, push_wifi)])
check(r["wdt_gap"] < 8, "watchdog ok with failing wifi (max gap %.2f)" % r["wdt_gap"])
check(r["net"]["connects"] <= 12, "connect attempts with backoff: %d" % r["net"]["connects"])
res = [m for m in sim.lines_from(r["usb"].data) if m["t"] == "res"]
check(len(res) > 100, "beacons keep flowing while wifi fails (%d)" % len(res))

# 5. no data port -> console fallback (print); no wifi module
import io, contextlib  # noqa: E402,E401
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    r = sim.run({"data_port": False, "wifi_module": False}, 60)
out = buf.getvalue().splitlines()
js = [json.loads(l) for l in out if l.startswith("{")]
check(any(m["t"] == "res" for m in js), "console fallback emits JSON lines")
check(not any(l.startswith("Beacon sent") or l.startswith("Received (ASCII)") for l in out), "no legacy-looking console lines")

# 6. radio init failures then recovery
r = sim.run({"radio_init_failures": 2}, 60)
msgs = sim.lines_from(r["usb"].data)
check(msgs[0]["t"] == "st" and msgs[0]["radio"] == "error", "radio error reported")
check(any(m["t"] == "res" for m in msgs), "radio recovered")

# 7. usb not connected (no DTR) -> nothing written to data port, no crash
r = sim.run({"usb_connected": False}, 30)
check(len(r["usb"].data.out) == 0, "no writes without DTR")

# 8. toa helper
g = r["g"]
check(g["lora_toa_ms"](8) == 1188 and g["lora_toa_ms"](10) == 1188 and g["lora_toa_ms"](19) == 1712, "toa formula %d %d %d" % (g["lora_toa_ms"](8), g["lora_toa_ms"](10), g["lora_toa_ms"](19)))
check(g["fnv1a_hex"]("Hotspot\nsecret123") == g["fnv1a_hex"]("Hotspot\nsecret123") and len(g["fnv1a_hex"]("")) == 8, "fnv")
print("FNV test vector:", g["fnv1a_hex"]("Hotspot\nsecret123"), g["fnv1a_hex"]("a"))

print("\nFAILURES:", failures)
sys.exit(1 if failures else 0)
