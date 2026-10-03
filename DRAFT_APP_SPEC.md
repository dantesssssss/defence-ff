# RescueBeacon — Draft App Spec (for Claude Code)
 
Build a draft Android app with two modes, **Victim** and **Rescuer**, that exchange data directly over Bluetooth Low Energy. No backend in this draft. Everything must work with no internet and no Wi-Fi network.
 
Goal of the draft: prove that two phones can discover each other, that the victim can share as much useful information as possible, and that everything is logged so it can be analysed and sent to a backend later.
 
## 1. Project setup
 
- Language: Kotlin. UI: Jetpack Compose (Material 3). Build: Gradle Kotlin DSL, version catalog.
- Single module `app`. Package: `com.rescuebeacon.app`.
- `minSdk 26`, `targetSdk 35`, `compileSdk 35`.
- Libraries: Compose BOM, Navigation Compose, Lifecycle ViewModel, Coroutines + Flow, kotlinx.serialization (JSON), Play Services Location. No DI framework (manual wiring in an `AppContainer`), no database.
- Platform BLE APIs only (`android.bluetooth.le`, `BluetoothGattServer`, `BluetoothGatt`). No third-party BLE library.
- Dark, high-contrast theme. Large touch targets. English UI strings in `strings.xml`.
### Package structure
 
```
com.rescuebeacon.app
├── MainActivity.kt
├── AppContainer.kt
├── ui/
│   ├── RoleSelectScreen.kt
│   ├── victim/   (VictimScreen, VictimViewModel, ProfileScreen)
│   └── rescuer/  (VictimListScreen, FinderScreen, RescuerViewModel)
├── ble/
│   ├── BleConstants.kt        (UUIDs, company ID)
│   ├── BeaconPayload.kt       (encode/decode 18-byte payload)
│   ├── VictimAdvertiser.kt
│   ├── VictimGattServer.kt
│   ├── RescuerScanner.kt
│   ├── RescuerGattClient.kt   (with serialized operation queue)
├── sensors/
│   ├── PressureSource.kt, MotionSource.kt, LightProximitySource.kt
│   ├── BatterySource.kt, LocationSource.kt
│   ├── WifiScanSource.kt, CellSignalSource.kt, SoundLevelSource.kt
├── model/        (VictimSnapshot and sub-models, @Serializable)
├── service/      (BeaconForegroundService)
├── estimation/   (RssiFilter, DistanceEstimator, FloorEstimator)
├── log/          (SessionLogger — JSON Lines files)
└── permissions/  (PermissionHelper)
```
 
## 2. Permissions and manifest
 
Declare and request at runtime (grouped, with a rationale screen, before entering a mode):
 
- API 31+: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- API ≤ 30: `BLUETOOTH`, `BLUETOOTH_ADMIN` (with `maxSdkVersion="30"`)
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- API 33+: `NEARBY_WIFI_DEVICES`, `POST_NOTIFICATIONS`
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `RECORD_AUDIO` (optional; app must work if denied)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`
- `WAKE_LOCK`, `FLASHLIGHT`/camera for torch, `VIBRATE`
- `<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>`
If Bluetooth is off, prompt to enable it. If `bluetoothAdapter.isMultipleAdvertisementSupported` is false, show a clear error in Victim mode. Every optional sensor or permission that is missing must degrade gracefully (field = `null`), never crash.
 
## 3. BLE protocol
 
### UUIDs (`BleConstants.kt`)
 
| Item | UUID |
|---|---|
| Service | `7e5c0001-9a1b-4c3d-8e2f-1a2b3c4d5e6f` |
| STATUS characteristic (read) | `7e5c0002-9a1b-4c3d-8e2f-1a2b3c4d5e6f` |
| LOCATION characteristic (read) | `7e5c0003-9a1b-4c3d-8e2f-1a2b3c4d5e6f` |
| ENVIRONMENT characteristic (read) | `7e5c0004-9a1b-4c3d-8e2f-1a2b3c4d5e6f` |
| PROFILE characteristic (read) | `7e5c0005-9a1b-4c3d-8e2f-1a2b3c4d5e6f` |
| COMMAND characteristic (write) | `7e5c0006-9a1b-4c3d-8e2f-1a2b3c4d5e6f` |
 
Manufacturer company ID: `0xFFFF`.
 
### Advertising (victim)
 
- Legacy, **connectable**, `ADVERTISE_MODE_LOW_LATENCY`, `ADVERTISE_TX_POWER_HIGH`, no timeout, device name not included.
- Advertise data: the service UUID.
- Scan response: manufacturer data, 18 bytes, big-endian:
| Bytes | Field | Encoding |
|---|---|---|
| 0–3 | Session ID | random uint32 generated when SOS starts |
| 4 | Flags | bit0 moving, bit1 has barometer, bit2 has GPS fix, bit3 user-confirmed SOS, bit4 has internet |
| 5 | Battery % | uint8 |
| 6–7 | Pressure | uint16 = Pa − 80000; `0xFFFF` if no barometer |
| 8–11 | Latitude | int32 = degrees × 1e7; `0x7FFFFFFF` if none |
| 12–15 | Longitude | int32 = degrees × 1e7; `0x7FFFFFFF` if none |
| 16 | Fix age | minutes, capped at 255 |
| 17 | Sequence | uint8, increments on every payload change |
 
- Refresh the payload every 3 s by restarting advertising with new data.
- `BeaconPayload` must have `encode()` / `decode()` with JVM unit tests (round trip, missing values, boundary values).
### GATT (full data)
 
- Victim runs a `BluetoothGattServer` with the service above. All read characteristics return UTF-8 JSON. **Each value must be ≤ 512 bytes**; trim lists to fit. Handle `offset` in `onCharacteristicReadRequest` for long reads.
- Rescuer connects, requests MTU 517, discovers services, then polls: STATUS every 2 s; LOCATION, ENVIRONMENT, PROFILE every 10 s. Call `readRemoteRssi()` every 1 s while connected.
- **All GATT client operations must go through a serialized queue** (one operation at a time, next one starts in the previous callback, with a 5 s timeout). Auto-reconnect on disconnect with backoff.
- Victim must support several rescuers connected at once and keep advertising while connected.
### COMMAND (rescuer → victim), JSON
 
- `{"cmd":"ack"}` → victim screen shows "A rescuer has detected your signal" and vibrates.
- `{"cmd":"siren_on"}` / `{"cmd":"siren_off"}`
- `{"cmd":"message","text":"..."}` → shown on the victim screen (max 100 chars).
## 4. Data the victim shares
 
Collect everything available; fields are nullable. Build one `VictimSnapshot` every 2 s.
 
**STATUS**
```json
{"sid":123456,"ts":1790000000000,"sosElapsedS":95,
 "battery":{"pct":64,"charging":false,"tempC":31.5},
 "pressurePa":100845,
 "motion":{"moving":false,"lastMoveAgoS":40,"orientation":"FLAT_FACE_UP","steps":12},
 "lightLux":3.0,"proximityNear":false,"ambientTempC":null,
 "userState":"TRAPPED","sirenOn":true,"hasInternet":false}
```
- `battery.tempC` from `BatteryManager.EXTRA_TEMPERATURE` (a rough heat indicator).
- `motion.moving` = accelerometer magnitude variance above a threshold in the last 3 s. `orientation` from gravity: `FLAT_FACE_UP`, `FLAT_FACE_DOWN`, `UPRIGHT`, `OTHER`. `steps` since SOS start (`TYPE_STEP_COUNTER`, null if unavailable).
- `userState` set by quick buttons: `OK`, `INJURED`, `TRAPPED`, `CANT_BREATHE`, or `UNKNOWN`.
**LOCATION**
```json
{"lat":52.2297,"lon":21.0122,"altM":118.0,"accM":12.0,"provider":"fused",
 "fixTs":1789999990000,"fixAgeS":10,"speedMps":0.0}
```
- Request high-accuracy updates every 5 s; always keep the best last fix (prefer newer; never drop an old good fix for nothing).
**ENVIRONMENT**
```json
{"wifi":{"connected":{"ssid":"Office","bssid":"aa:bb:cc:dd:ee:ff","rssi":-61},
         "scan":[{"b":"aa:bb:cc:dd:ee:ff","r":-61,"f":5180}]},
 "cell":{"type":"LTE","level":2,"dbm":-105},
 "bleNeighbors":14,"soundDb":62.5}
```
- Wi-Fi scan at most every 30 s (respect throttling); top 5 by RSSI.
- `bleNeighbors`: distinct BLE devices seen in a 10 s scan every 60 s.
- `soundDb`: microphone amplitude level, sampled 1 s every 5 s; only if `RECORD_AUDIO` is granted. Do not record or store audio.
**PROFILE**
```json
{"name":"Anna K.","people":2,"note":"Asthma","phone":"+48...",
 "device":{"model":"Pixel 7","sdk":35},"appVersion":"0.1.0",
 "caps":{"barometer":true,"wifiAware":true,"wifiRtt":true,"uwb":false}}
```
- Name, people count, note and phone are optional, entered on a profile screen and saved in `SharedPreferences`.
## 5. Victim mode
 
- **Screen:** large "HOLD FOR SOS" button (hold 2 s). When active: status ("Beacon active"), elapsed time, connected rescuers count, last rescuer message, user-state quick buttons, siren toggle, "Stop" with confirmation dialog.
- **`BeaconForegroundService`** (types `connectedDevice|location`) owns the advertiser, GATT server and all sensor sources; persistent notification; partial wake lock. Must keep working with the screen locked.
- **Siren + strobe:** alarm tone at max alarm-stream volume and torch blinking, in bursts (5 s on, 10 s off). On by default, toggleable locally and via COMMAND.
- Generate a new session ID on each SOS start.
## 6. Rescuer mode
 
- **Victim list screen:** continuous scan filtered by service UUID (`SCAN_MODE_LOW_LATENCY`, report all matches, no dedup). One row per session ID: distance band, raw and smoothed RSSI, floor difference, battery %, moving flag, last seen (s ago). Sorted by smoothed RSSI. Rows not seen for 30 s are greyed out.
- **Finder screen (tap a row):** connects over GATT and shows
  - large distance estimate (band and metres), trend arrow (closer / further / steady), signal bar;
  - floor difference ("≈ 1 floor above you"), with a "Calibrate here" button that stores the current pressure offset between the two phones;
  - all STATUS / LOCATION / ENVIRONMENT / PROFILE fields in readable cards, with the age of each;
  - buttons: "Acknowledge", "Siren on/off", "Send message";
  - optional audio feedback: beep rate increases as the signal gets stronger.
- Use scan RSSI when not connected and `readRemoteRssi()` when connected.
### Estimation
 
- `RssiFilter`: median of the last 5 samples, then exponential smoothing (α = 0.3).
- `DistanceEstimator`: `d = 10 ^ ((P1m − rssi) / (10 · n))`, defaults `P1m = −59`, `n = 3.0`, both editable in a debug settings dialog. Bands: `< 2 m`, `2–5 m`, `5–10 m`, `> 10 m`.
- Trend: compare the smoothed RSSI now with 3 s ago (±3 dB dead zone).
- `FloorEstimator`: `Δh = (P_rescuer − P_victim + offset) × 0.083 m/Pa`; `floors = round(Δh / 3.0)`. Hide if either phone has no barometer.
- Unit tests for all three.
## 7. Logging (for later use)
 
- `SessionLogger` writes JSON Lines to `files/logs/<role>-<sessionOrStartTs>.jsonl`.
- Victim logs every snapshot (all four JSON blocks) every 2 s.
- Rescuer logs every sighting: timestamp, session ID, raw RSSI, smoothed RSSI, estimated distance, own pressure, own location, own compass heading, decoded advertisement payload; plus every GATT read result.
- "Export logs" button on both sides shares the files through `FileProvider` (share sheet).
- Keep the logger behind an interface (`SnapshotSink`) so a backend uploader can be added later without changing the modes.
## 8. Out of scope for this draft
 
Backend and networking, accounts, maps, UWB, Wi-Fi Aware ranging, direction arrow (body-shadow sweep), remote activation, encryption of BLE data.
 
## 9. Milestones (build and verify in this order)
 
1. **M1 — Skeleton:** project builds; role select screen; permission flow; Bluetooth-enabled check.
2. **M2 — Discovery:** victim advertises the service UUID; rescuer list shows the device with live raw RSSI.
3. **M3 — Payload:** 18-byte payload with battery and pressure; decoded in the rescuer list; unit tests pass.
4. **M4 — Foreground service:** beacon keeps running with the screen locked for 10+ minutes.
5. **M5 — Sensors + GATT:** all four JSON characteristics served; Finder screen shows them, updating live.
6. **M6 — Estimation:** distance bands, trend, floor difference with calibration.
7. **M7 — Commands + siren:** ack, siren toggle, message.
8. **M8 — Logging + export.**
After each milestone: `./gradlew assembleDebug` and `./gradlew test` must pass; give a short manual test checklist for two phones.
 
## 10. Acceptance criteria
 
- Works with Wi-Fi and mobile data off on both phones.
- Rescuer sees the victim within 5 s of SOS start at 5 m distance.
- Raw RSSI visibly changes when walking closer and further.
- Advertised battery and pressure match the victim's own readings.
- Finder screen shows all available victim data; missing sensors appear as "not available".
- Beacon survives screen lock and the app being in the background.
- Denying any optional permission (microphone, notifications) does not break either mode.
- Exported log files contain valid JSON on each line.
