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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.da4a.smartcity.estimation.Proximity
import com.da4a.smartcity.estimation.Trend
import com.da4a.smartcity.ui.theme.IosLabel
import com.da4a.smartcity.ui.theme.IosRed
import com.da4a.smartcity.ui.theme.IosTertiaryFill
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
        SafeDialog(
            onKeepSearching = { confirming = false },
            onSafe = {
                confirming = false
                onSafe()
            },
        )
    }
}

/** Standard Android confirmation; stopping the SOS takes this deliberate second tap. */
@Composable
private fun SafeDialog(onKeepSearching: () -> Unit, onSafe: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepSearching,
        title = { Text("Are you safe?") },
        text = { Text("Rescuers will no longer be able to find this phone.") },
        confirmButton = {
            TextButton(onClick = onSafe) { Text("I'm safe", color = IosRed) }
        },
        dismissButton = {
            TextButton(onClick = onKeepSearching) { Text("Keep searching") }
        },
        containerColor = IosTertiaryFill,
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
