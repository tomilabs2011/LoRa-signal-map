
import os
import time

import adafruit_rfm9x
import board
import busio
import digitalio
import random



print("kaposzta")

spi = busio.SPI(board.GP18, MOSI=board.GP19, MISO=board.GP16,)
cs = digitalio.DigitalInOut(board.GP17)
reset = digitalio.DigitalInOut(board.GP20)
radio = adafruit_rfm9x.RFM9x(spi, cs, reset, 869.5, baudrate=1_000_000)
radio.tx_power = 20
radio.signal_bandwidth = 125_000 # SIGNAL_BANDWIDTH_HZ
radio.coding_rate = 8
radio.spreading_factor = 12
radio.preamble_length = 8
radio.enable_crc = True
radio.low_datarate_optimize = True  # required above ~16ms symbol time (true at SF12/BW125)
radio.xmit_timeout = 4.0  # generous vs. the ~1.5s a packet takes to send at SF12
#radio._write_u8(_REG_OCP, _OCP_150MA)
led = digitalio.DigitalInOut(board.LED)
led.direction = digitalio.Direction.OUTPUT

led.value=True
time.sleep(0.5)
led.value=False


while True:
    radio.send("Tomi")
    print("Beacon sent")

    
    
    packet = radio.receive(timeout=2)

    if not packet is None:
        packet_text = str(packet, "ascii")
        rssi = radio.last_rssi
        print(f"Received (ASCII): {packet_text} {rssi} dB")
	 

    time.sleep(1)


# receive
while True:
    packet = radio.receive()

    if not packet is None:
        # Print out the raw bytes of the packet:
        led.value=True
        time.sleep(0.1)
        led.value=False
        packet_text = str(packet, "ascii")
        rssi = radio.last_rssi
        print(f"Received (ASCII): {packet_text} {rssi} dB")
        time.sleep(random.randint(1,5)*0.1)
        radio.send(packet_text)
        led.value=True
        time.sleep(0.01)
        led.value=False
