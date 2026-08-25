package com.audiorelay.app.network

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing-code / session-key / payload-decryption logic. Mirrors
 * `desktop-app/src/protocol/crypto.rs` exactly — see `/protocol-spec.md`
 * §5 for the derivation this implements. This app only ever *decrypts*
 * (it's a receiver), so there's no `encryptPayload` here.
 *
 * Requires API 28+ for `Cipher.getInstance("ChaCha20-Poly1305")` — see the
 * `minSdk` comment in `app/build.gradle.kts`.
 */
object Crypto {
    const val SESSION_KEY_LEN = 32
    const val SESSION_ID_LEN = 8
    private const val NONCE_LEN = 12
    private const val HKDF_INFO = "audio-relay-session-v1"

    class AeadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * HKDF-SHA256(ikm = code, salt = phoneDeviceId || laptopDeviceId, info =
     * "audio-relay-session-v1") -> 32-byte session key. Must match
     * `derive_session_key` in `desktop-app/src/protocol/crypto.rs` exactly.
     */
    fun deriveSessionKey(code: String, phoneDeviceId: String, laptopDeviceId: String): ByteArray {
        val salt = (phoneDeviceId + laptopDeviceId).toByteArray(Charsets.UTF_8)
        val ikm = code.toByteArray(Charsets.UTF_8)
        return hkdfSha256(ikm, salt, HKDF_INFO.toByteArray(Charsets.UTF_8), SESSION_KEY_LEN)
    }

    /** `HMAC-SHA256(sessionKey, deviceId || nonce)`, hex-encoded — used in the REPAIR flow. */
    fun computeRepairProof(sessionKey: ByteArray, deviceId: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update(deviceId.toByteArray(Charsets.UTF_8))
        mac.update(nonce.toByteArray(Charsets.UTF_8))
        return toHex(mac.doFinal())
    }

    /**
     * `HMAC-SHA256(code, phoneDeviceId || nonce)`, hex-encoded — used in the
     * PAIR_REQUEST flow so first-time pairing never sends the code itself
     * over the network (see protocol-spec.md §5). Keyed directly by the
     * code's UTF-8 bytes; HMAC accepts any key length, so a 6-digit code is
     * a short but valid key here — same approach as
     * `desktop-app/src/protocol/crypto.rs`'s `compute_pair_proof`.
     *
     * This app only ever *sends* this proof (it's the pairing initiator),
     * never verifies one — verification, where constant-time comparison
     * actually matters, happens laptop-side.
     */
    fun computePairProof(code: String, phoneDeviceId: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(phoneDeviceId.toByteArray(Charsets.UTF_8))
        mac.update(nonce.toByteArray(Charsets.UTF_8))
        return toHex(mac.doFinal())
    }

    /**
     * Decrypts one audio packet's payload. `headerAad` is the packet's
     * 13-byte header (unencrypted, authenticated as associated data — see
     * protocol-spec.md §3.1). Throws [AeadException] on any failure
     * (wrong key, corrupted packet, replay) — callers should treat that
     * exactly like a dropped/lost packet, not a fatal error.
     */
    fun decryptPayload(
        key: ByteArray,
        sessionId: ByteArray,
        sequence: UInt,
        headerAad: ByteArray,
        ciphertext: ByteArray,
        ciphertextOffset: Int = 0,
        ciphertextLength: Int = ciphertext.size - ciphertextOffset,
    ): ByteArray {
        try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(buildNonce(sessionId, sequence)))
            cipher.updateAAD(headerAad)
            return cipher.doFinal(ciphertext, ciphertextOffset, ciphertextLength)
        } catch (e: Exception) {
            throw AeadException("failed to decrypt audio payload (seq=$sequence)", e)
        }
    }

    fun randomSessionId(): ByteArray = ByteArray(SESSION_ID_LEN).also { SecureRandom().nextBytes(it) }

    private fun buildNonce(sessionId: ByteArray, sequence: UInt): ByteArray {
        require(sessionId.size == SESSION_ID_LEN) { "session ID must be $SESSION_ID_LEN bytes" }
        val nonce = ByteArray(NONCE_LEN)
        sessionId.copyInto(nonce, 0)
        nonce[8] = (sequence.toInt() ushr 24).toByte()
        nonce[9] = (sequence.toInt() ushr 16).toByte()
        nonce[10] = (sequence.toInt() ushr 8).toByte()
        nonce[11] = sequence.toInt().toByte()
        return nonce
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val output = ByteArrayOutputStream()
        var previousBlock = ByteArray(0)
        var counter = 1
        val expandMac = Mac.getInstance("HmacSHA256")
        while (output.size() < length) {
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(previousBlock)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            previousBlock = expandMac.doFinal()
            output.write(previousBlock)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
