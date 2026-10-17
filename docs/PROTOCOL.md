# Protocols

Three hops: **phone app ⇄ Pico W "Device" ⇄ LoRa ⇄ rooftop "Repeater"**.

## 1. LoRa (device ⇄ repeater)

Radio settings (both sides must match): 869.5 MHz, SF12, BW 125 kHz,
CR 4/8, preamble 8, CRC on, low data rate optimisation on, 20 dBm.
Packets carry the 4-byte RadioHead header added by `adafruit_rfm9x`.

| Packet | Payload (ASCII) | Example |
|---|---|---|
| Beacon (device → repeater) | `<ID><TAG>` — ID from `LSM_ID` (default `Tomi`), TAG = beacon sequence number mod 1296 as 2 base-36 chars | `Tomi0H` |
| Echo, repeater v1 (original) | the beacon text unchanged | `Tomi0H` |
| Echo, repeater v2 | `<beacon>\|<rssi>\|<snr>` measured at the repeater | `Tomi0H\|-97\|-3.25` |

The device accepts an echo only if it equals its current beacon or starts with
`<beacon>|`, so stray packets are never mistaken for a reply. A 6-character
beacon costs the same air time as the old 4-character `Tomi` (24 payload
symbols, ≈1.19 s). A v2 echo is ≈1.71 s.

Cycle: transmit (≈1.2 s) → listen up to `LSM_RX_WINDOW_MS` (3.5 s) after TX
ends → pause `LSM_CYCLE_GAP_MS` (1 s) → next beacon.

## 2. Phone ⇄ device (USB and Wi-Fi)

Both links carry the same thing: **one JSON object per line** (UDP: one or
more lines per datagram). Device messages have `"lsm":1` and a type `"t"`.

### Links

* **USB** (preferred): USB CDC-ACM serial. With `Device/boot.py` installed the
  Pico exposes a second serial port ("data") used only for this protocol; the
  app opens every CDC port of the Pico and uses whichever carries protocol
  lines. The app asserts DTR (the firmware only writes when DTR is set).
* **Wi-Fi**: the Pico joins the phone's hotspot and exchanges UDP datagrams on
  port **47820** (both sides). Until it has heard from the app it sends to the
  hotspot gateway (= the phone); afterwards to the address the app's `hello`
  came from. The app sends `hello` every 3 s to the broadcast address of each
  local network and to the last known device address.

Every result is sent on **both** links when both are up. The app keeps the
first copy and drops duplicates by `(boot, seq)`.

### Device → phone

`st` — status, at boot, every ~5 s (between beacon cycles), after Wi-Fi changes, and in reply to `hello` (at most every 2.5 s):

```json
{"lsm":1,"t":"st","boot":"9f3a1c2e","fw":"2.0.0","up":123456,"id":"Tomi","seq":17,"radio":"ok","rerr":null,"freq":869.5,"sf":12,"bw":125000,"cr":8,"txp":20,"rxw":3500,"gap":1000,"usb":"data","wifi":"up","ip":"192.168.43.20","ssid":"MyHotspot","wcfg":"7de48b26","werr":null,"mem":91234}
```

`wcfg` is the FNV-1a 32-bit hash (8 hex chars) of `ssid + "\n" + password`
of the Wi-Fi config the device uses (`""` when none) — lets the app see
whether its saved hotspot config is already on the device without the device
ever sending the password. `prev` (optional) is the last line of the previous
crash traceback.

`res` — result of one beacon, sent right after its listen window closes:

```json
{"lsm":1,"t":"res","boot":"9f3a1c2e","seq":17,"pl":"Tomi0H","toa":1188,"txms":1190,"ok":1,"rssi":-97,"snr":-3.5,"rrssi":-101,"rsnr":-6.25,"rtt":1850,"crc":0,"other":0,"age":2310}
```

`age` = ms between the start of the transmission and the moment this line
was sent. The phone subtracts it from its receive time to find when the
beacon was sent, and looks up its GPS track at that instant. `err` is
`"tx_failed"` if the radio did not finish transmitting.

`ack` — answer to a command: `{"lsm":1,"t":"ack","cmd":"set","ok":1,"msg":null}`

`log` — firmware error report: `{"lsm":1,"t":"log","lvl":"error","msg":"..."}`

### Phone → device

```json
{"lsm":1,"cmd":"hello","app":"1.0.0"}
{"lsm":1,"cmd":"ping"}
{"lsm":1,"cmd":"set","wifi_ssid":"MyHotspot","wifi_pass":"secret123"}
```

`set` stores the hotspot credentials in the Pico's non-volatile memory
(`microcontroller.nvm`) and reconnects. An empty `wifi_ssid` clears the stored
config (falls back to `settings.toml`).

### Original firmware (legacy) support

If the Pico still runs the original `code.py`, the app parses its console
text instead: `Beacon sent` starts a cycle, `Received (ASCII): <text> <rssi> dB`
completes it with that RSSI, and a cycle with no `Received` line within 4 s
(or followed by the next `Beacon sent`) is logged as a failed beacon.
