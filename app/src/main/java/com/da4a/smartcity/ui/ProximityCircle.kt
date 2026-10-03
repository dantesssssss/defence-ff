package com.da4a.smartcity.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.da4a.smartcity.ui.theme.IosGray
import com.da4a.smartcity.ui.theme.IosGreen
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

enum class CircleStyle { SEARCHING, FAR, NEARBY, HERE, LOST }

// Distances at which the circle is completely full / at its smallest.
private const val FULL_M = 0.5f
private const val EMPTY_M = 30f
private const val MIN_CLOSENESS = 0.15f

private const val PULSE_MS = 1800
private const val SPIN_MS = 60_000
private const val DOTS = 28

private val DotColor = Color.White.copy(alpha = 0.22f)

/** 1 at half a metre, shrinking logarithmically to a small dot at 30 m. */
fun closenessOf(distanceM: Float): Float =
    (1f - log10(distanceM.coerceAtLeast(FULL_M) / FULL_M) / log10(EMPTY_M / FULL_M)).coerceIn(MIN_CLOSENESS, 1f)

/**
 * Find My-style closeness circle: it grows as the other phone gets closer, rings pulse out
 * from its edge, and a faint ring of slowly turning dots marks the largest size.
 */
@Composable
fun ProximityCircle(closeness: Float, style: CircleStyle, modifier: Modifier = Modifier) {
    // A soft spring turns the jittery Bluetooth estimate into a smooth breathe.
    val scale by animateFloatAsState(
        closeness, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow), label = "scale",
    )
    val fill by animateColorAsState(fillOf(style), tween(600), label = "fill")
    val edge by animateColorAsState(edgeOf(style), tween(600), label = "edge")
    val transition = rememberInfiniteTransition(label = "ambient")
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing)), label = "pulse",
    )
    val spin by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin",
    )

    Canvas(modifier.aspectRatio(1f)) {
        val outer = size.minDimension / 2
        val radius = outer * 0.78f * scale

        val dotRadius = 1.5.dp.toPx()
        rotate(spin) {
            for (i in 0 until DOTS) {
                val angle = 2 * PI * i / DOTS
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawCircle(DotColor, dotRadius, center + direction * (outer - dotRadius))
            }
        }
        // Two rings half a period apart, each expanding from the edge to the dots and fading.
        for (k in 0..1) {
            val t = (pulse + k * 0.5f) % 1f
            drawCircle(
                edge.copy(alpha = edge.alpha * 0.5f * (1f - t)),
                radius + (outer - radius) * t,
                style = Stroke(1.5.dp.toPx()),
            )
        }
        drawCircle(fill, radius)
        drawCircle(edge, radius, style = Stroke(2.dp.toPx()))
    }
}

private fun fillOf(style: CircleStyle) = when (style) {
    CircleStyle.SEARCHING, CircleStyle.LOST -> IosGray.copy(alpha = 0.2f)
    CircleStyle.FAR -> Color.White.copy(alpha = 0.15f)
    CircleStyle.NEARBY -> IosGreen
    CircleStyle.HERE -> Color.White.copy(alpha = 0.25f)
}

private fun edgeOf(style: CircleStyle) = when (style) {
    CircleStyle.SEARCHING, CircleStyle.LOST -> IosGray
    CircleStyle.FAR -> Color.White.copy(alpha = 0.7f)
    CircleStyle.NEARBY -> IosGreen
    CircleStyle.HERE -> Color.White
}
