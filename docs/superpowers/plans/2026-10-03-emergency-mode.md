# Emergency Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **This run:** inline, single agent (user's instruction: no multi-agent).

**Goal:** Victim taps an Emergency widget → phone broadcasts an SOS from a foreground service and shows a red Emergency screen; the rescuer's finder shows a Medical ID card and a Level tile, and only looks for phones in emergency mode.

**Architecture:** The Bluetooth/sensor wiring moves out of `MainActivity` into `BeaconEngine`. One engine per process is owned by the foreground `BeaconService`, which the app starts in search mode and the widget path starts in emergency mode. The UI reads the engine's Compose state directly. The emergency flag travels in bit 4 of the advertised payload's flags byte.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 slots, iOS styling), Android platform BLE, `RemoteViews` app widget, `NotificationCompat` from the existing `core-ktx`.

**Spec:** `docs/superpowers/specs/2026-10-03-emergency-mode-design.md`

## Global Constraints

- `minSdk 28`, `compileSdk`/`targetSdk 37`. **No new Gradle dependencies.**
- Colours only from `ui/theme/Color.kt` (iOS palette). Text styles only from `MaterialTheme.typography` (Inter, iOS scale).
- Compose UI strings stay hardcoded in Kotlin, as the existing screens do. Strings referenced from XML (widget, manifest) go in `res/values/strings.xml`.
- Demo profile (hardcoded): Anna Kowalska · Female · 34 · Blood A+ · Asthma · Penicillin allergy.
- Logcat tag stays `RescueLog`. The status line format is unchanged.
- Verify with: `./gradlew assembleDebug testDebugUnitTest` (from repo root, Git Bash).
- Commit after each task on branch `feat/demo-ui`. Messages follow the repo style (`feat: …`) and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File Map

| File | Responsibility |
|---|---|
| `ble/BeaconPayload.kt` (modify) | `emergency` flag, bit 4 |
| `estimation/Elevation.kt` (modify) | `levelText()` replaces `elevationLabel()` |
| `ui/BeaconScreen.kt` (modify) | Level tile + hidden long-press, Medical ID card slot, emergency-only peers |
| `ui/MedicalIdCard.kt` (create) | `VictimProfile`, `DemoVictim`, `MedicalIdCard` |
| `beacon/Permissions.kt` (create) | Permission lists and the `granted()` check shared by activity, engine and service |
| `beacon/BeaconEngine.kt` (create) | All sensor/BLE/ranging wiring, payload refresh, status log |
| `beacon/BeaconService.kt` (create) | Foreground service owning the engine; notifications; actions |
| `MainActivity.kt` (modify) | Permissions → start service; choose screen; widget intent |
| `ui/EmergencyScreen.kt` (create) | Victim's red screen + iOS "Are you safe?" alert |
| `widget/EmergencyWidget.kt` (create) | `AppWidgetProvider`, `views()`, `refresh()` |
| `res/layout/widget_emergency_{idle,active}.xml`, `res/drawable/widget_*.xml`, `res/drawable/ic_sos.xml`, `res/xml/emergency_widget_info.xml` (create) | Widget visuals and the notification icon |
| `AndroidManifest.xml`, `res/values/strings.xml` (modify) | Service, receiver, permissions, `singleTask`, widget strings |

(Paths under `app/src/main/java/com/da4a/smartcity/` unless they start with `res/`, which is `app/src/main/res/`.)

---

### Task 0: Commit the design docs

- [ ] **Step 1: Commit spec and plan**

```bash
git add docs/superpowers/specs/2026-10-03-emergency-mode-design.md docs/superpowers/plans/2026-10-03-emergency-mode.md
git commit -m "docs: emergency mode design and implementation plan"   # + Co-Authored-By line
```

---

### Task 1: Emergency flag in the payload

**Files:**
- Modify: `app/src/main/java/com/da4a/smartcity/ble/BeaconPayload.kt`
- Test (create): `app/src/test/java/com/da4a/smartcity/ble/BeaconPayloadTest.kt`

**Interfaces:**
- Produces: `BeaconPayload(..., emergency: Boolean = false)` (last constructor parameter); `decode()` fills it from flags bit 4.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.da4a.smartcity.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconPayloadTest {

    private val payload = BeaconPayload(
        deviceId = 0xCAFEBABEL, batteryPct = 82, pressurePa = 100_840, lat = 52.2297, lon = 21.0122,
        fixAgeMin = 3, seq = 7, rangedPeerId = 0x12345678L, rangedDistanceCm = 250,
        cannotMeasureRssi = true, cannotRange = false,
    )

    @Test
    fun emergencyFlagSurvivesARoundTrip() {
        assertTrue(BeaconPayload.decode(payload.copy(emergency = true).encode())!!.emergency)
        assertFalse(BeaconPayload.decode(payload.copy(emergency = false).encode())!!.emergency)
    }

    @Test
    fun emergencyFlagLeavesTheOtherFieldsIntact() {
        val sent = payload.copy(emergency = true)
        assertEquals(sent, BeaconPayload.decode(sent.encode()))
    }
}
```

- [ ] **Step 2: Run it, expect a compile failure** (`No parameter with name 'emergency'`)

Run: `./gradlew testDebugUnitTest --tests "com.da4a.smartcity.ble.BeaconPayloadTest" -q`

- [ ] **Step 3: Implement**

In `BeaconPayload.kt`:
- KDoc flag list: after `bit3 Wi-Fi ranging from this phone keeps failing` add `, bit4 emergency (the user asked for help)`.
- Constructor: after `val cannotRange: Boolean = false,` add
  ```kotlin
      /** The user asked for help; rescuers only look for phones with this set. */
      val emergency: Boolean = false,
  ```
- `encode()`: after `if (cannotRange) flags = flags or FLAG_NO_RANGE` add `if (emergency) flags = flags or FLAG_EMERGENCY`.
- Companion: after `private const val FLAG_GPS_FIX = 1 shl 2` add `private const val FLAG_EMERGENCY = 1 shl 4`.
- `decode()`: after `cannotRange = flags and FLAG_NO_RANGE != 0,` add `emergency = flags and FLAG_EMERGENCY != 0,`.

- [ ] **Step 4: Run, expect PASS (2 tests)**

Run: `./gradlew testDebugUnitTest --tests "com.da4a.smartcity.ble.BeaconPayloadTest" -q`

- [ ] **Step 5: Commit** — `feat: emergency flag in the advertised payload`

---

### Task 2: Level tile replaces pressure tile and badge; hidden long-press levelling

**Files:**
- Modify: `app/src/main/java/com/da4a/smartcity/estimation/Elevation.kt`
- Modify: `app/src/test/java/com/da4a/smartcity/estimation/ElevationTest.kt`
- Modify: `app/src/main/java/com/da4a/smartcity/ui/BeaconScreen.kt`

**Interfaces:**
- Consumes: `heightAboveM(ownPa, peerPa, offsetPa)` (exists).
- Produces: `data class LevelText(val value: String, val detail: String)`, `fun levelText(heightM: Float): LevelText`. `elevationLabel()` is deleted.

- [ ] **Step 1: Update the tests first.** In `ElevationTest.kt` replace the two label tests (`smallDifferencesReadAsSameLevel`, `labelsRoundToWholeMetres`) with:

```kotlin
    @Test
    fun smallDifferencesReadAsSameLevel() {
        assertEquals(LevelText("Same", "level"), levelText(0f))
        assertEquals(LevelText("Same", "level"), levelText(1.4f))
        assertEquals(LevelText("Same", "level"), levelText(-1.4f))
    }

    @Test
    fun levelTextRoundsToWholeMetres() {
        assertEquals(LevelText("↑ 3 m", "above you"), levelText(2.988f))
        assertEquals(LevelText("↓ 2 m", "below you"), levelText(-2.4f))
        assertEquals(LevelText("↑ 2 m", "above you"), levelText(1.6f))
    }
```

- [ ] **Step 2: Run, expect a compile failure** (`Unresolved reference 'LevelText'`)

Run: `./gradlew testDebugUnitTest --tests "com.da4a.smartcity.estimation.ElevationTest" -q`

- [ ] **Step 3: Implement in `Elevation.kt`.** Replace `fun elevationLabel(...)` with:

```kotlin
/** Two lines for the Level tile: a short value and what it means. */
data class LevelText(val value: String, val detail: String)

fun levelText(heightM: Float): LevelText = when {
    abs(heightM) < SAME_LEVEL_M -> LevelText("Same", "level")
    heightM > 0 -> LevelText("↑ ${heightM.roundToInt()} m", "above you")
    else -> LevelText("↓ ${(-heightM).roundToInt()} m", "below you")
}
```

- [ ] **Step 4: Update `BeaconScreen.kt`.**

Imports: remove `androidx.compose.animation.animateContentSize` and `com.da4a.smartcity.estimation.elevationLabel`. Add:
```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import com.da4a.smartcity.estimation.levelText
```

Replace the details block
```kotlin
                Column(Modifier.alpha(detailsAlpha), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevationBadge(ownPressurePa, peer?.payload?.pressurePa, peer?.id, surface, secondary)
                    InfoTiles(peer?.payload, surface, secondary)
                }
```
with
```kotlin
                InfoTiles(peer?.payload, ownPressurePa, peer?.id, surface, secondary, Modifier.alpha(detailsAlpha))
```

Delete the whole `ElevationBadge` composable (with its KDoc).

Replace `InfoTiles` and `Tile` with:

```kotlin
@Composable
private fun InfoTiles(
    payload: BeaconPayload?,
    ownPressurePa: Float?,
    peerId: Long?,
    surface: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tile("Battery", surface, secondary) {
            if (payload == null) {
                Value("—")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BatteryGlyph(payload.batteryPct)
                    Value("${payload.batteryPct}%")
                }
            }
        }
        Tile("Location", surface, secondary) {
            if (payload?.lat != null && payload.lon != null) {
                Value(String.format(Locale.US, "%.4f", payload.lat), MaterialTheme.typography.titleMedium)
                Value(String.format(Locale.US, "%.4f", payload.lon), MaterialTheme.typography.titleMedium)
            } else {
                Value("No fix", color = secondary)
            }
        }
        LevelTile(ownPressurePa, payload?.pressurePa, peerId, surface, secondary)
    }
}

/**
 * How far above or below the other phone is. Long-pressing it with the phones side by side
 * cancels the bias between the two barometers; there is deliberately no hint for it on screen.
 */
@Composable
private fun RowScope.LevelTile(ownPa: Float?, peerPa: Int?, peerId: Long?, surface: Color, secondary: Color) {
    var offsetPa by rememberSaveable(peerId) { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val level by rememberUpdatedState {
        if (ownPa != null && peerPa != null) {
            offsetPa = ownPa - peerPa
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    Tile("Level", surface, secondary, Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { level() }) }) {
        if (ownPa == null || peerPa == null) {
            Value("—")
        } else {
            val text = levelText(heightAboveM(ownPa, peerPa.toFloat(), offsetPa))
            Value(text.value)
            Text(text.detail, style = MaterialTheme.typography.bodySmall, color = secondary)
        }
    }
}

@Composable
private fun RowScope.Tile(
    title: String,
    surface: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .then(modifier)
            .background(surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = secondary)
        content()
    }
}
```

- [ ] **Step 5: Run all unit tests and build, expect PASS**

Run: `./gradlew assembleDebug testDebugUnitTest -q`

- [ ] **Step 6: Commit** — `feat: level tile with a hidden long-press to level the barometers`

---

### Task 3: Medical ID card; rescuer only looks for phones in emergency mode

**Files:**
- Create: `app/src/main/java/com/da4a/smartcity/ui/MedicalIdCard.kt`
- Modify: `app/src/main/java/com/da4a/smartcity/ui/BeaconScreen.kt`

**Interfaces:**
- Consumes: `BeaconPayload.emergency` (Task 1).
- Produces: `MedicalIdCard(profile: VictimProfile, surface: Color, secondary: Color, accent: Color, modifier: Modifier = Modifier)`, `val DemoVictim: VictimProfile`.

- [ ] **Step 1: Create `MedicalIdCard.kt`**

```kotlin
package com.da4a.smartcity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Who the rescuer is looking for. */
data class VictimProfile(
    val name: String,
    val sex: String,
    val age: Int,
    val bloodType: String,
    val conditions: List<String>,
)

/** Hardcoded for the demo; later it would come from the victim's phone. */
val DemoVictim = VictimProfile(
    name = "Anna Kowalska",
    sex = "Female",
    age = 34,
    bloodType = "A+",
    conditions = listOf("Asthma", "Penicillin allergy"),
)

/** iOS Medical ID-style summary of the person being searched for. */
@Composable
fun MedicalIdCard(profile: VictimProfile, surface: Color, secondary: Color, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("✱ MEDICAL ID", style = MaterialTheme.typography.labelSmall, color = accent)
        Text(profile.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        Text("${profile.sex} · ${profile.age} · Blood ${profile.bloodType}", style = MaterialTheme.typography.bodyLarge)
        Text(profile.conditions.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = secondary)
    }
}
```

- [ ] **Step 2: Wire it into `BeaconScreen.kt`.**

Imports, add:
```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
```

Peer choice: replace `val peer = peers.maxByOrNull { it.rssi }` with
```kotlin
    // Only phones whose owner asked for help are searched for.
    val peer = peers.filter { it.payload?.emergency == true }.maxByOrNull { it.rssi }
```

After the `secondary` colour, add:
```kotlin
    // The red caption would clash with the green background.
    val accent by animateColorAsState(if (here) IosLabel else IosRed, tween(500), label = "accent")
```

Searching subtitle: replace `prox == null -> "Looking for the other phone"` with `prox == null -> "Looking for people who need help"`.

Directly before `Header(title, subtitle, ...)` insert:
```kotlin
                AnimatedVisibility(
                    visible = prox != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    MedicalIdCard(DemoVictim, surface, secondary, accent, Modifier.padding(bottom = 16.dp))
                }
```

Previews: in `PreviewScreen`, add `emergency = true` to the `BeaconPayload(...)` call.

- [ ] **Step 3: Build and test, expect PASS**

Run: `./gradlew assembleDebug testDebugUnitTest -q`

- [ ] **Step 4: Commit** — `feat: Medical ID card; the finder only looks for phones in emergency mode`

---

### Task 4: Bluetooth engine in a foreground service (search mode)

**Files:**
- Create: `app/src/main/java/com/da4a/smartcity/beacon/Permissions.kt`
- Create: `app/src/main/java/com/da4a/smartcity/beacon/BeaconEngine.kt`
- Create: `app/src/main/java/com/da4a/smartcity/beacon/BeaconService.kt`
- Create: `app/src/main/res/drawable/ic_sos.xml`
- Modify: `app/src/main/java/com/da4a/smartcity/MainActivity.kt` (full rewrite below)
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces:
  - `object Permissions { val BLUETOOTH: List<String>; val ALL: List<String>; fun granted(context: Context, permission: String): Boolean; fun locationGranted(context: Context): Boolean }`
  - `class BeaconEngine(context: Context)` with `val pressure: PressureSource`, `val scanner: BeaconScanner`, `val proximity: ProximityTracker`, `val running: Boolean` (state), `var emergency: Boolean` (state; setter re-advertises), `fun start()`, `fun stop()`
  - `class BeaconService` with companion `val engine: BeaconEngine?` (state), `fun send(context: Context, action: String)`, actions `ACTION_SEARCH`, `ACTION_EMERGENCY`, `ACTION_SAFE`

- [ ] **Step 1: Create `Permissions.kt`**

```kotlin
package com.da4a.smartcity.beacon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    val BLUETOOTH: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // Before Android 12, scanning needs location instead.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    val WIFI: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()

    private val NOTIFICATIONS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    val ALL: List<String> = (BLUETOOTH + LOCATION + WIFI + NOTIFICATIONS).distinct()

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun locationGranted(context: Context) = LOCATION.any { granted(context, it) }
}
```

- [ ] **Step 2: Create `BeaconEngine.kt`.** This moves the wiring from `MainActivity`, with the same behaviour plus `emergency`.

```kotlin
package com.da4a.smartcity.beacon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.da4a.smartcity.ble.BeaconAdvertiser
import com.da4a.smartcity.ble.BeaconLink
import com.da4a.smartcity.ble.BeaconPayload
import com.da4a.smartcity.ble.BeaconScanner
import com.da4a.smartcity.estimation.ProximityTracker
import com.da4a.smartcity.sensors.BatterySource
import com.da4a.smartcity.sensors.LocationSource
import com.da4a.smartcity.sensors.PressureSource
import com.da4a.smartcity.wifi.AwareRanger
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Everything one phone runs to be found and to find others: sensors, Bluetooth advertising,
 * scanning and the signal-strength link, Wi-Fi ranging, and the closeness estimate. Public
 * state is Compose state, so screens read it directly.
 */
class BeaconEngine(private val context: Context) {

    private val deviceId = Random.nextInt().toLong() and 0xFFFFFFFFL
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    val pressure = PressureSource(context)
    private val location = LocationSource(context)
    private val ranger = AwareRanger(context, deviceId)
    val proximity = ProximityTracker()
    private val advertiser = BeaconAdvertiser(adapter)
    val scanner = BeaconScanner(adapter)
    private val link = BeaconLink(context, adapter, deviceId)

    /** True once Bluetooth advertising and scanning are up. */
    var running by mutableStateOf(false)
        private set

    private var emergencyState by mutableStateOf(false)

    /** Advertised to other phones; rescuers only look for phones with this set. */
    var emergency: Boolean
        get() = emergencyState
        set(value) {
            emergencyState = value
            // Tell rescuers right away instead of at the next refresh.
            if (running) advertiser.update(buildPayload())
        }

    private var seq = 0

    // Latest Wi-Fi distance measured by this phone, broadcast so the other phone can use it
    // even when its own ranging attempts fail.
    private var rangedPeerId = 0L
    private var rangedDistanceM = 0f
    private var rangedTimeMs = 0L

    private val refreshPayload = object : Runnable {
        override fun run() {
            advertiser.update(buildPayload())
            logStatus()
            handler.postDelayed(this, PAYLOAD_REFRESH_MS)
        }
    }

    init {
        link.onRssi = proximity::onConnRssi
        scanner.onConnectable = { device, payload -> link.onConnectable(device, payload.deviceId, payload.cannotMeasureRssi) }
        ranger.peerInRange = { (proximity.recentRssi(it) ?: -127f) > RANGING_MIN_RSSI_DBM }
        ranger.peerCannotRange = { scanner.peers[it]?.payload?.cannotRange == true }
        scanner.onSighting = { peer ->
            proximity.onBle(peer.id, peer.rssi)
            val cm = peer.payload?.rangedDistanceCm
            if (cm != null && peer.payload.rangedPeerId == deviceId) {
                proximity.onRange(peer.id, cm / 100f, raw = false)
            }
        }
        ranger.onDistance = { id, meters ->
            proximity.onRange(id, meters)
            rangedPeerId = id
            rangedDistanceM = proximity.rangeTo(id) ?: meters
            rangedTimeMs = SystemClock.elapsedRealtime()
        }
    }

    /** Starts whatever the granted permissions and the adapter allow; safe to call again. */
    fun start() {
        pressure.start()
        if (Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            location.stop()
            location.start()
            // Wi-Fi ranging is optional: Bluetooth proximity keeps working without it.
            if (Permissions.WIFI.all { Permissions.granted(context, it) }) ranger.start()
        }
        if (running || !Permissions.BLUETOOTH.all { Permissions.granted(context, it) } || adapter?.isEnabled != true) return
        running = true
        advertiser.start(buildPayload())
        scanner.start()
        link.start()
        handler.postDelayed(refreshPayload, PAYLOAD_REFRESH_MS)
    }

    fun stop() {
        handler.removeCallbacks(refreshPayload)
        if (running) {
            advertiser.stop()
            scanner.stop()
            link.stop()
            running = false
        }
        location.stop()
        pressure.stop()
        ranger.stop()
    }

    private fun buildPayload(): BeaconPayload {
        val fix = location.fix
        val rangeFresh = rangedTimeMs != 0L && SystemClock.elapsedRealtime() - rangedTimeMs < RANGE_SHARE_MS
        return BeaconPayload(
            deviceId = deviceId,
            batteryPct = BatterySource.percent(context),
            pressurePa = pressure.pressurePa?.roundToInt(),
            lat = fix?.latitude,
            lon = fix?.longitude,
            fixAgeMin = fix?.let { (LocationSource.ageSeconds(it) / 60).toInt() } ?: 0,
            seq = seq++,
            rangedPeerId = rangedPeerId.takeIf { rangeFresh },
            rangedDistanceCm = (rangedDistanceM * 100).roundToInt().takeIf { rangeFresh },
            cannotMeasureRssi = !link.canMeasure,
            cannotRange = ranger.cannotRange,
            emergency = emergency,
        )
    }

    /** One line per second and peer, for analysing dropouts from logcat after a walk test. */
    private fun logStatus() {
        // (body moved verbatim from MainActivity.logStatus())
    }

    private companion object {
        const val PAYLOAD_REFRESH_MS = 1000L
        const val TAG = "RescueLog"
        const val RANGE_SHARE_MS = 3000L
        const val RANGING_MIN_RSSI_DBM = -72f
    }
}
```

The `logStatus()` body is copied character for character from the current `MainActivity.logStatus()`. It is the `val now = …` line, the "no peers" `Log.i`, and the per-peer `Log.i` loop that reads `advertiser.state`, `scanner.state`, `link.state` and `ranger.state`. All of those names exist in the engine.

- [ ] **Step 3: Create `res/drawable/ic_sos.xml`** (white asterisk, notification icon)

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M10.5,3h3v18h-3z" />
    <group android:pivotX="12" android:pivotY="12" android:rotation="60">
        <path android:fillColor="#FFFFFFFF" android:pathData="M10.5,3h3v18h-3z" />
    </group>
    <group android:pivotX="12" android:pivotY="12" android:rotation="120">
        <path android:fillColor="#FFFFFFFF" android:pathData="M10.5,3h3v18h-3z" />
    </group>
</vector>
```

- [ ] **Step 4: Create `BeaconService.kt`**

```kotlin
package com.da4a.smartcity.beacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import com.da4a.smartcity.MainActivity
import com.da4a.smartcity.R

/**
 * Keeps the one [BeaconEngine] of this process running in the foreground, so a victim's phone
 * stays findable with the screen locked. The open app runs it in search mode; the Emergency
 * widget path switches it to emergency mode, which only "I'm safe" in the app ends.
 */
class BeaconService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = engine ?: BeaconEngine(this).also { engine = it }
        when (intent?.action) {
            ACTION_EMERGENCY -> engine.emergency = true
            ACTION_SAFE -> engine.emergency = false
        }
        goForeground(engine.emergency)
        engine.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun goForeground(emergency: Boolean) {
        val notification = notification(emergency)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            // Declaring location without the permission throws on Android 14+.
            if (Permissions.locationGranted(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(emergency: Boolean): Notification {
        val channel = if (emergency) {
            NotificationChannel(CHANNEL_EMERGENCY, "Emergency mode", NotificationManager.IMPORTANCE_HIGH)
        } else {
            NotificationChannel(CHANNEL_SEARCH, "Searching", NotificationManager.IMPORTANCE_LOW)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // Opening the app is the only action: stopping an emergency needs the in-app confirmation.
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channel.id)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(if (emergency) "Emergency mode on" else "Looking for people who need help")
            .setContentText(if (emergency) "Your phone is being searched" else null)
            .setColor(if (emergency) 0xFFFF453A.toInt() else 0xFF0A84FF.toInt())
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_SEARCH = "com.da4a.smartcity.action.SEARCH"
        const val ACTION_EMERGENCY = "com.da4a.smartcity.action.START_EMERGENCY"
        const val ACTION_SAFE = "com.da4a.smartcity.action.SAFE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_SEARCH = "search"
        private const val CHANNEL_EMERGENCY = "emergency"

        /** The engine of this process while the service runs. */
        var engine by mutableStateOf<BeaconEngine?>(null)
            private set

        fun send(context: Context, action: String) {
            context.startForegroundService(Intent(context, BeaconService::class.java).setAction(action))
        }
    }
}
```

- [ ] **Step 5: Rewrite `MainActivity.kt`** (search mode only; the emergency flow comes in Task 5)

```kotlin
package com.da4a.smartcity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.da4a.smartcity.beacon.BeaconService
import com.da4a.smartcity.beacon.Permissions
import com.da4a.smartcity.ui.BeaconScreen
import com.da4a.smartcity.ui.theme.SmartCityTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startIfReady() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startIfReady(askToEnable = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status bar icons over the black (or green) background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions()

        setContent {
            SmartCityTheme {
                val engine = BeaconService.engine
                BeaconScreen(
                    ownPressurePa = engine?.pressure?.pressurePa,
                    peers = engine?.scanner?.peers?.values ?: emptyList(),
                    proximity = engine?.proximity?.states ?: emptyMap(),
                    running = engine?.running == true,
                    onRetry = ::requestPermissions,
                )
            }
        }
    }

    override fun onDestroy() {
        // Searching belongs to the open app; an emergency keeps running without it.
        if (!isChangingConfigurations && BeaconService.engine?.emergency != true) {
            stopService(Intent(this, BeaconService::class.java))
        }
        super.onDestroy()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(Permissions.ALL.toTypedArray())
    }

    /** Starts the service once Bluetooth can run, asking to switch it on first if needed. */
    private fun startIfReady(askToEnable: Boolean = true) {
        if (!Permissions.BLUETOOTH.all { Permissions.granted(this, it) }) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (adapter.isEnabled) {
            BeaconService.send(this, BeaconService.ACTION_SEARCH)
        } else if (askToEnable) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}
```

- [ ] **Step 6: Manifest.**

After the `NEARBY_WIFI_DEVICES` permission line add:
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
Inside `<application>`, after `</activity>` add:
```xml
        <service
            android:name=".beacon.BeaconService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice|location" />
```

- [ ] **Step 7: Build and test, expect PASS**

Run: `./gradlew assembleDebug testDebugUnitTest -q`

- [ ] **Step 8: Emulator smoke check.** Install, grant permissions with `pm grant`, and launch.
  - `adb shell dumpsys activity services com.da4a.smartcity` shows `BeaconService` with `isForeground=true`.
  - `logcat` shows `RescueLog: no peers | adv=active … scan=active`.
  - Press back to close the activity. The service is gone (dumpsys is empty).

- [ ] **Step 9: Commit** — `feat: run the beacon from a foreground service`

---

### Task 5: Emergency screen and the emergency flow in the app

**Files:**
- Create: `app/src/main/java/com/da4a/smartcity/ui/EmergencyScreen.kt`
- Modify: `app/src/main/java/com/da4a/smartcity/ui/BeaconScreen.kt` (make the three zone constants `internal`)
- Modify: `app/src/main/java/com/da4a/smartcity/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/da4a/smartcity/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`launchMode`)

**Interfaces:**
- Consumes: `BeaconService.engine`, `BeaconService.send`, `ACTION_EMERGENCY`/`ACTION_SAFE`/`ACTION_SEARCH` (Task 4); `ProximityCircle`, `CircleStyle.HERE` (existing).
- Produces: `EmergencyScreen(running: Boolean, proximity: Map<Long, Proximity>, onRetry: () -> Unit, onSafe: () -> Unit, modifier: Modifier = Modifier)`; `MainActivity.ACTION_EMERGENCY = "com.da4a.smartcity.EMERGENCY"` (used by the widget in Task 6).

- [ ] **Step 1: Colours.** Append to `Color.kt`:
```kotlin
val IosSeparator = Color(0x99545458)
val IosAlertBackground = Color(0xFF2C2C2E)
```

- [ ] **Step 2: In `BeaconScreen.kt`**, change `private const val HERE_M`, `private const val NEAR_M` and `private const val SIGNAL_LOST_MS` to `internal const val`.

- [ ] **Step 3: Create `EmergencyScreen.kt`**

```kotlin
package com.da4a.smartcity.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.da4a.smartcity.estimation.Proximity
import com.da4a.smartcity.estimation.Trend
import com.da4a.smartcity.ui.theme.IosAlertBackground
import com.da4a.smartcity.ui.theme.IosBlue
import com.da4a.smartcity.ui.theme.IosLabel
import com.da4a.smartcity.ui.theme.IosRed
import com.da4a.smartcity.ui.theme.IosSeparator
import com.da4a.smartcity.ui.theme.SmartCityTheme
import kotlinx.coroutines.delay

private val EmergencyRedDeep = Color(0xFFC8231B)
private val SoftWhite = Color.White.copy(alpha = 0.85f)

// Size of the pulsing SOS circle, on the same 0..1 scale as the finder's closeness circle.
private const val SOS_CLOSENESS = 0.45f

/** The victim's screen while their phone broadcasts an SOS. */
@Composable
fun EmergencyScreen(
    running: Boolean,
    proximity: Map<Long, Proximity>,
    onRetry: () -> Unit,
    onSafe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ticks so a rescuer who went quiet stops counting as nearby.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val rescuerNearby = proximity.values.any { it.distanceM < NEAR_M && now - it.updatedMs < SIGNAL_LOST_MS }
    var confirming by remember { mutableStateOf(false) }

    val title = when {
        !running -> "Starting emergency mode…"
        rescuerNearby -> "A rescuer is nearby"
        else -> "Your phone is being searched"
    }
    val message = if (running) {
        "Stay where you are. Rescuers nearby can find this phone."
    } else {
        "Allow Bluetooth so rescuers can find this phone."
    }

    CompositionLocalProvider(LocalContentColor provides IosLabel) {
        Column(
            modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(IosRed, EmergencyRedDeep)))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("●  EMERGENCY MODE", style = MaterialTheme.typography.labelSmall, color = SoftWhite)
            AnimatedContent(
                title,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "title",
            ) {
                Text(it, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ProximityCircle(SOS_CLOSENESS, CircleStyle.HERE)
                Text("SOS", style = MaterialTheme.typography.displaySmall)
            }
            Text(message, style = MaterialTheme.typography.bodyLarge, color = SoftWhite)
            Text(
                if (running) "I'm safe" else "Try again",
                style = MaterialTheme.typography.titleMedium,
                color = IosRed,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { if (running) confirming = true else onRetry() }
                    .padding(vertical = 16.dp),
            )
        }
    }

    if (confirming) {
        SafeAlert(
            onKeepSearching = { confirming = false },
            onSafe = {
                confirming = false
                onSafe()
            },
        )
    }
}

/** iOS-style two-button alert; stopping the SOS takes this deliberate second tap. */
@Composable
private fun SafeAlert(onKeepSearching: () -> Unit, onSafe: () -> Unit) {
    Dialog(onDismissRequest = onKeepSearching) {
        Column(
            Modifier
                .width(270.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(IosAlertBackground),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Are you safe?",
                style = MaterialTheme.typography.titleMedium,
                color = IosLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            )
            Text(
                "Rescuers will no longer be able to find this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = IosLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            )
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(IosSeparator))
            Row(Modifier.height(IntrinsicSize.Min)) {
                AlertButton("Keep searching", IosBlue, FontWeight.SemiBold, onKeepSearching, Modifier.weight(1f))
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(IosSeparator))
                AlertButton("I'm safe", IosRed, FontWeight.Normal, onSafe, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlertButton(text: String, color: Color, weight: FontWeight, onClick: () -> Unit, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = weight),
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun EmergencyPreview() = SmartCityTheme {
    EmergencyScreen(running = true, proximity = emptyMap(), onRetry = {}, onSafe = {})
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun RescuerNearbyPreview() = SmartCityTheme {
    val now = SystemClock.elapsedRealtime()
    EmergencyScreen(
        running = true,
        proximity = mapOf(1L to Proximity(2f, false, -55f, Trend.CLOSER, now)),
        onRetry = {},
        onSafe = {},
    )
}
```

- [ ] **Step 4: `MainActivity.kt` emergency flow.**

Add imports:
```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.da4a.smartcity.ui.EmergencyScreen
```

Add a field above `permissionLauncher`:
```kotlin
    /** Set when opened from the Emergency widget; cleared once the user confirms they are safe. */
    private var emergencyRequested by mutableStateOf(false)
```

In `onCreate`, before `requestPermissions()`:
```kotlin
        emergencyRequested = intent?.action == ACTION_EMERGENCY
```

Replace the `setContent { … }` body with:
```kotlin
        setContent {
            SmartCityTheme {
                val engine = BeaconService.engine
                if (emergencyRequested || engine?.emergency == true) {
                    EmergencyScreen(
                        running = engine?.running == true && engine.emergency,
                        proximity = engine?.proximity?.states ?: emptyMap(),
                        onRetry = ::requestPermissions,
                        onSafe = ::stopEmergency,
                    )
                } else {
                    BeaconScreen(
                        ownPressurePa = engine?.pressure?.pressurePa,
                        peers = engine?.scanner?.peers?.values ?: emptyList(),
                        proximity = engine?.proximity?.states ?: emptyMap(),
                        running = engine?.running == true,
                        onRetry = ::requestPermissions,
                    )
                }
            }
        }
```

Add after `onCreate`:
```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_EMERGENCY) {
            emergencyRequested = true
            requestPermissions()
        }
    }
```

In `startIfReady`, replace `BeaconService.send(this, BeaconService.ACTION_SEARCH)` with:
```kotlin
            BeaconService.send(this, if (emergencyRequested) BeaconService.ACTION_EMERGENCY else BeaconService.ACTION_SEARCH)
```

Add after `startIfReady`:
```kotlin
    private fun stopEmergency() {
        emergencyRequested = false
        BeaconService.send(this, BeaconService.ACTION_SAFE)
    }

    companion object {
        /** Intent action the Emergency widget opens the app with. */
        const val ACTION_EMERGENCY = "com.da4a.smartcity.EMERGENCY"
    }
```

- [ ] **Step 5: Manifest.** On the `<activity android:name=".MainActivity"`, add `android:launchMode="singleTask"`.

- [ ] **Step 6: Build and test, expect PASS**

Run: `./gradlew assembleDebug testDebugUnitTest -q`

- [ ] **Step 7: Emulator flow check.**
  - `adb shell am start -n com.da4a.smartcity/.MainActivity -a com.da4a.smartcity.EMERGENCY` shows the red screen, "Your phone is being searched".
  - `dumpsys` shows the foreground service, and the notification shows "Emergency mode on".
  - Lock the screen (`input keyevent 26`). The `RescueLog` lines continue with `adv=active`.
  - Back out of the app. The service is still running.
  - Reopen, tap **I'm safe**, and the alert appears. **Keep searching** closes it. **I'm safe** switches to the rescuer screen, and the notification goes back to "Looking for people who need help".

- [ ] **Step 8: Commit** — `feat: emergency screen with an "Are you safe?" confirmation`

---

### Task 6: Emergency widget

**Files:**
- Create: `app/src/main/java/com/da4a/smartcity/widget/EmergencyWidget.kt`
- Create: `app/src/main/res/layout/widget_emergency_idle.xml`, `app/src/main/res/layout/widget_emergency_active.xml`
- Create: `app/src/main/res/drawable/widget_bg_idle.xml`, `widget_bg_active.xml`, `widget_sos_button.xml`
- Create: `app/src/main/res/xml/emergency_widget_info.xml`
- Modify: `app/src/main/res/values/strings.xml`, `AndroidManifest.xml`, `beacon/BeaconService.kt`

**Interfaces:**
- Consumes: `MainActivity.ACTION_EMERGENCY` (Task 5), `BeaconService.engine` (Task 4).
- Produces: `EmergencyWidget.views(context: Context, active: Boolean = …): RemoteViews`, `EmergencyWidget.refresh(context: Context)`.

- [ ] **Step 1: Strings** — `res/values/strings.xml` becomes:

```xml
<resources>
    <string name="app_name">SmartCity</string>
    <string name="widget_label">Emergency</string>
    <string name="widget_description">Call for help: makes this phone findable by rescuers nearby.</string>
    <string name="widget_idle_caption">✱ Emergency</string>
    <string name="widget_idle_footer">Tap to call for help nearby</string>
    <string name="widget_active_caption">● EMERGENCY ON</string>
    <string name="widget_active_title">Searching\nfor you…</string>
    <string name="widget_active_footer">Stay where you are</string>
    <string name="widget_sos">SOS</string>
</resources>
```

- [ ] **Step 2: Drawables**

`res/drawable/widget_bg_idle.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#FF1C1C1E" />
    <corners android:radius="24dp" />
</shape>
```
`res/drawable/widget_bg_active.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="270" android:startColor="#FFFF453A" android:endColor="#FFC8231B" />
    <corners android:radius="24dp" />
</shape>
```
`res/drawable/widget_sos_button.xml`:
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#FFFF453A" />
</shape>
```

- [ ] **Step 3: Layouts**

`res/layout/widget_emergency_idle.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg_idle"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="sans-serif-medium"
        android:text="@string/widget_idle_caption"
        android:textColor="#FFFF453A"
        android:textSize="14sp" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">

        <TextView
            android:layout_width="64dp"
            android:layout_height="64dp"
            android:layout_gravity="center"
            android:background="@drawable/widget_sos_button"
            android:fontFamily="sans-serif-medium"
            android:gravity="center"
            android:text="@string/widget_sos"
            android:textColor="#FFFFFFFF"
            android:textSize="20sp"
            android:textStyle="bold" />
    </FrameLayout>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:maxLines="2"
        android:text="@string/widget_idle_footer"
        android:textColor="#99EBEBF5"
        android:textSize="12sp" />
</LinearLayout>
```

`res/layout/widget_emergency_active.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg_active"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="sans-serif-medium"
        android:text="@string/widget_active_caption"
        android:textColor="#D9FFFFFF"
        android:textSize="12sp" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:fontFamily="sans-serif-medium"
        android:gravity="center_vertical"
        android:text="@string/widget_active_title"
        android:textColor="#FFFFFFFF"
        android:textSize="22sp"
        android:textStyle="bold" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:maxLines="2"
        android:text="@string/widget_active_footer"
        android:textColor="#D9FFFFFF"
        android:textSize="12sp" />
</LinearLayout>
```

- [ ] **Step 4: Provider info** — `res/xml/emergency_widget_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_description"
    android:initialLayout="@layout/widget_emergency_idle"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:previewLayout="@layout/widget_emergency_idle"
    android:resizeMode="none"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen" />
```

- [ ] **Step 5: `EmergencyWidget.kt`**

```kotlin
package com.da4a.smartcity.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.da4a.smartcity.MainActivity
import com.da4a.smartcity.R
import com.da4a.smartcity.beacon.BeaconService

/** Home-screen SOS button; turns red while this phone is in emergency mode. */
class EmergencyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {

        /** Shows the current emergency state on every placed widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EmergencyWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        fun views(context: Context, active: Boolean = BeaconService.engine?.emergency == true): RemoteViews {
            // Both states open the app in emergency mode; when already active that simply shows it.
            val open = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_EMERGENCY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val layout = if (active) R.layout.widget_emergency_active else R.layout.widget_emergency_idle
            return RemoteViews(context.packageName, layout).apply {
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
        }
    }
}
```

- [ ] **Step 6: Keep the widget in sync from `BeaconService.kt`.** Import `com.da4a.smartcity.widget.EmergencyWidget`. In `onStartCommand`, after `engine.start()` add `EmergencyWidget.refresh(this)`. In `onDestroy`, after `engine = null` add `EmergencyWidget.refresh(this)`.

- [ ] **Step 7: Manifest** — inside `<application>`, after the `<service>`:
```xml
        <receiver
            android:name=".widget.EmergencyWidget"
            android:exported="true"
            android:label="@string/widget_label">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/emergency_widget_info" />
        </receiver>
```

- [ ] **Step 8: Build and test, expect PASS** — `./gradlew assembleDebug testDebugUnitTest -q`

- [ ] **Step 9: Commit** — `feat: Emergency home-screen widget`

---

### Task 7: Visual and end-to-end verification on the emulator (throwaway test, not committed)

- [ ] **Step 1:** Create `app/src/androidTest/java/com/da4a/smartcity/ScreenshotsTest.kt`, using the same `ActivityScenario` + `uiAutomation.takeScreenshot()` approach as the first UI pass. Espresso 3.5.1 cannot drive API 37. It renders the following and saves PNGs to `getExternalFilesDir("shots")`:
  - Rescuer: Searching, Nearby (Medical ID visible, Level "↑ 3 m"), Here, Signal lost. Peers use `payload.emergency = true`.
  - Emergency: starting (`running = false`), running, rescuer nearby.
  - Widget idle and active: `EmergencyWidget.views(ctx, active).apply(ctx, frame)` added to a 170×170 dp `FrameLayout` via `activity.setContentView`.
- [ ] **Step 2:** Install with `./gradlew installDebug installDebugAndroidTest`, run with `adb shell am instrument -w -e class com.da4a.smartcity.ScreenshotsTest com.da4a.smartcity.test/androidx.test.runner.AndroidJUnitRunner`, pull the PNGs to `%TEMP%\shots2`, and look at each one. Fix what looks wrong.
- [ ] **Step 3:** Real-app emergency flow on the emulator (Task 5 Step 7), plus a screenshot of the "Are you safe?" alert. Tap via `adb shell input tap`, using coordinates from `uiautomator dump`.
- [ ] **Step 4:** Delete the throwaway test, uninstall `com.da4a.smartcity.test`, kill the emulator, then `./gradlew clean assembleDebug testDebugUnitTest`.
