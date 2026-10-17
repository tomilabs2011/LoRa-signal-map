# LoRa-signal-map

Map the coverage of the rooftop LoRa repeater in Salgótarján. The mobile Pico W
("Device") sends a beacon, the repeater echoes it, and the Android app puts
**every beacon on a map at the GPS position where it was sent**, colored from
green (strong) through yellow and orange to red (weak). Beacons that got no
reply are shown as dark red rings. Every session is logged to a file the app
can reopen later, and a future desktop tool can analyse
([log format](docs/LOG_FORMAT.md)).

```
 Android app (Mi 10T Pro)  ── USB-C OTG cable (preferred) ──┐
   GPS + map + log         ── Wi-Fi: Pico joins the phone   ├── Pico W "Device" ))) LoRa 869.5 MHz ))) Repeater (roof)
                              hotspot, UDP 47820 ───────────┘     beacon "Tomi0H"  →  echo "Tomi0H[|rssi|snr]"
```

| Folder | What |
|---|---|
| `Device/` | Firmware for the mobile Pico W: `code.py`, `boot.py`, `settings.example.toml` |
| `Repeater/` | Repeater firmware **v2** (optional upgrade, the old one keeps working) |
| `android/` | The Android app (source, build script, tests) |
| `releases/` | Ready-to-install APK |
| `docs/` | [Log file format](docs/LOG_FORMAT.md) and [protocols](docs/PROTOCOL.md) |
| `tools/firmware-sim/` | Runs both firmwares on a PC against simulated radio/USB/Wi-Fi |

---

## Quick start (in the morning)

### 1. Install the app on the phone
1. Copy `releases/LoRaSignalMap-1.0.0.apk` to the phone (or download it from
   GitHub on the phone) and open it. Allow *Install unknown apps* for the file
   manager / browser when asked.
2. Requires Android 10+. Built for the Mi 10T Pro on LineageOS 22.1; no Google
   services are needed (it uses the phone's own GPS).

### 2. Update the mobile Pico ("Device")
Plug the Pico into a computer; the `CIRCUITPY` drive appears.
1. Copy `Device/code.py` and `Device/boot.py` onto `CIRCUITPY` (keep `lib/adafruit_rfm9x`).
2. **Unplug and replug** the Pico once (`boot.py` only runs after a power cycle).
3. Optional: copy settings you want from `Device/settings.example.toml` into
   `CIRCUITPY/settings.toml` (beacon name, timing, Wi-Fi). Defaults are fine.

The LED flashes at start-up, stays on while the beacon is transmitted, and
blinks briefly when the echo comes back.

> **No computer at hand?** The app also understands the **original** device
> firmware over USB (it reads its `Beacon sent` / `Received (ASCII): … dB`
> console output). You get the map with phone-side RSSI, but no SNR, no
> Wi-Fi link, and timing is a little less precise. Updating is strongly
> recommended.

The rooftop repeater does **not** need to be touched — see
[Repeater v2](#repeater-v2-optional) for what updating it adds.

### 3. Go mapping
1. Connect the Pico to the phone with a USB-C OTG cable. Android asks
   *"Open LoRa Signal Map to handle Pico W?"* → tick **Always** → **OK**.
2. In the app tap **Start**. Allow **Location → Precise**, **Notifications**,
   and (once) **unrestricted battery use**, so logging never pauses in your pocket.
3. Watch the status card: **USB** turns green, the device line shows
   `Pico fw 2.0.0 · Tomi · seen 1 s ago`, **GPS ±x m** turns green.
4. Walk/drive. Every beacon becomes a dot; the bottom card shows the last
   reading and the session counters. The screen stays on while mapping
   (Settings → *Keep screen on*). The notification has a **Stop** button.
5. Tap **Stop** when done. The session is saved and stays on the map, together
   with all earlier sessions, so the map of the city fills up over time.

### 4. Optional: Wi-Fi link as well
Both links can run at the same time; every result is sent over both and the
app keeps the first copy, so a wobbly cable costs nothing.
1. Phone: turn on the hotspot. It **must be 2.4 GHz** and **WPA2** (or
   WPA2/WPA3 mixed) — the Pico W cannot do 5 GHz or WPA3-only.
2. App → Settings → *Device Wi-Fi link*: enter the hotspot name and password.
3. With the Pico on USB and mapping started, the app stores these on the Pico
   (in its non-volatile memory) automatically. The **Wi-Fi** chip turns green
   within ~15 s. From then on the Pico rejoins the hotspot by itself, even
   without USB.

---

## Using the map

* **Colors**: continuous scale from red (worst) → orange → yellow → green (best).
  Default scale −135 … −80 dBm; change it in Settings → *Color scale*.
* **What is colored**: "signal" = packet strength as the SX1276 datasheet
  defines it (RSSI, plus SNR when SNR is negative — below 0 dB SNR the RSSI
  register only measures noise). By default the **weaker of both directions**
  is used when the repeater reports its side. The layers button switches to
  SNR, or to one direction only.
* **Dark red ring** = beacon sent, no reply.
* **Track line**: consecutive beacons are joined and the line blends from one
  beacon's color to the next.
* **Tap a dot**: shows every beacon at that spot (all passes, all sessions):
  time, phone-side RSSI/SNR, repeater-side RSSI/SNR (repeater v2), round-trip
  time, CRC errors heard, GPS accuracy, speed, distance to the repeater, and
  whether it came over USB or Wi-Fi.
* **Long-press the map** to place the repeater (used for distances).
* Buttons on the right: layers · follow my position · zoom to all data ·
  sessions · settings.
* **Sessions**: show/hide each one on the map, tap one to zoom to it, share the
  log, export CSV, delete, or import a `.jsonl` log (⋮ / folder icon).

## Logs

* One file per session: `Android/data/com.tomilabs.lorasignalmap/files/sessions/lsm_YYYYMMDD_HHMMSS.jsonl`
  (reachable over USB from a PC; also shareable from the Sessions screen).
* JSON Lines: one record per line, header first, flushed after every line, so a
  crash or dead battery loses nothing already written.
* Every beacon is logged, including failed ones and ones without GPS fix.
* Full specification for the desktop app: **[docs/LOG_FORMAT.md](docs/LOG_FORMAT.md)**.

## Device firmware (`Device/code.py`, v2.0.0)

* Beacon = `LSM_ID` (default `Tomi`) + 2-character sequence tag, e.g. `Tomi0H`.
  Same air time as the old `Tomi` (≈1.19 s at SF12), and an echo is only
  accepted if it matches the current beacon, so stray packets are never
  counted as replies.
* Listens 3.5 s after transmitting (the old 2 s could miss late echoes:
  repeater delay 0.2–0.6 s + 1.2 s echo), then pauses 1 s, like before.
* Reports per beacon: reply yes/no, RSSI and SNR of the echo, repeater-side
  RSSI/SNR (v2 repeater), round-trip time, CRC-error packets heard.
* USB: uses the extra "data" serial port created by `boot.py` (falls back to
  the console port without it). Wi-Fi: joins the hotspot, talks UDP to the phone.
* Robustness: hardware watchdog (resets a hung board after 8 s), radio
  re-initialisation on errors, automatic restart after unexpected errors,
  auto-reload disabled so the phone mounting `CIRCUITPY` can't restart it.
  After editing files from a PC, press **Ctrl+D** in the serial console (or
  replug) to run the new code.
* Settings: see `Device/settings.example.toml`. Radio parameters are fixed in
  the code and must match the repeater.

## Repeater v2 (optional)

`Repeater/code.py` is a drop-in replacement for the roof. It echoes exactly
like before but appends what the **repeater** measured (`Tomi0H|-97|-3.25`), so
the map shows both directions of the link. It also never crashes on garbage
packets (the old one can stop on a garbled/binary packet), does not echo other
repeaters' replies, and has a watchdog. The device firmware works with both
versions, so update the roof only when convenient.

## Building from source

* **Android Studio**: open `android/` (Gradle, AGP 8.7). Put the signing key at
  `android/keystore/lorasignalmap.jks` (not in git, the repo is public) so
  your builds install over the released APK without uninstalling — uninstalling
  deletes the app's logs. *This Gradle build was written but could not be run
  where the APK was built (no access to Google's Maven there).*
* **Script** (Ubuntu/Debian, no Android SDK needed): `android/build.sh` uses
  the distro's `aapt2`, `dx`, `zipalign`, `apksigner` packages plus two jars
  from Maven Central. This is how `releases/` was produced.

## Tests

* `python3 tools/firmware-sim/test_sim.py` and `python3 tools/firmware-sim/test_repeater.py`
  run the real firmware files against simulated radio, repeater, USB, Wi-Fi,
  NVM and watchdog.
* `android/app/src/test/`: JUnit + Robolectric tests — log format round-trip,
  dedup/timing/geotagging, legacy console parsing, Wi-Fi config push, and the
  real activities and foreground service end to end over a real UDP socket with
  simulated GPS.

## Notes

* **Duty cycle**: 869.4–869.65 MHz allows 10 % transmit time. One SF12 beacon is
  ≈1.2 s, so the default cycle (~4 s) transmits ~30 % of the time, the same
  as the original firmware. For compliance set `LSM_MIN_PERIOD_MS = 12000` in
  `settings.toml` (the repeater's echoes count for the repeater too).
* Map tiles (CARTO / OpenStreetMap) need mobile data; areas you have looked at
  are cached and work offline later.
* Nothing here has run on the real phone or Pico yet. The app was exercised in
  Robolectric (Android 14 framework) and the firmware in simulation.

### Troubleshooting

| Symptom | Fix |
|---|---|
| USB chip stays grey | Replug; accept the USB permission dialog; check the cable is a data cable. |
| "Waiting for the device…" for long | The Pico may run old firmware without `boot.py`: replug it once after copying `boot.py`. |
| "No data from the device for …" | Pico reset or hung; it restarts itself within ~10 s. Replug if it persists. |
| Wi-Fi chip never green | Hotspot must be 2.4 GHz + WPA2; check SSID/password in Settings; Pico needs to have been on USB once with mapping started. |
| GPS… for minutes | Go outside, wait for a fix; Location must be on and set to Precise. |
| App stopped unexpectedly | On next start it shows the error report — use *Share* to send it. |
