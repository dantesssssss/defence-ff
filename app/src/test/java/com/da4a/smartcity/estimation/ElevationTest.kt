package com.da4a.smartcity.estimation

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationTest {

    @Test
    fun lowerPressureOnTheOtherPhoneMeansItIsAbove() {
        // 36 Pa is roughly one storey.
        assertEquals(3.0f, heightAboveM(ownPa = 100_000f, peerPa = 99_964f), 0.05f)
    }

    @Test
    fun higherPressureOnTheOtherPhoneMeansItIsBelow() {
        assertEquals(-3.0f, heightAboveM(ownPa = 99_964f, peerPa = 100_000f), 0.05f)
    }

    @Test
    fun offsetCancelsTheBiasBetweenTwoBarometers() {
        // Side by side the two sensors disagree by 50 Pa; levelling stores that difference.
        val offset = 100_050f - 100_000f
        assertEquals(0f, heightAboveM(100_050f, 100_000f, offset), 0.001f)
        // The other phone is then carried one storey up.
        assertEquals(3.0f, heightAboveM(100_050f, 99_964f, offset), 0.05f)
    }

    @Test
    fun smallDifferencesReadAsSameLevel() {
        assertEquals("Same level", elevationLabel(0f))
        assertEquals("Same level", elevationLabel(1.4f))
        assertEquals("Same level", elevationLabel(-1.4f))
    }

    @Test
    fun labelsRoundToWholeMetres() {
        assertEquals("↑ 3 m above you", elevationLabel(2.988f))
        assertEquals("↓ 2 m below you", elevationLabel(-2.4f))
        assertEquals("↑ 2 m above you", elevationLabel(1.6f))
    }
}
