# Emergency mode, Medical ID and Level tile — design

Date: 2026-10-03 · Branch: `feat/demo-ui` · Status: approved in chat, awaiting spec review

## Goal

Turn the symmetric two-phone finder into a rescue demo with two roles:

- **Victim** taps an Emergency widget on the home screen. Their phone starts broadcasting an SOS
  in the background (it keeps working with the phone locked) and shows that it is being searched.
- **Rescuer** opens the app and finds the victim with the existing circle screen, which now also
  shows who they are looking for (a Medical ID card) and how far above/below the victim is.

Demo-only scope: hardcoded victim profile, no settings, no backend.

## Decisions (agreed with the user)

| Topic | Decision |
|---|---|
| Widget tap | Opens a full-screen red Emergency screen **and** starts the beacon in a foreground service, so it survives locking the phone. |
| Stopping | "I'm safe" button on the Emergency screen, then an iOS-style confirmation alert. Nothing else stops emergency mode. |
| Widget look | Square 2×2. Idle: dark card with a red SOS button. Active: red card, "Searching for you…". |
| Victim info | iOS Medical ID-style card at the top of the rescuer screen; status text ("Nearby" / "Getting closer") sits under it. |
| Pressure tile | Replaced by a **Level** tile: "Same / level", "↑ 3 m / above you", "↓ 2 m / below you". |
| Levelling | The visible badge and hint are removed. Long-pressing the Level tile levels the barometers, with a vibration and no on-screen hint. |

## Architecture

```
 EmergencyWidget ──tap──▶ MainActivity (ACTION_EMERGENCY) ──▶ permissions / Bluetooth on
                                      │
                                      ▼ startForegroundService(SEARCH | EMERGENCY | SAFE)
                               BeaconService  (foreground, connectedDevice|location)
                                      │ owns exactly one
                                      ▼
                               BeaconEngine  ── advertiser, scanner, link, Wi-Fi ranger,
                                      │          proximity, pressure, location, payload refresh
                                      ▼ Compose state (read directly by the UI)
                    BeaconScreen (rescuer)  /  EmergencyScreen (victim)
```

### BeaconEngine — `beacon/BeaconEngine.kt` (new)

Everything `MainActivity` wires together today moves here with unchanged behaviour: the
component construction and callback wiring, `buildPayload()`, the 1 s payload refresh, the
`RescueLog` status line, and `startBeacon()`.

- `start()`: pressure; location if permitted; Wi-Fi ranging if permitted; advertiser, scanner and link
  if the Bluetooth permissions are granted and the adapter is on. `running` (Compose state) is true once
  Bluetooth is up.
- `stop()`: stops all of it.
- `emergency` (Compose state). Setting it pushes a new payload immediately instead of waiting for the next
  1 s refresh.
- It exposes what the UI reads: `pressure`, `scanner.peers`, `proximity.states`, `running`, `emergency`.

### BeaconService — `beacon/BeaconService.kt` (new)

A foreground service that holds the single engine in `companion object { var engine by mutableStateOf<BeaconEngine?>(null) }`.

| Action | Effect |
|---|---|
| `SEARCH` | Ensure the engine is running. Does **not** clear an active emergency. |
| `EMERGENCY` | Ensure running, set `engine.emergency = true`. |
| `SAFE` | Set `engine.emergency = false`. The service stays up in search mode, because the app is on screen. |

- `startForeground` types are computed from the granted permissions: always `connectedDevice`, plus
  `location` only if location is granted (API 34+ throws otherwise). Use the plain 2-arg call on API 28.
- Notifications use two channels:
  - **Search** (low importance): "Looking for people who need help".
  - **Emergency** (high importance, ongoing, red): "Emergency mode on · Your phone is being searched".
  - Tapping either one opens the app. There is deliberately no stop action, so the confirmation can't be skipped.
- After every emergency change and in `onDestroy`, call `EmergencyWidget.refresh(context)`.
  `onDestroy` also stops the engine and sets it to null.

### MainActivity

- Becomes `launchMode="singleTask"`. It handles `ACTION_EMERGENCY` in `onCreate` and `onNewIntent`, and keeps the
  permission and Bluetooth-enable flow. It adds `POST_NOTIFICATIONS` (API 33+) to the request.
- Once Bluetooth is ready, it starts the service with `EMERGENCY` if that was requested, otherwise `SEARCH`.
- Screen choice: `EmergencyScreen` if an emergency was requested or `engine.emergency` is true,
  otherwise `BeaconScreen`.
- `onDestroy`: stop the service unless the engine is in emergency mode.

### Payload — `ble/BeaconPayload.kt`

Add `emergency: Boolean = false`, carried as flags **bit 4** (currently unused). Size and layout are
otherwise unchanged. The rescuer screen only considers peers whose payload has `emergency == true`:
the strongest such peer, by RSSI as today.

## UI

### Rescuer — `ui/BeaconScreen.kt`

Top to bottom:

1. **Medical ID card** (new `ui/MedicalIdCard.kt`, with the hardcoded `DemoVictim` profile in the same file).
   - It slides in (`AnimatedVisibility`, expand + fade) once an emergency peer is found, and is hidden while searching.
   - Contents: a red "✱ MEDICAL ID" caption, the name (Title 3), "Female · 34 · Blood A+" (Body), and
     "Asthma · Penicillin allergy" (Body, secondary colour).
   - Same surface colour and 16 dp corners as the tiles, so it turns to frosted glass on the green "Here" screen.
2. **Status**: "Nearby" / "Getting closer", unchanged. While searching, the subtitle reads
   "Looking for people who need help".
3. **Circle**: unchanged.
4. **Tiles**: Battery, Location, **Level**.
   - The Level tile shows `levelText(heightM)`: value "Same" / "↑ 3 m" / "↓ 2 m" plus a detail line
     "level" / "above you" / "below you". It shows "—" if either phone has no barometer.
   - Long-pressing it stores `offsetPa = ownPa − peerPa` (`rememberSaveable`, keyed by peer id) and
     vibrates. Use `rememberUpdatedState` so the gesture reads the current pressures.
5. The `ElevationBadge`, its footnote and the pressure (hPa) tile are removed.

`estimation/Elevation.kt`: `elevationLabel()` is replaced by `levelText(heightM): LevelText(value, detail)`.
The thresholds stay as they are (`SAME_LEVEL_M = 1.5`, whole metres).

### Victim — `ui/EmergencyScreen.kt` (new)

```
┌───────────────────────────────┐   iOS red, subtle vertical gradient
│ ● EMERGENCY MODE              │
│ Your phone is being searched  │   → "A rescuer is nearby" when any peer < 5 m and fresh
│          ╭─────────╮          │
│         ╱    SOS    ╲         │   ProximityCircle(0.45, HERE) with "SOS" on top
│          ╰─────────╯          │
│ Stay where you are. Rescuers  │
│ nearby can find this phone.   │
│ ╭───────────────────────────╮ │
│ │         I'm safe          │ │   white capsule, red text
│ ╰───────────────────────────╯ │
└───────────────────────────────┘
```

- While the engine isn't running yet (permissions being requested, Bluetooth off), the title is
  "Starting emergency mode…" and "I'm safe" is hidden. If Bluetooth can't start, the screen shows
  "Allow Bluetooth to send an SOS" with a white **Try again** capsule.
- **I'm safe** opens an iOS-style alert (a custom `Dialog`: 270 dp wide, 14 dp corners, dark surface):
  - Title "Are you safe?", message "Rescuers will no longer be able to find this phone."
  - Buttons side by side: **Keep searching** (bold, blue, dismisses) and **I'm safe** (red), which sends `SAFE`.

### Widget — `widget/EmergencyWidget.kt` (new, RemoteViews, no new dependencies)

- Two layouts, `res/layout/widget_emergency_idle.xml` and `widget_emergency_active.xml`, with rounded
  backgrounds: `#1C1C1E` idle, red gradient active. The text matches the agreed mockup.
- Tapping either state opens `MainActivity` with `ACTION_EMERGENCY`. When already active it simply
  shows the Emergency screen.
- `res/xml/emergency_widget_info.xml`: 2×2 (`targetCellWidth/Height = 2`, min 110 dp), not resizable,
  no periodic updates, `previewLayout` = the idle layout.
- `refresh(context)` pushes the layout for the current `engine?.emergency` state to all instances.
  `onUpdate` does the same.
- A new vector `res/drawable/ic_sos.xml` is used by the notification.

### Manifest

- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`,
  `POST_NOTIFICATIONS`.
- `<service .beacon.BeaconService exported=false foregroundServiceType="connectedDevice|location">`.
- `<receiver .widget.EmergencyWidget exported=true>` with the `APPWIDGET_UPDATE` filter and
  provider meta-data, labelled "Emergency".
- `MainActivity`: `launchMode="singleTask"`.

## Error handling

- Missing permissions or Bluetooth off: the service isn't started. The active screen shows its own prompt
  (rescuer: the existing "Ready to find" screen; victim: "Allow Bluetooth to send an SOS").
- Location denied: the service runs without the `location` type, and the GPS tile says "No fix".
- Notification permission denied: the service still runs, just without a visible notification.
- If the process dies, the widget may show "active" until it is next tapped, because `onDestroy` refreshes it when it can.
  This is acceptable for a demo.

## Testing

1. **Unit tests, written first**:
   - `BeaconPayloadTest`: round trip with `emergency` true and false, with the other fields preserved.
   - `ElevationTest`: the `levelText` cases replace the label cases.
2. `./gradlew assembleDebug testDebugUnitTest`.
3. **Emulator screenshots** (throwaway instrumentation test, deleted afterwards): the rescuer screen while
   searching, Nearby with the Medical ID card, Here and Signal lost, then the Emergency screen while starting
   and while running, the "I'm safe" alert, and both widget layouts rendered with `RemoteViews.apply`.
4. **Emulator service check**:
   - Start with `am start -a <ACTION_EMERGENCY>`.
   - `dumpsys activity services` shows a foreground `BeaconService`.
   - Lock the screen and confirm the `RescueLog` lines continue with advertising active.
   - Confirm "I'm safe" switches the screen and widget back.
5. **Two-phone run (user)**:
   - Victim adds the widget and taps it. The phone goes red, then gets locked.
   - Rescuer opens the app. Searching… turns into the Medical ID card and the circle, and the Level tile
     shows the floor difference.
   - Walk up until the rescuer's screen says "Here" and the victim's says "A rescuer is nearby".
   - Victim taps "I'm safe" and confirms.

## Out of scope

Sending the real profile over Bluetooth, editing the profile, a stop action in the notification,
keeping emergency mode across reboots, lock-screen widgets.
