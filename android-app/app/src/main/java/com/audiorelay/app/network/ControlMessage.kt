package com.audiorelay.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TCP control-channel messages. Mirrors
 * `windows-app/src/protocol/control.rs` field-for-field — see
 * `/protocol-spec.md` §4 for the state machine this implements the client
 * side of.
 */
@Serializable
sealed class ControlMessage {

    @Serializable
    @SerialName("HELLO")
    data class Hello(
        val protocol_version: Int,
        val device_id: String,
        val device_name: String,
        val audio_port: Int,
    ) : ControlMessage()

    @Serializable
    @SerialName("HELLO_ACK")
    data class HelloAck(
        val protocol_version: Int,
        val device_id: String,
        val device_name: String,
        val paired: Boolean,
        val nonce: String? = null,
    ) : ControlMessage()

    @Serializable
    @SerialName("PAIR_REQUEST")
    data class PairRequest(val code: String) : ControlMessage()

    @Serializable
    @SerialName("REPAIR")
    data class Repair(val device_id: String, val proof: String) : ControlMessage()

    @Serializable
    @SerialName("PAIR_OK")
    data class PairOk(val session_id: String, val session_key: String? = null) : ControlMessage()

    @Serializable
    @SerialName("PAIR_FAIL")
    data class PairFail(val reason: String) : ControlMessage()

    @Serializable
    @SerialName("CAPABILITIES")
    data class Capabilities(val sample_rate: Int, val channels: Int) : ControlMessage()

    @Serializable
    @SerialName("PING")
    data class Ping(val t: Long) : ControlMessage()

    @Serializable
    @SerialName("PONG")
    data class Pong(val t: Long) : ControlMessage()

    @Serializable
    @SerialName("BYE")
    object Bye : ControlMessage()

    /** Serializes to one newline-terminated JSON line, ready to write to the socket. */
    fun toLine(): String = json.encodeToString(serializer(), this) + "\n"

    companion object {
        private val json = Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

        /**
         * Parses one line. Returns null for a blank line, malformed JSON,
         * or a `type` this version doesn't recognize — per
         * protocol-spec.md §6, unknown types must be ignored, not treated
         * as a fatal error.
         */
        fun parseLine(line: String): ControlMessage? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            return try {
                json.decodeFromString(serializer(), trimmed)
            } catch (e: Exception) {
                null
            }
        }
    }
}
