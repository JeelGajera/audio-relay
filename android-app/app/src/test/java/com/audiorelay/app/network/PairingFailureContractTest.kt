package com.audiorelay.app.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins the distinction that decides whether the app recovers or spins.
 *
 * A refused credential and a failed connection are both `IOException`s, and
 * treating them alike is what left the phone reconnecting forever with a
 * stale key while the laptop sat displaying a code nobody was asked for.
 * They must stay separately catchable.
 */
class PairingFailureContractTest {

    private fun isPermanentRefusal(e: IOException) = e is ControlChannel.PairingRejected

    @Test
    fun `a refused credential is distinguishable from a transient failure`() {
        assertTrue(isPermanentRefusal(ControlChannel.PairingRejected("proof did not verify")))
        assertTrue(!isPermanentRefusal(ControlChannel.ControlChannelException("connection reset")))
        assertTrue(!isPermanentRefusal(IOException("timeout")))
    }

    /**
     * Both must remain `IOException`s so the connection coroutine's existing
     * catch-all still contains them — the difference is in handling, not in
     * whether they escape.
     */
    @Test
    fun `both failure kinds stay catchable as IOException`() {
        val failures: List<IOException> = listOf(
            ControlChannel.PairingRejected("refused"),
            ControlChannel.ControlChannelException("dropped"),
        )
        assertTrue(failures.all { it is IOException })
    }

    /** The refusal reason is carried through, so the UI can say what happened. */
    @Test
    fun `the refusal reason survives for the user-facing message`() {
        val e = ControlChannel.PairingRejected("invalid or expired pairing code")
        assertTrue(e.message!!.contains("invalid or expired"))
    }
}
