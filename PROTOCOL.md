# Deep cube BLE protocol

Reverse-engineered from official Android app `com.dnateam.deep_app` 1.4.0
(Flutter 3.8.1 AOT `libapp.so`), ESP32 firmware `deep_esp` **1.8.9.g7c90664**
(build `May 18 2026`, IDF `v4.4.8-dirty`), live HCI snoop, and writes to a
physical **deep.n**.

This is a working notebook, not a vendor spec. Anything not marked
**verified live** is inferred. The native client in `app/` only sends frames
that changed device state on this cube.

---

## 1. Device under test

| Field | Value |
|---|---|
| BLE name | `deep.n` |
| Address | `34:5F:45:36:E3:8E` |
| Firmware (DIS 0x2A26) | `1.8.9` |
| Model (DIS 0x2A24) | `deep.n` |
| Firmware image name | `deep_esp` |
| Image wrapper | TI CC13x2 OAD (`CC13x2R1`) around an ESP32 app (`0xE9` magic, 8 segments) |
| Advertised service | 16-bit `0x00FF` (also used by unrelated devices) |

Other advertised names in the official app / firmware strings:

- `deep.n`, `deep.r`, `deep.n.hotel`, `deep.r.hotel`, leftover `deep`

`deep.r` is the wider model (extra dream programs in the official UI). This
cube is `deep.n`. Hotel variants share the same control UUIDs as far as the
app is concerned.

Firmware tasks (rodata): `deviceTask`, `chargerTask`, `gaugeTask`,
`accelTask`, `vibroTask`, `oadTask`, `ledTask`, `sleepTask`.

NVS keys next to each other: `name-size`, `name-name`, `led-enable`,
`led-dim`, `vibro-enable`, `ulp`.

---

## 2. Sources

- Official APK pulled from the phone (`apk/`, not in git).
- `re/fw_esp32.bin` + `re/fw_irom.bin` (ESP32 image; IROM mapped at
  `0x400D0020`).
- `re/strings/all.txt` from `libapp.so`.
- Full HCI: `re/live/btsnoop_*.log`. **btsnooz is truncated** (~9 B of ACL)
  and hides the 8-byte command payload. Only `btsnoop_hci_*.log` shows
  complete ATT Write Request values.
- Live GATT from laptop (`tools/probe.py`, bleak) and from the native app.

Official gestures that produced the first complete frames:

- Start: set wake time, then **double-tap** the purple program switch
  (a single adb tap on the cube graphic missed the Flutter control).
- Stop: **hold the pause II control for 3 seconds**.
  Official copy: `Hold  I I  (pause) button for 3 seconds to stop program`
  and `Tap (play) button, to resume program`.

Captured official start (wake time → duration, power 1):

```
1a 01 7f 01 32 40 01 00
```

`0x00014032` little-endian = 81970 s ≈ 22 h 46 min (time until next morning).
The official app does **not** send “9 hours”; it sends seconds until the
chosen wake clock.

---

## 3. Advertising / scan

The cube’s advertisement is short-lived. If the ring is dark, touch the
cube. It can keep advertising while a phone already holds the ACL.

The scan record is **malformed / duplicated**. Android `ScanRecord` UUID
filters then fail with `unable to parse scan record`. Unfiltered scan +
name match works.

**Do not match on advertised `0x00FF`.** A laptop on this desk
(`5C:C3:36:F6:B3:8E` / `B3:19`) also advertises it; early writes went to
the wrong device. Match `deep.n` / `deep.r` / `deep.n.hotel` /
`deep.r.hotel` in the parsed name or by scanning raw adv bytes for those
ASCII strings.

Only one useful GATT client at a time. Official app and native app
fighting over the cube look like “buttons do nothing”. Fitbit on the same
phone also briefly appeared as an ACL holder on this MAC.

---

## 4. GATT map

Custom UUIDs (same 96-bit tail `e4e3-4ac4-8e14-b1b7ff024353` for control):

| Role | UUID | Properties | Max len | Handle on this cube |
|---|---|---|---|---|
| Control service | `98658f38-e4e3-4ac4-8e14-b1b7ff024353` | | | |
| Status | `98658f39-…` | read + notify | 12 | **0x0041** (CCC 0x0042) |
| Command | `98658f3a-…` | write, write-without-response, notify | 8 | **0x0044** (CCC 0x0045) |
| Data service | `9b5bfb12-20d7-4730-aa0c-993ea99fd4cd` | | | |
| Data | `9b5bfb13-…` | write + notify | 80 | **0x0048** (CCC 0x0049) |
| OAD leftover | `f000ffc0-0451-4000-b000-000000000000` | TI firmware update | | **do not touch** |

Standard services also present: GAP `0x1800`, GATT `0x1801`, DIS `0x180A`,
Battery `0x180F`.

DIS we actually read:

| UUID | Meaning | Live value |
|---|---|---|
| 0x2A24 | Model Number | `deep.n` |
| 0x2A26 | Firmware Revision | `1.8.9` |
| 0x2A25 / 0x2A27 / 0x2A29 | Serial / HW / Manufacturer | present, not required to run |

Enable notify (CCC 0x2902 = `01 00`) on status, command, data in that order.
The native client requests a large MTU (185); the phone got **244**. Writes
use Write Request (with response), not Write Command, so we wait for ATT
Write Response plus the 2-byte command notify.

Firmware GATT table (DROM around `0x2380`) agrees: status max 12
(`0x000C`), command max 8 (`0x0008`), data max 80 (`0x0050`).

---

## 5. Command characteristic (8 bytes)

Always pad to 8 bytes. Byte0 is the opcode. The firmware dispatcher at
~`0x400D8D3C` treats:

- `byte0 < 4` → device setting id `0..3`
- `byte0 == 0x1A` → start program
- `byte0 == 0x3C` → stop

Immediate loads `movi a10, 0x1A` / `movi a10, 0x3C` and compares against
setting ids `0,1,2,3` with bound `4` sit in that function.

### 5.1 Start — opcode `0x1A` (26)

```
1a [program] [curve] [power] [duration_s little-endian u32]
```

| Offset | Name | Live values |
|---|---|---|
| 0 | opcode | `0x1A` |
| 1 | program number | `0x01` Just a sleep, `0x0A` deep sleep on this fw |
| 2 | frequency curve | **always `0x7F`** in official and working native frames |
| 3 | power | `1` min, `2` mid, `3` max |
| 4–7 | duration seconds | LE uint32, **min 3600, max 86400** |

Verified live:

```
1a 01 7f 03 90 7e 00 00   # prog 1, max power, 32400 s = 9 h
1a 01 7f 03 10 0e 00 00   # prog 1, 3600 s = 60 min  (floor)
1a 0a 7f 03 90 7e 00 00   # prog 10 deep sleep, 9 h
1a 01 7f 01 32 40 01 00   # official app, prog 1, power 1, ~22.8 h
```

Status after a good start (program 10, 9 h):

```
aa 0a 7f 00 90 7e 00 00 00 00 00 00
```

then elapsed ticks in bytes 8–11.

**Duration floor is real.** 90 s (`5a 00 00 00`) for program 1 returned
command notify `00 00` but status stayed idle. Official strings:
`Min program duration is 60 minutes`, `Max program duration is 24 hours`.
90 s is below the floor; 3600 s runs. Duration 0 and several flag-only
`1a` variants returned **`01 00` (rejected)**, not stop.

**Curve byte `0x7F` is not Hertz.** Status keeps `7f` while the built-in
curve is active. Native UI therefore does not treat `0x7F` as a frequency.

**Status byte 3 is not the power we sent.** Start with power `3` still
yields status `aa … 7f 00 …`. Power lives on the start frame (and setting
id 3), not in the 12-byte status snapshot.

### 5.2 Stop — opcode `0x3C` (60)

```
3c 00 00 00 00 00 00 00
```

Verified live: running → idle. Official 3 s hold on pause sends this
same frame. There is no extra “which program” byte; stop is global.

### 5.3 Settings — ids 0..3

```
[id] [value] 00 00 00 00 00 00
```

| Id | NVS key | Value | Notes |
|---|---|---|---|
| 0 | `led-enable` | 0 / 1 | ring on/off |
| 1 | `led-dim` | 0..100 | percent |
| 2 | `vibro-enable` | 0 / 1 | |
| 3 | (not in that NVS string; power) | 1 / 2 / 3 | min / mid / max |

Live writes all returned command notify **`00 00`**. They do **not**
change the 12-byte status. Physical LED/vibro effect was not independently
re-confirmed after start/stop started working; GATT-level ACK is confirmed.
Firmware has `ledTask` / `vibroTask` and the dispatcher clearly branches
on these ids.

Official app models this as `DeviceSettingsEnum` +
`NewProgramsEvent.setValue(id:)` / `setPower(value:)`. Power in the
**start** frame (byte 3 of `0x1A`) is what the running program actually
uses; setting id 3 is the persisted default.

### 5.4 Command notify (2 bytes)

First byte is the result code (second byte observed as `00`):

| Notify | When |
|---|---|
| `00 00` | ACK: setting accepted, start accepted, stop accepted while running |
| `01 00` | NACK: bad start variant (duration 0, nonsense flags) |
| `03 00` | unknown **program number** (start `1a` with a slot this fw does not have) |
| `04 00` | stop while **already idle** |

Do not treat `00 00` alone as “program is running”. Check status: a
too-short duration can ACK and still stay idle.

Native client requires `status.running` and `status.programNumber == requested`.

---

## 6. Status characteristic (12 bytes)

Read + notify. Polling every few seconds is enough; start/stop also push
notifies immediately.

```
[state] [program] [freq] [b3] [total_s LE u32] [elapsed_s LE u32]
```

| Offset | Idle | Running (example, 9 h prog 1) |
|---|---|---|
| 0 state | `00` | `aa` |
| 1 program | `00` | `01` or `0a` |
| 2 freq/curve | `7f` | `7f` (curve on) |
| 3 | `00` | `00` (not start-frame power) |
| 4–7 total | `00 00 00 00` | e.g. `90 7e 00 00` = 32400 |
| 8–11 elapsed | `00 00 00 00` | ticks up about once a second |

`state != 0` means running. No third value (paused) was ever seen.
Elapsed notify examples: `00`, `03`, `07`, `20` seconds while we watched.

---

## 7. Data characteristic (≤80 bytes)

Used by the official app for extra-program download / `setPrograms`, not
for start/stop of the two built-in programs.

| Write | Notify | Meaning |
|---|---|---|
| `00 00` | `00 01` | GET unknown register |
| `00 01` | `00 00 01 1a` | GET reg 1 = **0x1A** |
| `00 02` | `00 00 02 1a` | GET reg 2 = 0x1A |
| `00 03` | `00 00 03 1a` | GET reg 3 = 0x1A |
| `00 04` … `00 0c` | `00 01` | GET unknown |
| `01 …` | `01 01` | SET error (no writable register found) |
| `02 00` / `03 00` | `02 01` / `03 01` | error |
| `04 00` | (none) | ignored |
| `10 xx` | `10 00` | ACK, **status unchanged** |

Registers 1–3 all returning `0x1A` is the same byte as the start opcode.
Treat it as a protocol tag / version, not as “start lives on the data
char”. Opcode `0x10` on data is a no-op ACK; early brute-force treated it
as start and was wrong.

`NewDeviceDataEvent.setPrograms(programs:)` / `sendProgramDataUpdate` /
`ExtraProgramData` in the Flutter app almost certainly stream extra
program blobs here (max 80 B per write). Format is **not decoded**.
Without that blob, start with program numbers 2–9 and 11–16 nacks `03 00`.

---

## 8. Program numbers

### 8.1 What this firmware actually runs

Live sweep, 60 min, curve `7f`, power 3, on deep.n 1.8.9:

| Program | Command notify | Status |
|---|---|---|
| 0 | `03 00` | idle |
| **1** | `00 00` | **`aa 01 …` runs** |
| 2–9 | `03 00` | idle |
| **10 (`0x0A`)** | `00 00` | **`aa 0a …` runs** |
| 11–16 | `03 00` | idle |
| 26, 127, 170 | `03 00` | idle |

So this image has **two** built-in slots: **1** and **10**. Not 1–4.

Native UI mapping on this cube:

| Wire | Official English | Official RU (UTF-16 in `libapp.so`) | Native title |
|---|---|---|---|
| 1 | Just a sleep | Просто сон | Просто сон |
| 10 | Sleeping Bear (see below) | Сон медведя | Глубокий сон |

Program 10 is the only other slot the firmware accepted. Official copy
for Sleeping Bear: *“Maximum duration of deep sleep. Heavy sleep with
drowsy awakening.”* Just a sleep: *“The combination of falling asleep,
deep sleep and smooth awakening the remaining third of sleep.”*

That identification (10 = Sleeping Bear / deep sleep) is **by elimination
plus copy**, not by an official “id=10” integer sitting next to the
string. It is the working deep-sleep mode on this cube. Confirmed in the
native app: start `1a 0a 7f 03 90 7e 00 00` → UI “идёт программа /
Глубокий сон”, stop `3c` → idle.

### 8.2 What the official app *talks about*

Flutter `init:` keys (not wire numbers):

`justADream`, `bearDream`, `buddhaDream`, `cascadingAwakening`,
`daytimeSleep`, `dreamcatcher`, `sleepingPill`, `sleepWaves`, `owlDream`,
`dreamStairs`, `dreamWhale`, `dreamWorld`, `dreamsControl`,
`basicPrograms`, `extraPrograms`.

Display names in `libapp.so`: Just a sleep, Sleeping Bear, Dream Kit,
Dreamcatcher, Sleeping Pill, Sleep Waves, The Buddha's Sleep, plus RU
Просто сон / Сон медведя / Дрим кит / Ловец снов / Сонная пилюля /
Дневной сон.

Assets have icons `p1.svg` … `p14.svg`. One hard pairing in AOT:
`p12.init:sleepWaves`. Icon index is **not** the same as the BLE program
byte (p12 ≠ wire 12; wire 12 nacks).

Dream Kit / Dream Catcher were originally coded as wire 3 / 4 and
`rOnly` for `deep.r`. On this `deep.n` those numbers nack `03 00`. They
are extra programs that need a cloud download onto the cube, or they
exist only on `deep.r` firmware. Not verified.

Other English blurbs (which named program is which is not fully wired):

- Nap: *“It will help you fall asleep, but will not let you enter into
  the deep sleep phaze. Suitable for naps.”* (`daytimeSleep` / Дневной сон)
- Counting sheep / deep sleep helper
- Lucid / OBE / vivid-dream variants for the dream programs

Official extra programs are fetched from NetCat
`https://forsleep.org/app/extraprogram/?isNaked=1` and similar
(`/app/cube-soft/`, `/app/app-soft/`). Naked calls return
`invalid app token`. A prefs token
`d60c53fcdf80a7cb6560d8ed0bc0f876` exists but is not a sufficient
app token. Extra-program **payload format on the data characteristic
is unknown**.

The cube also has a notion of **sides**
(`MapOfCubeSideProgramsExtension|getExtraProgramsIds`,
`NewDeviceDataEvent.setPrograms`). Assigning a program to a face is
app-side / data-channel; start still uses the `0x1A` program byte.

---

## 9. Pause / resume

Official UI: tap pause, tap play to resume, hold pause 3 s to stop.

Hold 3 s is **stop `0x3C`** (HCI + live).

While program 1 was running, these 8-byte command frames all returned
`00 00` and **did not** change status (still `aa`, elapsed kept ticking):

`05`, `06`, `14`, `28`, `2d` (and a larger idle-hunt set that was
invalid because duration was 90 s).

No status `state` other than `00` / `aa` appeared. If pause exists on
the wire, it is not a one-byte opcode in that list, or it is implemented
only in the app (cube keeps running). Treat pause as **unsolved**; stop
is `0x3C`.

---

## 10. Firmware notes

ESP32 image header: magic `e9`, 8 segments, entry `0x40081134`.
IROM segment `addr=0x400d0020 size=0x58a3c` = `re/fw_irom.bin`.

Command-write function pointers in the GATT table (little-endian
`0x400d8cf8`, `0x400d8d0c`, `0x400d8cac`, `0x400d8cd4`). Dispatcher
prologue `ENTRY` at `0x400D8D3C`.

That is enough to confirm opcodes `0x1A` / `0x3C` and setting ids 0–3
in silicon, not only on the air.

`ulp` NVS key after `vibro-enable` is unused by us. OAD path is leftover
from the CC13x2 wrapper; the sleep logic is ESP32.

---

## 11. Dead ends (do not repeat)

- Brute-forcing start as `04 [prog] [duration]`, `aa [prog]`, `0x10`,
  `0x20`, or data-char `02`/`03`/`10`. Those never flipped status to
  `aa`. The working opcode is **`0x1A`**.
- Treating data GET value `0x1A` as coincidence only — it is the same
  constant as start, but GET does not start anything.
- Matching scan on `0x00FF`.
- Using Android `btsnooz` logs: payload truncated, 8-byte writes look
  empty. Need persist `btsnoop_hci_*.log`.
- Frida 17: no Java. Frida 16 as `su 0` attached pid 0 and broke
  `system_server` (`Can't find service: activity/window`). Do not.
- Guessing program numbers 2/3/4 for Bear/Kit/Catcher on this firmware.
  They nack. Slot **10** is the second built-in.
- Assuming start ACK means running. Check 12-byte status.
- Assuming status byte 3 is power.
- Assuming `0x7F` in status is a frequency in Hz.
- Driving the cube from official app and native app at once.
- Naive `DeviceSettingsEnum` as program control (opcodes 4–7). Settings
  0–3 are real; they are not start/stop.
- Short durations (< 60 min).

HCI on handle **0x0044** from the official session plus probes, unique
command payloads:

```
1a 01 7f 01 32 40 01 00   # official start (×6)
1a 01 7f 01 00 00 00 00   # duration-0 probes (rejected or no-run)
1a 00 7f 01 00 00 00 00
1a 00 00 00 00 00 00 00
1a 01 7f 00 00 00 00 00
1a 01 7f 02 00 00 00 00
1a 01 00 00 00 00 00 00
1b 01 7f 01 00 00 00 00
19 01 7f 01 00 00 00 00
1a 00 7f 00 00 00 00 00
3c 00 00 00 00 00 00 00   # official + native stop
```

Handle 0x0048 (data) in those logs: only `00 01`, `00 02`, `00 03` (GET).
Official start/stop path does not use the data char.

---

## 12. Client behaviour that matches the cube

- Scan unfiltered, match name / raw ASCII `deep.n` etc.
- One serialized GATT queue (mutex). Do not overlap write/read/poll.
- CCC notify on status, command, data.
- Start = one `0x1A` frame; stop = one `0x3C` frame. No candidate lists.
- Success = status **changed** to the requested running/idle state, and
  for start the program byte matches.
- Default duration 9 h, allowed 1–12 h in UI (wire min 1 h, max 24 h).
- Power 1–3 goes in the start frame; settings writes are extra.

---

## 13. Open questions

1. Exact official program-id table (is wire 10 definitely Sleeping Bear,
   or another extra that happened to be baked into 1.8.9?).
2. Extra-program blob on the data characteristic (`setPrograms`,
   `ExtraProgramData`, 80-byte chunks).
3. Pause/resume opcode, if any. No paused status byte observed.
4. Whether setting id 3 (power) changes a running program or only the
   next start. Start-frame power is what we rely on.
5. Physical confirmation of LED/dim/vibro vs GATT ACK.
6. `deep.r` firmware: likely more built-in slots (Dream Kit / Catcher).
   Not tested.
7. `ulp` NVS key.
8. Whether curve byte other than `0x7F` selects another table. Not
   needed for the two working programs.
9. Cloud app-token for `forsleep.org` (gated; not required for built-in
   1 and 10).

---

## 14. Quick cheat sheet

```
# Just a sleep, 9 h, max power
1a 01 7f 03 90 7e 00 00

# Deep sleep / Sleeping Bear slot, 9 h, max power
1a 0a 7f 03 90 7e 00 00

# Stop
3c 00 00 00 00 00 00 00

# LED on, dim 100, vibro on, power max
00 01 00 00 00 00 00 00
01 64 00 00 00 00 00 00
02 01 00 00 00 00 00 00
03 03 00 00 00 00 00 00

# GET protocol tag
00 01   →  00 00 01 1a
```
