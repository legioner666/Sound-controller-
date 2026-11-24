package com.soniccontrol.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqMathTest {
    @Test fun neverLouder() {
        val gains = listOf(6f, 3f, 0f, -2f, 0f, 0f, 0f, 0f, 9f, 0f)
        val headroom = EqMath.headroomDb(gains)
        assertEquals(9f, headroom, 0f)
        assertTrue(gains.all { it - headroom <= 0f })
        assertEquals(0f, EqMath.headroomDb(List(10) { -3f }), 0f)
    }

    @Test fun balanceOnlyAttenuates() {
        for (b in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) for (ch in 0..1) assertTrue(EqMath.balanceAttenuationDb(b, ch) <= 0f)
        assertEquals(-12f, EqMath.balanceAttenuationDb(0.5f, 0), 0.001f)
        assertEquals(0f, EqMath.balanceAttenuationDb(0.5f, 1), 0.001f)
    }

    @Test fun cutoffsAreIncreasingAndBetweenCentres() {
        val c = SystemEqualizer.BANDS_HZ.indices.map(EqMath::cutoffHz)
        assertTrue(c.zipWithNext().all { (a, b) -> a < b })
        SystemEqualizer.BANDS_HZ.dropLast(1).forEachIndexed { i, hz -> assertTrue(c[i] > hz && c[i] < SystemEqualizer.BANDS_HZ[i + 1]) }
        assertEquals(20_000f, c.last(), 0f)
    }
}
