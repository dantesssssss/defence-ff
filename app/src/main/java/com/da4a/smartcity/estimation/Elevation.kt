package com.da4a.smartcity.estimation

import kotlin.math.abs
import kotlin.math.roundToInt

/** Height change per pascal near the ground: 1 / (air density 1.2 kg/m³ × g). */
const val M_PER_PA = 0.083f

/** Below this the difference is within barometer noise and reads as the same level. */
const val SAME_LEVEL_M = 1.5f

/**
 * Height of the other phone above this one, in metres; negative when it is below.
 *
 * Two phones' barometers disagree by up to a few hPa, so [offsetPa] is the difference
 * (own − other) measured once with the phones held side by side.
 */
fun heightAboveM(ownPa: Float, peerPa: Float, offsetPa: Float = 0f): Float =
    (ownPa - peerPa - offsetPa) * M_PER_PA

fun elevationLabel(heightM: Float): String = when {
    abs(heightM) < SAME_LEVEL_M -> "Same level"
    heightM > 0 -> "↑ ${heightM.roundToInt()} m above you"
    else -> "↓ ${(-heightM).roundToInt()} m below you"
}
