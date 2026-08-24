package com.audiorelay.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun `first attempt retries quickly`() {
        assertEquals(ReconnectBackoff.INITIAL_DELAY_MS, ReconnectBackoff.delayMsFor(0))
    }

    @Test
    fun `delay doubles with each attempt`() {
        assertEquals(1_000L, ReconnectBackoff.delayMsFor(0))
        assertEquals(2_000L, ReconnectBackoff.delayMsFor(1))
        assertEquals(4_000L, ReconnectBackoff.delayMsFor(2))
        assertEquals(8_000L, ReconnectBackoff.delayMsFor(3))
        assertEquals(16_000L, ReconnectBackoff.delayMsFor(4))
    }

    @Test
    fun `delay is capped so a vanished laptop never backs off indefinitely`() {
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayMsFor(5))
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayMsFor(50))
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayMsFor(Int.MAX_VALUE))
    }

    /**
     * `1000L shl 64` would wrap around to 1000 rather than saturating, so the
     * shift has to be clamped before it is applied, not after.
     */
    @Test
    fun `large attempt counts cannot wrap the shift back to a tiny delay`() {
        for (attempt in intArrayOf(31, 32, 33, 63, 64, 65, 1000, Int.MAX_VALUE)) {
            assertEquals(
                "attempt $attempt should sit at the ceiling",
                ReconnectBackoff.MAX_DELAY_MS,
                ReconnectBackoff.delayMsFor(attempt),
            )
        }
    }

    @Test
    fun `negative attempts are clamped rather than producing a nonsense delay`() {
        assertEquals(ReconnectBackoff.INITIAL_DELAY_MS, ReconnectBackoff.delayMsFor(-1))
        assertEquals(ReconnectBackoff.INITIAL_DELAY_MS, ReconnectBackoff.delayMsFor(Int.MIN_VALUE))
    }

    @Test
    fun `every delay stays within sane bounds`() {
        for (attempt in 0..100) {
            val delay = ReconnectBackoff.delayMsFor(attempt)
            assertTrue("attempt $attempt produced $delay", delay >= ReconnectBackoff.INITIAL_DELAY_MS)
            assertTrue("attempt $attempt produced $delay", delay <= ReconnectBackoff.MAX_DELAY_MS)
        }
    }
}
