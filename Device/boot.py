# boot.py - runs once after power-up or a hard reset (NOT on soft reload).
#
# Adds a second USB serial port ("data") next to the normal REPL console, so
# the phone app gets a clean channel that never mixes with REPL output.
# If this file is missing, code.py falls back to the console port - the app
# understands both.
#
# After copying boot.py to CIRCUITPY, unplug and replug the Pico once.

import usb_cdc

try:
    usb_cdc.enable(console=True, data=True)
except Exception as e:
    print("usb_cdc.enable failed:", e)
