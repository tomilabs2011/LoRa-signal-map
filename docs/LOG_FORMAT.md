# LoRa Signal Map log format — version 1

This is the file format the Android app writes for every mapping session and
reads back when you open a session. It is meant to be the input of a future
desktop analysis tool, so it is written to be easy and safe to parse.

## The file

* **JSON Lines**: UTF-8 text, one complete JSON object per line, lines end
  with `\n`. Extension `.jsonl`.
* File name: `lsm_YYYYMMDD_HHMMSS.jsonl` (phone local time at session start).
  The name is only a convenience; everything needed is inside the file.
* Location on the phone: `Android/data/com.tomilabs.lorasignalmap/files/sessions/`
  (visible over USB from a PC). The app can also share/export files.
* Append-only. Every line is flushed to storage as soon as it is written, so
  a crash or a dead battery loses at most the line being written.
* The **first line is always the `header`** record.

### Rules for readers

1. Read line by line. Skip empty lines.
2. If a line does not parse as JSON (e.g. a half-written last line after a
   crash), skip it and keep going.
3. Every record has a `"type"` string. Ignore record types you don't know.
4. Ignore fields you don't know. Missing fields and `null` mean the same:
   "not available".
5. A session that has no `end` record was interrupted (app killed, phone
   died); all records before that point are valid.
6. Version: `header.version` is `1`. A future incompatible change will bump
   it; compatible additions (new fields / record types) will not.

### Common conventions

| Thing | Convention |
|---|---|
| `t` | Time of the event, Unix epoch **milliseconds, UTC**, integer, from the phone clock. |
| Coordinates | WGS84 decimal degrees, 7 decimals (~1 cm). |
| Signal values | dBm (RSSI) and dB (SNR) exactly as reported by the SX1276 (RSSI integer, SNR in 0.25 dB steps). |
| Booleans | JSON `true` / `false`. |

## Record types

### `header` (first line)

```json
{"type":"header","format":"lora-signal-map","version":1,"session":"4f1c2a9e-7d1b-4a59-9a5e-2f1f0c6b7e11","t":1792222212345,"tz":"Europe/Budapest","tz_offset_min":120,"app":{"id":"com.tomilabs.lorasignalmap","version":"1.0.0","build":1},"phone":{"make":"Xiaomi","model":"M2007J3SG","android":"15","sdk":35},"repeater":{"lat":48.1001234,"lon":19.8051234}}
```

| Field | Meaning |
|---|---|
| `format` | Always `"lora-signal-map"`. Use it to recognise the file. |
| `version` | Format version, `1`. |
| `session` | Random UUID of the session. |
| `t` | Session start. |
| `tz`, `tz_offset_min` | Phone time zone at start (for displaying local times). |
| `app`, `phone` | Software / hardware that wrote the file. |
| `repeater` | Repeater position set in the app (`null` if not set). |

### `m` — one beacon (measurement)

Written for **every** beacon the device sent, successful or not.

```json
{"type":"m","t":1792222215012,"boot":"9f3a1c2e","seq":17,"pl":"Tomi0H","ok":true,"rssi":-97,"snr":-3.5,"rrssi":-101,"rsnr":-6.25,"signal":-100.5,"rtt":1850,"crc":0,"other":0,"toa":1188,"txms":1190,"lat":48.0981234,"lon":19.8031234,"alt":312.4,"acc":4.2,"spd":1.3,"brg":87.0,"sats":14,"pos":"interp","fix_age":120,"rx_lat":48.0981301,"rx_lon":19.8031412,"via":"usb"}
```

| Field | Type | Meaning |
|---|---|---|
| `t` | int | When the beacon **started transmitting** (phone clock). |
| `boot` | string | Random id the device picks at every boot. `"legacy"` for the old firmware. |
| `seq` | int | Beacon counter, restarts at 1 when `boot` changes. `(boot, seq)` is unique. |
| `pl` | string | Exact beacon payload sent over LoRa (`"Tomi"` + 2 char base-36 sequence tag). |
| `ok` | bool | `true` = the repeater's echo was received. `false` = no reply (or TX failed). |
| `rssi` | int\|null | RSSI of the echo **at the device** (downlink, repeater → you), dBm. |
| `snr` | number\|null | SNR of the echo at the device, dB. |
| `rrssi` | int\|null | RSSI of the beacon **at the repeater** (uplink, you → repeater), dBm. Only with repeater firmware v2. |
| `rsnr` | number\|null | SNR of the beacon at the repeater, dB. v2 repeater only. |
| `signal` | number\|null | The value the map colors by (see below). Stored for convenience; recompute it from the raw fields if you prefer. |
| `rtt` | int\|null | ms from TX start until the echo was fully received. |
| `crc` | int | Packets that arrived with a CRC error during this beacon's listen window ("heard something but it was corrupted"). |
| `other` | int | Other valid packets heard during the window that were not our echo. |
| `toa` | int | Calculated time on air of the beacon, ms. |
| `txms` | int | Measured transmit duration, ms. |
| `lat`, `lon` | number\|null | Phone position at `t`. `null` if there was no GPS fix. |
| `alt` | number\|null | Altitude, m (WGS84 ellipsoid, as Android reports it). |
| `acc` | number\|null | Horizontal accuracy, m (68 %). |
| `spd` | number\|null | Speed, m/s. |
| `brg` | number\|null | Bearing, degrees. |
| `sats` | int\|null | Satellites used in the fix. |
| `pos` | string | How the position was obtained: `"interp"` (interpolated between two GPS fixes around `t`), `"nearest"` (closest fix), `"none"`. |
| `fix_age` | int\|null | ms between `t` and the nearest GPS fix used. Large values mean the position is stale. |
| `rx_lat`, `rx_lon` | number\|null | Phone position when the echo was received (`t + rtt`). Only when `ok`. |
| `via` | string | Link that delivered the result first: `"usb"` or `"wifi"`. |

**`signal` — the coloring value.** For each direction the app computes the
packet signal strength the way the SX1276 datasheet defines it:
`strength = rssi + min(snr, 0)` (below 0 dB SNR the RSSI register only
measures the noise floor, so the SNR is added). `signal` is the weaker of the
downlink (`rssi`,`snr`) and uplink (`rrssi`,`rsnr`) strengths, or just the
downlink one when the repeater does not report. `null` when `ok` is false.

### `device` — device information

Written when a device is first seen in the session and whenever its reported
state changes (reboot, Wi-Fi up/down, …).

```json
{"type":"device","t":1792222213001,"boot":"9f3a1c2e","fw":"2.0.0","id":"Tomi","freq":869.5,"sf":12,"bw":125000,"cr":8,"txp":20,"rxw":3500,"usb":"data","wifi":"up","ip":"192.168.43.20","radio":"ok","legacy":false}
```

`freq` MHz, `sf` spreading factor, `bw` Hz, `cr` coding rate denominator
(4/`cr`), `txp` TX power dBm, `rxw` listen window ms, `usb` which USB port the
firmware uses, `wifi` Wi-Fi state, `radio` `"ok"`/`"error"`, `legacy` true
when the data comes from the original firmware's text output.

### `link` — phone ↔ device link changes

```json
{"type":"link","t":1792222213001,"link":"usb","state":"up","detail":"Pico W (239a:8120)"}
```

`link` is `"usb"` or `"wifi"`, `state` is `"up"` or `"down"`.

### `gps` — GPS state changes

```json
{"type":"gps","t":1792222213001,"state":"fix","acc":3.9}
```

`state`: `"fix"`, `"lost"`, `"disabled"`.

### `end` — clean end of session

```json
{"type":"end","t":1792223912345,"count":412,"ok":367}
```

## CSV export

The app can also export a session as CSV (one row per `m` record) with the
columns `time_utc, t_ms, session, boot, seq, ok, signal, rssi, snr, rrssi,
rsnr, lat, lon, alt, acc, spd, sats, rtt, crc, other, via`. The JSONL file
is the master copy; CSV is for spreadsheets.
