package com.da4a.smartcity.ui

import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.da4a.smartcity.ble.Peer
import com.da4a.smartcity.estimation.Proximity
import com.da4a.smartcity.estimation.Trend
import com.da4a.smartcity.sensors.LocationSource
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.log10

private const val PEER_STALE_MS = 10_000L

private const val HERE_M = 1.5f
private const val NEAR_M = 5f
private const val SIGNAL_LOST_MS = 2000L

// Distances at which the closeness dot is completely filled / nearly empty.
private const val DOT_FULL_M = 0.5f
private const val DOT_EMPTY_M = 30f

@Composable
fun BeaconScreen(
    deviceId: Long,
    bluetoothOn: Boolean,
    advertiserState: String,
    scannerState: String,
    hasBarometer: Boolean,
    pressurePa: Float?,
    fix: Location?,
    batteryPct: Int,
    peers: Collection<Peer>,
    rangingState: String,
    linkState: String,
    rangingEnabled: Boolean,
    onRangingEnabled: (Boolean) -> Unit,
    proximity: Map<Long, Proximity>,
    running: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ticks so "last seen" and fix age keep counting between updates.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard("Status") {
            Line("Bluetooth", if (bluetoothOn) "on" else "off")
            Line("Advertising", advertiserState)
            Line("Scanning", scannerState)
            Line("Bluetooth link", linkState)
            Line("Wi-Fi ranging", rangingState)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = rangingEnabled, onCheckedChange = onRangingEnabled)
                Text("Use Wi-Fi ranging", style = MaterialTheme.typography.bodyLarge)
            }
            Line("Barometer", if (hasBarometer) "present" else "not available")
            if (!running) {
                Button(onClick = onRetry) { Text("Start") }
            }
        }

        InfoCard("This phone · ${idText(deviceId)}") {
            Line("Pressure", pressureText(pressurePa))
            Line(
                "GPS",
                fix?.let {
                    "${coordText(it.latitude, it.longitude)}\n" +
                        "±${it.accuracy.toInt()} m · ${LocationSource.ageSeconds(it)} s ago"
                } ?: "no fix",
            )
            Line("Battery", "$batteryPct %")
        }

        if (peers.isEmpty()) {
            Text("No other phone in range yet", style = MaterialTheme.typography.bodyLarge)
        }
        for (peer in peers.sortedByDescending { it.rssi }) {
            val age = now - peer.lastSeenMs
            InfoCard(
                title = "Other phone · ${idText(peer.id)}",
                modifier = Modifier.alpha(if (age > PEER_STALE_MS) 0.4f else 1f),
            ) {
                ProximitySection(proximity[peer.id], peer, now)
                Line("Raw signal", "${peer.rssi} dBm")
                Line("Last seen", "${(age / 1000).coerceAtLeast(0)} s ago")
                val p = peer.payload
                if (p == null) {
                    Line("Data", "not received yet")
                } else {
                    Line("Pressure", pressureText(p.pressurePa?.toFloat()))
                    Line(
                        "GPS",
                        if (p.lat != null && p.lon != null) {
                            "${coordText(p.lat, p.lon)}\n${p.fixAgeMin} min ago"
                        } else {
                            "no fix"
                        },
                    )
                    Line("Battery", "${p.batteryPct} %")
                }
            }
        }
    }
}

@Composable
private fun ProximitySection(proximity: Proximity?, peer: Peer, now: Long) {
    if (proximity == null) {
        Line("Closeness", "measuring…")
        return
    }
    val d = proximity.distanceM
    val zone = when {
        d < HERE_M -> "Here"
        d < NEAR_M -> "Near"
        else -> "Far"
    }
    val silentMs = now - proximity.updatedMs
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ClosenessDot(d)
        Column {
            Text(zone, style = MaterialTheme.typography.displayMedium)
            Text(
                when (proximity.trend) {
                    Trend.CLOSER -> "▲ Getting closer"
                    Trend.FARTHER -> "▼ Moving away"
                    Trend.STEADY -> "● Steady"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
    // A short silence keeps the last reading on screen instead of resetting it.
    Line(
        "Signal",
        if (silentMs > SIGNAL_LOST_MS) "lost for ${silentMs / 1000} s, showing last reading" else "live",
    )
    Line(
        "Rough distance",
        String.format(Locale.US, "≈ %.0f m · %s", d, if (proximity.usesWifi) "Bluetooth + Wi-Fi" else "Bluetooth only"),
    )
    Line(
        "Bluetooth updates",
        String.format(
            Locale.US, "%.0f/s, longest gap %.1f s",
            peer.ratePerS, maxOf(peer.maxGapMs, now - peer.lastSeenMs) / 1000f,
        ),
    )
}

/** Filled circle inside a ring; the fill grows as the other phone gets closer. */
@Composable
private fun ClosenessDot(distanceM: Float) {
    val color = MaterialTheme.colorScheme.primary
    val closeness = (1f - log10(distanceM.coerceAtLeast(DOT_FULL_M) / DOT_FULL_M) / log10(DOT_EMPTY_M / DOT_FULL_M))
        .coerceIn(0.12f, 1f)
    Canvas(Modifier.size(96.dp)) {
        drawCircle(color, style = Stroke(width = 3.dp.toPx()))
        drawCircle(color, radius = size.minDimension / 2 * closeness)
    }
}

@Composable
private fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
}

private fun idText(id: Long) = String.format(Locale.US, "%08X", id)

private fun pressureText(pa: Float?) =
    pa?.let { String.format(Locale.US, "%.2f hPa", it / 100f) } ?: "not available"

private fun coordText(lat: Double, lon: Double) = String.format(Locale.US, "%.6f, %.6f", lat, lon)
