# LoRa Signal Map - rooftop repeater firmware v2 (CircuitPython, Pico + RFM9x)
#
# Echoes every packet it hears, exactly like v1, but appends what the repeater
# measured:  "<original text>|<rssi>|<snr>"   e.g.  "Tomi0H|-97|-3.25"
# The device firmware understands both the old plain echo and this format, so
# updating the repeater is optional. v2 also never crashes on binary packets,
# does not echo other repeaters' replies, and restarts itself if it hangs.

import random
import time

import adafruit_rfm9x
import board
import busio
import digitalio
import supervisor

REPORT_LINK = True  # False = behave exactly like v1 (plain echo)

# Radio settings - MUST match Device/code.py.
FREQ_MHZ = 869.5
TX_POWER = 20
BANDWIDTH = 125000
CODING_RATE = 8
SPREADING_FACTOR = 12
PREAMBLE = 8

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


def fmt_snr(snr):
    text = "%.2f" % snr
    return text.rstrip("0").rstrip(".") if "." in text else text


print("kaposzta")  # repeater v2

led = digitalio.DigitalInOut(board.LED)
led.direction = digitalio.Direction.OUTPUT
spi = busio.SPI(board.GP18, MOSI=board.GP19, MISO=board.GP16)
cs = digitalio.DigitalInOut(board.GP17)
reset = digitalio.DigitalInOut(board.GP20)


def make_radio():
    r = adafruit_rfm9x.RFM9x(spi, cs, reset, FREQ_MHZ, baudrate=1_000_000)
    r.tx_power = TX_POWER
    r.signal_bandwidth = BANDWIDTH
    r.coding_rate = CODING_RATE
    r.spreading_factor = SPREADING_FACTOR
    r.preamble_length = PREAMBLE
    r.enable_crc = True
    r.low_datarate_optimize = True  # required above ~16ms symbol time (true at SF12/BW125)
    r.xmit_timeout = 4.0  # generous vs. the ~1.7s a reply takes to send at SF12
    return r


def run():
    radio = None
    errors = 0
    led.value = True
    time.sleep(0.5)
    led.value = False
    while True:
        feed()
        try:
            if radio is None:
                radio = make_radio()
                print("radio ready")
            packet = radio.receive(timeout=2.0)
            if packet is None:
                errors = 0
                continue
            rssi = radio.last_rssi
            snr = getattr(radio, "last_snr", None)
            led.value = True
            time.sleep(0.1)
            led.value = False
            try:
                text = str(packet, "ascii")
            except Exception:
                text = None
            print("Received (ASCII): %s %s dB" % (text if text is not None else packet, rssi))
            if text is not None and "|" in text:
                continue  # a reply from another repeater - echoing it would ping-pong
            time.sleep(random.randint(1, 5) * 0.1)
            if REPORT_LINK and text is not None and snr is not None and len(text) <= 230:
                reply = "%s|%d|%s" % (text, rssi, fmt_snr(snr))
            else:
                reply = packet
            feed()
            radio.send(reply)
            led.value = True
            time.sleep(0.01)
            led.value = False
            errors = 0
        except KeyboardInterrupt:
            raise
        except Exception as e:
            errors += 1
            print("error:", e)
            time.sleep(0.5)
            if errors >= 5:
                radio = None  # re-initialise the radio
            if errors >= 20:
                raise


try:
    run()
except KeyboardInterrupt:
    raise
except Exception as e:
    print("fatal:", e, "- restarting in 5 s")
    for _ in range(10):
        feed()
        time.sleep(0.5)
    supervisor.reload()
