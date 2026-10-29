package com.example.idosos.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedLogicTest {
    @Test fun bthomeDecodesShellyButton() {
        val reading = BTHomeDecoder.parse(byteArrayOf(0x44, 0x00, 0x2B, 0x01, 0x64, 0x3A, 0x01))
        assertEquals(BTHomeDecoder.Reading(false, 43, 100, 1), reading)
        assertNull(BTHomeDecoder.parse(byteArrayOf(0x00)))
        assertTrue(BTHomeDecoder.parse(byteArrayOf(0x45))!!.encrypted)
    }

    @Test fun fallRequiresFreeFallThenImpactAndCooldown() {
        val detector = FallDetector()
        assertFalse(detector.update(9.8f, 5000L))
        assertFalse(detector.update(2.0f, 5100L))
        assertFalse(detector.update(2.0f, 5190L))
        assertTrue(detector.update(22.0f, 5300L))
        assertFalse(detector.update(2.0f, 5400L))
        assertFalse(detector.update(2.0f, 5500L))
        assertFalse(detector.update(22.0f, 5600L))
    }
}
