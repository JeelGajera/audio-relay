package com.audiorelay.app.service

/**
 * Exponential backoff schedule for automatic reconnect attempts
 * (docs/roadmap.md Phase 4).
 *
 * Doubling from one second up to a thirty-second ceiling: quick enough that
 * a brief blip recovers almost immediately, bounded so a laptop that has
 * genuinely gone away doesn't leave the phone retrying in a tight loop for
 * hours. The ceiling matters more than the curve — this runs inside a
 * foreground service holding a wake lock.
 *
 * Deliberately kept free of Android dependencies so it can be unit-tested on
 * a plain JVM, unlike the rest of [RelayService].
 */
object ReconnectBackoff {
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /** 1s doubled five times is 32s, already past the ceiling. */
    private const val MAX_SHIFT = 5

    /**
     * Delay before attempt number [attempt], counting from zero. Negative
     * values are clamped rather than rejected — a caller getting its
     * bookkeeping wrong should retry promptly, not crash the service.
     */
    fun delayMsFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        return (INITIAL_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}
