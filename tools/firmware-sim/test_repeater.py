"""Simulates Repeater/code.py: echo format, binary packets, no ping-pong."""
import contextlib
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sim  # noqa: E402

clock, mods, usb, net, rs, mc = sim.make_env({})
incoming = [b"Tomi0H", b"\xff\xfe\x00bin", b"Tomi0J|-90|2.5", b"Tomi0K"]
sent = []
class R:
    def __init__(self, *a, **k): self.crc_error_count = 0; self.last_rssi = -97; self.last_snr = -3.25
    def receive(self, timeout=0.5):
        clock.advance(0.2)
        if incoming: return bytearray(incoming.pop(0))
        raise sim.Stop()
    def send(self, d): sent.append(d); clock.advance(1.7); return True
mods["adafruit_rfm9x"].RFM9x = R
sys.modules.update(mods)
buf = io.StringIO()
try:
    with contextlib.redirect_stdout(buf):
        exec(compile(open(os.path.join(sim.REPO, "Repeater", "code.py")).read(), "code.py", "exec"), {"__name__": "x"})
except sim.Stop:
    pass
print(buf.getvalue()); print("SENT:", sent, "wdt gap", mc.watchdog.max_gap)
assert sent[0] == "Tomi0H|-97|-3.25" and sent[1] == bytearray(b"\xff\xfe\x00bin") and sent[2] == "Tomi0K|-97|-3.25" and len(sent) == 3
print("REPEATER OK")
