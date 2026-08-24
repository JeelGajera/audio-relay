package com.audiorelay.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMessageTest {

    @Test
    fun `hello round trips`() {
        val msg = ControlMessage.Hello(
            protocol_version = 1,
            device_id = "abc-123",
            device_name = "Pixel 9",
            audio_port = 45000,
        )
        val line = msg.toLine()
        assertTrue(line.endsWith("\n"))
        assertEquals(msg, ControlMessage.parseLine(line))
    }

    @Test
    fun `bye has no fields but still round trips`() {
        val line = ControlMessage.Bye.toLine()
        assertEquals(ControlMessage.Bye, ControlMessage.parseLine(line))
    }

    @Test
    fun `unknown type is ignored not erroring`() {
        assertNull(ControlMessage.parseLine("""{"type":"SOMETHING_FROM_THE_FUTURE"}"""))
    }

    @Test
    fun `missing type is treated as unparseable, not a crash`() {
        assertNull(ControlMessage.parseLine("""{"foo":"bar"}"""))
    }

    @Test
    fun `blank line is ignored`() {
        assertNull(ControlMessage.parseLine(""))
        assertNull(ControlMessage.parseLine("   \n"))
    }

    @Test
    fun `pair_ok omits session_key on repair flow`() {
        val msg = ControlMessage.PairOk(session_id = "deadbeef", session_key = null)
        val line = msg.toLine()
        assertTrue(!line.contains("session_key"))
    }

    @Test
    fun `extra unknown fields in a known message are ignored`() {
        val line = """{"type":"PING","t":123,"unexpected_new_field":"x"}"""
        assertEquals(ControlMessage.Ping(123), ControlMessage.parseLine(line))
    }

    @Test
    fun `hello_ack with nonce round trips`() {
        val msg = ControlMessage.HelloAck(
            protocol_version = 1,
            device_id = "laptop-1",
            device_name = "DESKTOP-A1B2C3",
            paired = true,
            nonce = "abc123",
        )
        val line = msg.toLine()
        assertEquals(msg, ControlMessage.parseLine(line))
    }
}
