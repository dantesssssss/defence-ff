# RescueBeacon — Requirements
 
Android app that turns a trapped person's phone into a rescue beacon and gives firefighters a "finder" that shows how close the victim is and on which floor.
 
## 1. Concept
 
Two modes in **one app** (role chosen at start):
 
| Mode | Who | What the phone does |
|---|---|---|
| **Victim (Beacon)** | Person trapped in a fire / under rubble | Broadcasts an emergency signal and sensor data over BLE, uploads data to the backend while it has a connection, makes sound and light |
| **Rescuer (Finder)** | Firefighter | Scans for beacons, shows a list of victims with estimated distance, floor difference and a warmer/colder indicator; syncs with the backend |
 
**Core principle: offline-first.** In a fire, mobile network, building Wi-Fi and power are likely to be down. Phone-to-phone search over BLE must work with no backend at all. The backend adds value (last known location, incident overview, multi-rescuer data) but is never required to find someone.
 
## 2. Feasibility of each signal
 
| Signal | Feasible? | What it really gives | Limits |
|---|---|---|---|
| **BLE advertising + RSSI** | Yes — core of the MVP | Presence within ~10–30 m indoors, rough distance band, warmer/colder trend | RSSI is noisy; walls, rubble and bodies change it by 10–20 dB. Gives distance bands, not metres. No direction. |
| **Barometer** | Yes | Floor difference between rescuer and victim (~12 Pa per metre, ~0.35 hPa per floor) | Not every phone has one. Different phones can disagree by ~1 hPa (= several floors) unless calibrated. Fire and ventilation change local pressure. |
| **GPS** | Partly | Last good fix before entering the building → which building / entrance | Useless indoors and under rubble. Send the last fix with its age and accuracy. |
| **Wi-Fi scan fingerprint** хз мб кал | Partly | List of visible access points and their strengths; rescuer compares with their own scan ("you see the same APs") | Android throttles scans (4 per 2 min in foreground). APs may lose power in a fire. Stretch goal. |
| **"Share Wi-Fi" / hotspot** мб кал | Mostly no | — | Apps cannot turn on normal tethering. `LocalOnlyHotspot` is possible but adds little over BLE and drains battery. Skip. |
| **Wi-Fi Aware / RTT ranging** мб кал | Device-dependent | 1–2 m ranging between two phones | Supported on a minority of devices. Stretch goal. |
| **UWB** | Device-dependent | ~10–30 cm distance plus direction | Only flagship phones (Pixel Pro, Galaxy S Plus/Ultra). Best possible result if both demo phones support it. Stretch goal. |
| **Sound + flashlight** | Yes, trivial | Siren and strobe so the rescuer can hear/see the phone in the last few metres | Real firefighter PASS devices work this way. High value for low effort. |
| **Accelerometer** | Yes | "Person is moving / not moving" status | — |
| **Automatic fire detection** | No | — | Phones have no smoke or usable temperature sensor. See triggers below. |
мб + батарэя, тэмпература, рух (і як атрымаць direction)
 
### How emergency mode starts (instead of "phone detects fire")
 
1. **Manual SOS** (мне падаецца не нашая ідэя) — big button, plus hold-to-activate to avoid false alarms. (MVP)
2. **Remote activation** — dispatcher/rescuer creates an incident on the backend; phones with the app inside the incident area get a push and switch to beacon mode after a 15 s "I'm safe" countdown. (MVP if time, otherwise stretch)
3. **No-motion detection** — while an incident is active, no movement for N seconds marks the victim as "possibly unconscious". (Stretch)
4. **Smoke alarm sound recognition** (які нах смоўк дэтэкшн) via microphone. (Stretch, risky)
## 3. Problems with the original idea and how we address them
похуй
| Problem | Fix |
|---|---|
| Victim must have the app installed *before* the fire | Pitch it for defined groups: employees of a factory / office / dorm, or **firefighters themselves** ("firefighter down" locator). Long term: integrate into an existing alert app or the OS. |
| Phone cannot detect a fire | Manual SOS + remote activation by incident (above). |
| Backend unreachable during the fire | Offline-first BLE; all essential data fits in the BLE packet. |
| "Exact distance" is not achievable with BLE | Show distance bands (< 2 m, 2–5 m, 5–10 m, > 10 m) and a signal-trend indicator. Offer UWB as precision mode where available. |
| No direction from one RSSI reading | Rescuer walks and follows the trend (warmer/colder). With several rescuers, the backend shows who is closest. |
| Phone overheats and shuts down (~45–50 °C battery) | Upload data early and often; backend keeps the last known state. |
| Battery | BLE advertising is cheap. Siren/strobe in bursts. Show the victim's battery level to the rescuer. |
| Privacy | Nothing is broadcast outside emergency mode. Random per-incident ID in the BLE packet, no personal data. |
 
## 4. Architecture
 напэўна норм
```
┌──────────────┐   BLE advertising (always)   ┌──────────────┐
│ Victim phone │ ───────────────────────────▶ │ Rescuer phone│
│  (Beacon)    │ ◀─── GATT connect (details) ─│  (Finder)    │
└──────┬───────┘                              └──────┬───────┘
       │ HTTPS (while connected)                     │ HTTPS + WebSocket
       ▼                                             ▼
      ┌───────────────────────────────────────────────┐
      │ Backend (FastAPI): incidents, victims,        │
      │ telemetry, rescuer sightings, live updates    │
      └───────────────────────────────────────────────┘
```
 
### Repository layout (monorepo)
 напэўна норм, чэкнуць
```
rescue-beacon/
├── android/          # one Kotlin app, two modes
├── backend/          # FastAPI service
├── docs/             # this file, API contract, pitch
└── docker-compose.yml
```
 
## 5. Tech stack
 
**Android (Kotlin)**
- (хв)Jetpack Compose, single activity, Navigation Compose
- (ок)minSdk 26, targetSdk 35
- (ок)BLE: platform `BluetoothLeAdvertiser`, `BluetoothLeScanner`, GATT server/client
- (хв)Foreground service (`connectedDevice` + `location` types) so the beacon survives screen-off
- (ок)Sensors: `SensorManager` (`TYPE_PRESSURE`, `TYPE_ACCELEROMETER`), `FusedLocationProviderClient`
- (хв)Networking: Retrofit or Ktor client, OkHttp WebSocket, kotlinx.serialization
- (нібы кал)Coroutines + Flow, ViewModel
- (хв, бліжэй кал)Optional: `androidx.core.uwb`, Wi-Fi Aware, Firebase Cloud Messaging, osmdroid / Google Maps
**Backend (Python)**
- (без піданцік)FastAPI + Uvicorn, Pydantic v2
- (ок)WebSockets for live updates to rescuers
- (кал)SQLite via SQLModel (Postgres later)
- (кал)Docker; expose with ngrok or deploy to Render / Fly.io for the demo
**Permissions**
`BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (optional), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES` (stretch), `INTERNET`
 
## 6. BLE protocol
 хв абсалютна
- **Advertisement packet:** flags + one 128-bit service UUID (rescuers filter scans on it).
- **Scan response:** manufacturer-specific data (company ID `0xFFFF`), 18-byte payload:
| Bytes | Field | Notes |
|---|---|---|
| 0–3 | Beacon ID | Random per emergency session |
| 4 | Status flags | bit0 moving, bit1 has barometer, bit2 has GPS fix, bit3 user confirmed SOS, bit4 backend reachable |
| 5 | Battery % | 0–100 |
| 6–7 | Pressure | `Pa − 80000`, uint16 (1 Pa ≈ 8 cm) |
| 8–11 | Last latitude | int32, degrees × 1e7 |
| 12–15 | Last longitude | int32, degrees × 1e7 |
| 16 | Fix age | minutes, capped at 255 |
| 17 | Sequence | increments on each payload update |
 
- Advertising: low-latency mode, high TX power, payload refreshed every 2–5 s.
- **GATT service (stretch):** read characteristic with extended JSON (name, medical note, Wi-Fi fingerprint); write characteristic so the rescuer can trigger the siren or send "help is coming".
## 7. Distance and floor estimation
 можна але і пьюр даныя трэба захаваць
**Distance (rescuer phone, local)**
1. Collect RSSI per beacon, median filter over the last ~2 s, then exponential smoothing.
2. Path-loss model: `d = 10 ^ ((P1m − RSSI) / (10 · n))`, with `P1m ≈ −59 dBm` (calibrate on the demo phones) and `n ≈ 2.5–3.5` indoors.
3. Display as a band plus trend arrow (getting closer / further) and a Geiger-style beep that speeds up.
**Floor (rescuer phone, local)**
- `Δh ≈ (P_rescuer − P_victim) × 0.083 m/Pa`; `floors ≈ round(Δh / 3 m)`.
- Display: "≈ 2 floors above you" / "same floor".
- Calibration: store a per-device offset; for the demo, calibrate the phones side by side. Production idea: self-calibrate against GPS altitude outdoors or a per-model bias table.
**Backend fusion**
- Rescuers upload sightings (beacon ID, RSSI, own pressure, own GPS if any, timestamp).
- Backend keeps per victim: last GPS fix, last telemetry, last seen time, strongest-signal rescuer and its distance estimate, floor estimate.
- With 3+ rescuers with known positions: weighted-centroid position estimate (stretch).
## 8. Functional requirements
 
### Victim mode
- (падаецца кал)**V1** (MVP) Hold-to-activate SOS button starts emergency mode.
- (ок)**V2** (MVP) Foreground service advertises the BLE beacon with the payload from section 6.
- (ок)**V3** (MVP) Read barometer, battery, motion state, last known GPS fix; refresh the payload.
- (хв)**V4** (MVP) POST telemetry to the backend every 5 s while a connection exists; queue and retry when offline.
- (ок)**V5** (MVP) Siren + flashlight strobe, on by default in bursts, with a mute toggle.
- (норм але не мвп)**V6** (MVP) Simple high-contrast screen: status, "rescuers nearby: N", cancel with confirmation.
- (пох)**V7** (Stretch) Remote activation by incident push with "I'm safe" countdown.
- (пох)**V8** (Stretch) Optional profile: name, number of people with me, medical note.
- (кал)**V9** (Stretch) Wi-Fi fingerprint upload; UWB / Wi-Fi Aware ranging responder.
### Rescuer mode
- (ок)**R1** (MVP) Scan for beacons continuously, filtered by the service UUID.
- (ок)**R2** (MVP) Victim list sorted by estimated distance: band, floor difference, battery, moving / not moving, last seen.
- (аўдыя важна, не будзе рэск'юер глядзець тэлефон)**R3** (MVP) Finder screen for one victim: large distance band, trend indicator, floor difference, audio feedback.
- (пакуль пох)**R4** (MVP) Upload sightings to the backend; receive live victim updates over WebSocket.
- (ок)**R5** (MVP) Mark victim as "found / rescued"; status propagates to all rescuers.
- (хв)**R6** (Stretch) Map with the victims' last GPS fixes and the incident area.
- (мм че мб)**R7** (Stretch) Create an incident (location + radius) that remotely activates victim phones.
- (ок але і так павінна быць у вікціма)**R8** (Stretch) Trigger the victim's siren remotely over GATT.
- (цяжка але хайпова)**R9** (Stretch) UWB precision finding with direction arrow.
### Backend (вы казалі не адразу)
- **B1** (MVP) `POST /incidents`, `GET /incidents/{id}`
- **B2** (MVP) `POST /victims/{beacon_id}/telemetry` — pressure, battery, motion, GPS, timestamp
- **B3** (MVP) `POST /sightings` — rescuer ID, beacon ID, RSSI, rescuer pressure, rescuer GPS, timestamp
- **B4** (MVP) `GET /incidents/{id}/victims` — fused state per victim
- **B5** (MVP) `PATCH /victims/{beacon_id}` — status: active / located / rescued
- **B6** (MVP) `WS /incidents/{id}/stream` — push victim updates to rescuers
- **B7** (Stretch) Web dashboard for the incident commander
- **B8** (Stretch) FCM push for remote activation; rescuer authentication
## 9. Non-functional requirements
 
- (галоўнае)Works with no internet and no Wi-Fi (BLE path).
- (выратавальнік)Beacon keeps running with the screen off and the app in the background.
- Beacon discovered within 5 s of the rescuer coming into range.
- Victim UI usable in panic: one action to start, large targets, dark high-contrast theme.
- Beacon mode should last at least 4 hours on 30 % battery (BLE only, siren in bursts).
- No personal data in BLE packets; telemetry deleted when the incident is closed.
## 10. Hackathon plan
 
**Team split**
- Android A: victim mode (foreground service, advertiser, sensors, siren)
- Android B: rescuer mode (scanner, RSSI filtering, finder UI)
- (я мб магу хв)Backend: FastAPI, data model, WebSocket, fusion logic
- Fourth person / shared: API contract, calibration, demo script, pitch
**Order of work** усё па-новай самі
1. Agree on the BLE payload and API contract (sections 6 and 8).
2. BLE advertise → scan → RSSI on screen between two phones. This is the main technical risk; do it first.
3. Distance bands + barometer floor difference.
4. Backend telemetry and sightings, live victim list.
5. Siren, strobe, polish, calibration on the demo phones.
6. Stretch goals only after the demo path works end to end.
**Demo script** усё самі
1. Hide the victim phone in another room or on another floor, activate SOS.
2. Turn off Wi-Fi and mobile data on both phones to show offline search.
3. Rescuer walks in: beacon appears, floor difference shown, distance band shrinks, beeps speed up, siren heard.
4. Turn data back on: show the backend view with last GPS fix, battery and "rescued" status.
## 11. Risks
 похуй
| Risk | Mitigation |
|---|---|
| A demo phone does not support BLE peripheral (advertising) mode | Check `isMultipleAdvertisementSupported()` on all phones on day one |
| A demo phone has no barometer | Check `TYPE_PRESSURE`; pick victim/rescuer phones accordingly |
| RSSI too jumpy on stage | Median + smoothing, show bands not metres, calibrate in the demo room |
| Vendor battery savers kill the service (Xiaomi, Huawei, Samsung) | Foreground service, disable battery optimisation for the app on demo phones |
| Venue Wi-Fi blocks the backend | Phone hotspot + ngrok, or a deployed instance |
 
## 12. Future improvements похуй
 
- UWB and Wi-Fi RTT for metre-level or better ranging
- Mesh relay: victim phones relay each other's beacons to extend range
- Dedicated rescuer hardware (directional antenna, LoRa relay at the building entrance)
- Building floor plans and indoor positioning for rescuers
- Integration with emergency dispatch systems and existing public alert apps
- Wearable support (smartwatch heart rate as a vital sign)
- Firefighter-down mode: automatic alert when a rescuer stops moving
