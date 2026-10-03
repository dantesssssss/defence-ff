package com.da4a.smartcity.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.da4a.smartcity.ble.BeaconPayload
import com.da4a.smartcity.ble.Peer
import com.da4a.smartcity.estimation.Proximity
import com.da4a.smartcity.estimation.Trend
import com.da4a.smartcity.estimation.heightAboveM
import com.da4a.smartcity.estimation.levelText
import com.da4a.smartcity.ui.theme.IosBackground
import com.da4a.smartcity.ui.theme.IosBlue
import com.da4a.smartcity.ui.theme.IosGreen
import com.da4a.smartcity.ui.theme.IosLabel
import com.da4a.smartcity.ui.theme.IosOrange
import com.da4a.smartcity.ui.theme.IosRed
import com.da4a.smartcity.ui.theme.IosSecondaryBackground
import com.da4a.smartcity.ui.theme.IosSecondaryLabel
import com.da4a.smartcity.ui.theme.SmartCityTheme
import kotlinx.coroutines.delay
import java.util.Locale

private const val HERE_M = 1.5f
private const val NEAR_M = 5f

// Short gaps are normal and keep the last reading; this long without one, the link is gone.
private const val SIGNAL_LOST_MS = 5_000L

private const val SEARCHING_CLOSENESS = 0.3f

@Composable
fun BeaconScreen(
    ownPressurePa: Float?,
    peers: Collection<Peer>,
    proximity: Map<Long, Proximity>,
    running: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ticks so a silent peer turns into "Signal lost" without waiting for another update.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    // Only phones whose owner asked for help are searched for.
    val peer = peers.filter { it.payload?.emergency == true }.maxByOrNull { it.rssi }
    val prox = peer?.let { proximity[it.id] }
    val distance = prox?.distanceM
    val lost = prox != null && now - prox.updatedMs > SIGNAL_LOST_MS
    val here = distance != null && distance < HERE_M && !lost

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(here) {
        if (here) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val background by animateColorAsState(if (here) IosGreen else IosBackground, tween(500), label = "background")
    // Cards turn into frosted glass on the green background, and grey text would vanish on it.
    val surface by animateColorAsState(
        if (here) Color.White.copy(alpha = 0.2f) else IosSecondaryBackground, tween(500), label = "surface",
    )
    val secondary by animateColorAsState(
        if (here) Color.White.copy(alpha = 0.85f) else IosSecondaryLabel, tween(500), label = "secondary",
    )
    // The red caption would clash with the green background.
    val accent by animateColorAsState(if (here) IosLabel else IosRed, tween(500), label = "accent")

    CompositionLocalProvider(LocalContentColor provides IosLabel) {
        Column(
            modifier
                .fillMaxSize()
                .background(background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            if (!running) {
                StartPrompt(onRetry)
            } else {
                val title = when {
                    distance == null -> "Searching…"
                    distance < HERE_M -> "Here"
                    distance < NEAR_M -> "Nearby"
                    else -> "Far"
                }
                val subtitle = when {
                    prox == null -> "Looking for people who need help"
                    lost -> "Signal lost"
                    here -> ""
                    else -> when (prox.trend) {
                        Trend.CLOSER -> "Getting closer"
                        Trend.FARTHER -> "Moving away"
                        Trend.STEADY -> ""
                    }
                }
                val style = when {
                    distance == null -> CircleStyle.SEARCHING
                    lost -> CircleStyle.LOST
                    distance < HERE_M -> CircleStyle.HERE
                    distance < NEAR_M -> CircleStyle.NEARBY
                    else -> CircleStyle.FAR
                }
                val closeness = if (here) 1f else distance?.let(::closenessOf) ?: SEARCHING_CLOSENESS
                // Laid out from the start but invisible, so the circle does not jump when they appear.
                val detailsAlpha by animateFloatAsState(if (prox != null) 1f else 0f, tween(500), label = "details")

                AnimatedVisibility(
                    visible = prox != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    MedicalIdCard(DemoVictim, surface, secondary, accent, Modifier.padding(bottom = 16.dp))
                }
                Header(title, subtitle, if (lost) IosOrange else secondary)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ProximityCircle(closeness, style)
                }
                InfoTiles(peer?.payload, ownPressurePa, peer?.id, surface, secondary, Modifier.alpha(detailsAlpha))
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, subtitleColor: Color) {
    Column(Modifier.fillMaxWidth()) {
        AnimatedContent(
            title,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
            label = "title",
        ) {
            Text(it, style = MaterialTheme.typography.displaySmall)
        }
        // An empty subtitle still takes its line, so the layout never shifts.
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = subtitleColor)
    }
}

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

/** Numbers use tabular figures so they do not wobble as they change. */
@Composable
private fun Value(text: String, style: TextStyle = MaterialTheme.typography.titleLarge, color: Color = IosLabel) {
    Text(text, style = style.copy(fontFeatureSettings = "tnum"), color = color, maxLines = 1, softWrap = false)
}

/** The iOS status-bar battery: outline, nub, and a fill that turns red at 20 %. */
@Composable
private fun BatteryGlyph(pct: Int) {
    Canvas(Modifier.size(width = 25.dp, height = 12.dp)) {
        val outline = IosLabel.copy(alpha = 0.4f)
        val stroke = 1.dp.toPx()
        val nub = 2.dp.toPx()
        val bodyWidth = size.width - nub - stroke
        drawRoundRect(
            outline,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(bodyWidth - stroke, size.height - stroke),
            cornerRadius = CornerRadius(3.5.dp.toPx()),
            style = Stroke(stroke),
        )
        val inset = 2.dp.toPx()
        drawRoundRect(
            if (pct <= 20) IosRed else IosLabel,
            topLeft = Offset(inset, inset),
            size = Size((bodyWidth - 2 * inset) * pct.coerceIn(0, 100) / 100f, size.height - 2 * inset),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        drawRoundRect(
            outline,
            topLeft = Offset(bodyWidth + stroke, size.height * 0.33f),
            size = Size(nub, size.height * 0.34f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

@Composable
private fun StartPrompt(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ready to find", style = MaterialTheme.typography.displaySmall)
        Text(
            "Allow Bluetooth to start",
            style = MaterialTheme.typography.bodyLarge,
            color = IosSecondaryLabel,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )
        Text(
            "Start",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clip(CircleShape)
                .background(IosBlue)
                .clickable(onClick = onRetry)
                .padding(horizontal = 36.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun PreviewScreen(distanceM: Float?, trend: Trend = Trend.STEADY, silentMs: Long = 0, running: Boolean = true) {
    val now = SystemClock.elapsedRealtime()
    val payload = BeaconPayload(
        deviceId = 1, batteryPct = 82, pressurePa = 100_840, lat = 52.2297, lon = 21.0122, fixAgeMin = 0, seq = 0,
        emergency = true,
    )
    val proximity = distanceM?.let { mapOf(1L to Proximity(it, false, -60f, trend, now - silentMs)) } ?: emptyMap()
    SmartCityTheme {
        BeaconScreen(
            ownPressurePa = 100_876f,
            peers = listOf(Peer(1, -60, payload, now)),
            proximity = proximity,
            running = running,
            onRetry = {},
        )
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun StartPreview() = PreviewScreen(distanceM = null, running = false)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun SearchingPreview() = PreviewScreen(distanceM = null)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun FarPreview() = PreviewScreen(distanceM = 14f, trend = Trend.FARTHER)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun NearbyPreview() = PreviewScreen(distanceM = 3f, trend = Trend.CLOSER)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun HerePreview() = PreviewScreen(distanceM = 0.8f)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun SignalLostPreview() = PreviewScreen(distanceM = 6f, silentMs = 8_000)
