package com.audiorelay.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP control-channel client: connects to a laptop, runs the pairing
 * handshake, then the heartbeat loop. One instance per connection. See
 * `/protocol-spec.md` §4 for the message sequence this implements the
 * client side of, and `windows-app/src/network/control_channel.rs` for the
 * server side it talks to.
 *
 * **Unverified against a real laptop** — this has been written to match
 * the server implementation and the spec, but not yet exercised
 * end-to-end on real hardware (see `docs/roadmap.md` Phase 0).
 */
class ControlChannel(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val deviceName: String,
    private val audioPort: Int,
) {
    private lateinit var socket: Socket
    private lateinit var reader: BufferedReader
    private lateinit var writer: BufferedWriter

    data class Paired(
        val laptopDeviceId: String,
        val laptopDeviceName: String,
        val sessionId: ByteArray,
        val sessionKey: ByteArray,
        val sampleRateHz: Int,
        val channels: Int,
    )

    class ControlChannelException(message: String) : IOException(message)

    /** Connects and exchanges HELLO/HELLO_ACK. Caller decides pair vs. repair from the result. */
    suspend fun connect(): ControlMessage.HelloAck = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        socket = s
        reader = s.getInputStream().bufferedReader()
        writer = s.getOutputStream().bufferedWriter()

        send(ControlMessage.Hello(PROTOCOL_VERSION, deviceId, deviceName, audioPort))
        readNextMessage() as? ControlMessage.HelloAck
            ?: throw ControlChannelException("expected HELLO_ACK as the first reply")
    }

    /** First-time pairing: `code` is what the user typed from the laptop's UI. */
    suspend fun pairWithCode(code: String, laptopDeviceId: String, laptopDeviceName: String): Paired =
        withContext(Dispatchers.IO) {
            send(ControlMessage.PairRequest(code))
            when (val reply = readNextMessage()) {
                is ControlMessage.PairOk -> {
                    val keyHex = reply.session_key
                        ?: throw ControlChannelException("PAIR_OK missing session_key on first pair")
                    finishPairing(laptopDeviceId, laptopDeviceName, Crypto.hexToBytes(keyHex), reply.session_id)
                }
                is ControlMessage.PairFail -> throw ControlChannelException("pairing failed: ${reply.reason}")
                else -> throw ControlChannelException("unexpected message while pairing: $reply")
            }
        }

    /** Reconnect using a previously-derived key — no code re-entry. */
    suspend fun repair(laptopDeviceId: String, laptopDeviceName: String, savedKey: ByteArray, nonce: String): Paired =
        withContext(Dispatchers.IO) {
            val proof = Crypto.computeRepairProof(savedKey, deviceId, nonce)
            send(ControlMessage.Repair(deviceId, proof))
            when (val reply = readNextMessage()) {
                is ControlMessage.PairOk -> finishPairing(laptopDeviceId, laptopDeviceName, savedKey, reply.session_id)
                is ControlMessage.PairFail -> throw ControlChannelException("reconnect failed: ${reply.reason}")
                else -> throw ControlChannelException("unexpected message while reconnecting: $reply")
            }
        }

    private fun finishPairing(
        laptopDeviceId: String,
        laptopDeviceName: String,
        sessionKey: ByteArray,
        sessionIdHex: String,
    ): Paired {
        val laptopCaps = readNextMessage() as? ControlMessage.Capabilities
            ?: throw ControlChannelException("expected CAPABILITIES after PAIR_OK")
        // Ack with the same values — this app plays back whatever the
        // laptop is actually capturing (protocol-spec.md §3 makes the
        // per-packet header authoritative regardless).
        send(ControlMessage.Capabilities(laptopCaps.sample_rate, laptopCaps.channels))
        return Paired(
            laptopDeviceId = laptopDeviceId,
            laptopDeviceName = laptopDeviceName,
            sessionId = Crypto.hexToBytes(sessionIdHex),
            sessionKey = sessionKey,
            sampleRateHz = laptopCaps.sample_rate,
            channels = laptopCaps.channels,
        )
    }

    /**
     * Runs until the connection drops, 3 heartbeats are missed, or a `BYE`
     * arrives — whichever comes first. Suspends for the duration; callers
     * should launch this in its own coroutine.
     */
    suspend fun heartbeatLoop() = withContext(Dispatchers.IO) {
        var lastPongAt = System.currentTimeMillis()
        var lastPingAt = 0L
        socket.soTimeout = HEARTBEAT_INTERVAL_MS.toInt()

        while (isActive) {
            if (System.currentTimeMillis() - lastPongAt > HEARTBEAT_INTERVAL_MS * MISSED_BEATS_BEFORE_DISCONNECT) {
                return@withContext // treated as a clean disconnect, not an error
            }
            if (System.currentTimeMillis() - lastPingAt >= HEARTBEAT_INTERVAL_MS) {
                send(ControlMessage.Ping(System.currentTimeMillis()))
                lastPingAt = System.currentTimeMillis()
            }
            val line = try {
                reader.readLine()
            } catch (e: java.net.SocketTimeoutException) {
                continue // just means no data within one heartbeat interval; loop and re-check deadlines
            }
            if (line == null) return@withContext // socket closed
            when (val msg = ControlMessage.parseLine(line)) {
                is ControlMessage.Ping -> send(ControlMessage.Pong(msg.t))
                is ControlMessage.Pong -> lastPongAt = System.currentTimeMillis()
                is ControlMessage.Bye -> return@withContext
                else -> {} // ignore anything else on the heartbeat loop
            }
        }
    }

    suspend fun sendBye() = withContext(Dispatchers.IO) {
        runCatching { send(ControlMessage.Bye) }
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun send(message: ControlMessage) {
        writer.write(message.toLine())
        writer.flush()
    }

    /** Reads lines until a recognized message is parsed, or the stream ends. */
    private fun readNextMessage(): ControlMessage {
        while (true) {
            val line = reader.readLine() ?: throw ControlChannelException("connection closed unexpectedly")
            val msg = ControlMessage.parseLine(line) ?: continue
            return msg
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HEARTBEAT_INTERVAL_MS = 1_000L
        private const val MISSED_BEATS_BEFORE_DISCONNECT = 3
    }
}
