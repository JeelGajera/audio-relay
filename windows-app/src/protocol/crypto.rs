//! Pairing-code generation, session-key derivation, reconnect proof, and
//! per-packet payload encryption. See `/protocol-spec.md` §5 for the
//! rationale and exact derivation inputs — this is a direct implementation
//! of that section, nothing more.

use chacha20poly1305::{
    aead::{Aead, KeyInit},
    ChaCha20Poly1305, Key, Nonce,
};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::Sha256;
use subtle::ConstantTimeEq;
use thiserror::Error;

pub const SESSION_KEY_LEN: usize = 32;
pub const SESSION_ID_LEN: usize = 8;
const NONCE_LEN: usize = 12; // session_id (8) || sequence (4)

pub type SessionKey = [u8; SESSION_KEY_LEN];
pub type SessionId = [u8; SESSION_ID_LEN];

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("encryption/decryption failed (wrong key, corrupted packet, or replay)")]
    AeadFailure,
}

/// A cryptographically random 6-digit pairing code, e.g. "042817".
/// Zero-padded — always exactly 6 ASCII digits.
pub fn generate_pairing_code() -> String {
    let mut rng = rand::thread_rng();
    let n = rng.next_u32() % 1_000_000;
    format!("{n:06}")
}

/// A fresh random session ID, minted once per connection and used as half
/// of the AEAD nonce (protocol-spec.md §3.1). Must never be reused with the
/// same session key.
pub fn generate_session_id() -> SessionId {
    let mut id = [0u8; SESSION_ID_LEN];
    rand::thread_rng().fill_bytes(&mut id);
    id
}

/// HKDF-SHA256(ikm = code, salt = phone_id || laptop_id, info =
/// "audio-relay-session-v1") -> 32-byte session key.
pub fn derive_session_key(code: &str, phone_device_id: &str, laptop_device_id: &str) -> SessionKey {
    let mut salt = Vec::with_capacity(phone_device_id.len() + laptop_device_id.len());
    salt.extend_from_slice(phone_device_id.as_bytes());
    salt.extend_from_slice(laptop_device_id.as_bytes());

    let hk = Hkdf::<Sha256>::new(Some(&salt), code.as_bytes());
    let mut key = [0u8; SESSION_KEY_LEN];
    hk.expand(b"audio-relay-session-v1", &mut key)
        .expect("32 bytes is a valid HKDF-SHA256 output length");
    key
}

/// `HMAC-SHA256(key, msg_parts.concat())`, raw 32-byte output. Shared by
/// both proof flavors below — see protocol-spec.md §5.
fn hmac_sha256(key: &[u8], msg_parts: &[&[u8]]) -> [u8; 32] {
    let mut mac: Hmac<Sha256> = Mac::new_from_slice(key).expect("HMAC accepts any key length");
    for part in msg_parts {
        mac.update(part);
    }
    mac.finalize().into_bytes().into()
}

/// Compares a received hex-encoded proof against the expected raw bytes in
/// constant time — proof verification gates authentication (first pairing
/// and reconnect alike), so this must not leak timing information about how
/// many leading bytes matched. Malformed hex (wrong length, non-hex chars)
/// is treated as a mismatch, decoded before any secret-dependent branching.
fn proof_matches(expected: &[u8; 32], proof_hex: &str) -> bool {
    match hex_decode(proof_hex) {
        Ok(bytes) if bytes.len() == expected.len() => bool::from(expected.ct_eq(&bytes)),
        _ => false,
    }
}

/// `proof = HMAC-SHA256(persisted_key, device_id || nonce)`, hex-encoded.
/// Used in the `REPAIR` flow so a reconnecting device can prove it holds
/// the previously-derived session key without ever resending the key
/// itself. See protocol-spec.md §4.2.
///
/// Not called by this laptop-side binary (which only ever *verifies* a
/// received proof) — kept as the reference implementation of the phone
/// side, exercised by the tests below and mirrored in
/// `android-app/.../network/Crypto.kt`.
#[allow(dead_code)]
pub fn compute_repair_proof(session_key: &SessionKey, device_id: &str, nonce: &str) -> String {
    hex_encode(hmac_sha256(
        session_key,
        &[device_id.as_bytes(), nonce.as_bytes()],
    ))
}

pub fn verify_repair_proof(
    session_key: &SessionKey,
    device_id: &str,
    nonce: &str,
    proof_hex: &str,
) -> bool {
    let expected = hmac_sha256(session_key, &[device_id.as_bytes(), nonce.as_bytes()]);
    proof_matches(&expected, proof_hex)
}

/// `proof = HMAC-SHA256(code, phone_device_id || nonce)`, hex-encoded. Used
/// in the `PAIR_REQUEST` flow so first-time pairing never puts the code
/// itself on the wire — see protocol-spec.md §5. Keyed directly by the
/// code's UTF-8 bytes (HMAC accepts any key length; a 6-digit code is a
/// short but perfectly valid HMAC key here).
///
/// Not called by this laptop-side binary (which only ever *verifies* a
/// received proof, via `AppState::verify_pairing_proof`) — kept as the
/// reference implementation of the phone side, exercised by the tests
/// below and mirrored in `android-app/.../network/Crypto.kt`.
#[allow(dead_code)]
pub fn compute_pair_proof(code: &str, phone_device_id: &str, nonce: &str) -> String {
    hex_encode(hmac_sha256(
        code.as_bytes(),
        &[phone_device_id.as_bytes(), nonce.as_bytes()],
    ))
}

pub fn verify_pair_proof(code: &str, phone_device_id: &str, nonce: &str, proof_hex: &str) -> bool {
    let expected = hmac_sha256(
        code.as_bytes(),
        &[phone_device_id.as_bytes(), nonce.as_bytes()],
    );
    proof_matches(&expected, proof_hex)
}

fn build_nonce(session_id: &SessionId, sequence: u32) -> Nonce {
    let mut bytes = [0u8; NONCE_LEN];
    bytes[..SESSION_ID_LEN].copy_from_slice(session_id);
    bytes[SESSION_ID_LEN..].copy_from_slice(&sequence.to_be_bytes());
    Nonce::from(bytes)
}

/// Encrypts an audio packet's PCM payload in place, returning the
/// ciphertext + 16-byte Poly1305 tag appended. The 12-byte packet header is
/// authenticated as associated data but left unencrypted, so a receiver can
/// still read `sequence`/`timestamp_ms` even if authentication later fails.
pub fn encrypt_payload(
    key: &SessionKey,
    session_id: &SessionId,
    sequence: u32,
    header_aad: &[u8],
    plaintext_payload: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    let nonce = build_nonce(session_id, sequence);
    cipher
        .encrypt(
            &nonce,
            chacha20poly1305::aead::Payload {
                msg: plaintext_payload,
                aad: header_aad,
            },
        )
        .map_err(|_| CryptoError::AeadFailure)
}

// Not called by this sender-only binary; kept as the reference implementation
// of the receive-side of protocol-spec.md §3.1 and exercised by the tests
// below, alongside encrypt_payload.
#[allow(dead_code)]
pub fn decrypt_payload(
    key: &SessionKey,
    session_id: &SessionId,
    sequence: u32,
    header_aad: &[u8],
    ciphertext_payload: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    let nonce = build_nonce(session_id, sequence);
    cipher
        .decrypt(
            &nonce,
            chacha20poly1305::aead::Payload {
                msg: ciphertext_payload,
                aad: header_aad,
            },
        )
        .map_err(|_| CryptoError::AeadFailure)
}

mod hex {
    pub fn encode(bytes: impl AsRef<[u8]>) -> String {
        bytes.as_ref().iter().map(|b| format!("{b:02x}")).collect()
    }

    pub fn decode(s: &str) -> Result<Vec<u8>, String> {
        if !s.len().is_multiple_of(2) {
            return Err("odd-length hex string".into());
        }
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|e| e.to_string()))
            .collect()
    }
}

pub use hex::{decode as hex_decode, encode as hex_encode};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pairing_code_is_six_digits() {
        for _ in 0..1000 {
            let code = generate_pairing_code();
            assert_eq!(code.len(), 6);
            assert!(code.chars().all(|c| c.is_ascii_digit()));
        }
    }

    #[test]
    fn session_ids_are_not_all_zero_and_differ() {
        let a = generate_session_id();
        let b = generate_session_id();
        assert_ne!(a, [0u8; SESSION_ID_LEN]);
        assert_ne!(a, b, "two consecutive session IDs collided — RNG broken");
    }

    #[test]
    fn same_inputs_derive_same_key() {
        let k1 = derive_session_key("042817", "phone-1", "laptop-1");
        let k2 = derive_session_key("042817", "phone-1", "laptop-1");
        assert_eq!(k1, k2);
    }

    #[test]
    fn different_code_derives_different_key() {
        let k1 = derive_session_key("042817", "phone-1", "laptop-1");
        let k2 = derive_session_key("999999", "phone-1", "laptop-1");
        assert_ne!(k1, k2);
    }

    #[test]
    fn different_device_pair_derives_different_key() {
        let k1 = derive_session_key("042817", "phone-1", "laptop-1");
        let k2 = derive_session_key("042817", "phone-2", "laptop-1");
        assert_ne!(k1, k2);
    }

    #[test]
    fn repair_proof_round_trips() {
        let key = derive_session_key("042817", "phone-1", "laptop-1");
        let proof = compute_repair_proof(&key, "phone-1", "some-nonce");
        assert!(verify_repair_proof(&key, "phone-1", "some-nonce", &proof));
    }

    #[test]
    fn repair_proof_rejects_wrong_key() {
        let key = derive_session_key("042817", "phone-1", "laptop-1");
        let wrong_key = derive_session_key("111111", "phone-1", "laptop-1");
        let proof = compute_repair_proof(&key, "phone-1", "some-nonce");
        assert!(!verify_repair_proof(
            &wrong_key,
            "phone-1",
            "some-nonce",
            &proof
        ));
    }

    #[test]
    fn pair_proof_round_trips() {
        let proof = compute_pair_proof("042817", "phone-1", "some-nonce");
        assert!(verify_pair_proof("042817", "phone-1", "some-nonce", &proof));
    }

    #[test]
    fn pair_proof_rejects_wrong_code() {
        let proof = compute_pair_proof("042817", "phone-1", "some-nonce");
        assert!(!verify_pair_proof(
            "999999",
            "phone-1",
            "some-nonce",
            &proof
        ));
    }

    #[test]
    fn pair_proof_rejects_wrong_nonce() {
        let proof = compute_pair_proof("042817", "phone-1", "some-nonce");
        assert!(!verify_pair_proof(
            "042817",
            "phone-1",
            "different-nonce",
            &proof
        ));
    }

    #[test]
    fn pair_proof_does_not_leak_the_code_itself() {
        let proof = compute_pair_proof("042817", "phone-1", "some-nonce");
        assert!(!proof.contains("042817"));
    }

    #[test]
    fn proof_verification_rejects_malformed_hex() {
        assert!(!verify_pair_proof(
            "042817",
            "phone-1",
            "some-nonce",
            "not-hex!!"
        ));
        assert!(!verify_pair_proof("042817", "phone-1", "some-nonce", "abc")); // odd length
        assert!(!verify_pair_proof("042817", "phone-1", "some-nonce", "")); // wrong length
    }

    #[test]
    fn payload_round_trips_through_encrypt_decrypt() {
        let key = derive_session_key("042817", "phone-1", "laptop-1");
        let session_id = generate_session_id();
        let header = [0u8; 12];
        let plaintext = b"pretend this is 480 stereo samples";

        let ciphertext = encrypt_payload(&key, &session_id, 7, &header, plaintext).unwrap();
        assert_ne!(ciphertext, plaintext);

        let decrypted = decrypt_payload(&key, &session_id, 7, &header, &ciphertext).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn decrypt_fails_with_wrong_sequence_in_nonce() {
        let key = derive_session_key("042817", "phone-1", "laptop-1");
        let session_id = generate_session_id();
        let header = [0u8; 12];
        let ciphertext = encrypt_payload(&key, &session_id, 7, &header, b"hello").unwrap();

        assert!(decrypt_payload(&key, &session_id, 8, &header, &ciphertext).is_err());
    }

    #[test]
    fn decrypt_fails_with_tampered_ciphertext() {
        let key = derive_session_key("042817", "phone-1", "laptop-1");
        let session_id = generate_session_id();
        let header = [0u8; 12];
        let mut ciphertext = encrypt_payload(&key, &session_id, 7, &header, b"hello").unwrap();
        let last = ciphertext.len() - 1;
        ciphertext[last] ^= 0xFF;

        assert!(decrypt_payload(&key, &session_id, 7, &header, &ciphertext).is_err());
    }

    #[test]
    fn decrypt_fails_with_tampered_header_aad() {
        let key = derive_session_key("042817", "phone-1", "laptop-1");
        let session_id = generate_session_id();
        let header = [0u8; 12];
        let mut other_header = [0u8; 12];
        other_header[0] = 1;
        let ciphertext = encrypt_payload(&key, &session_id, 7, &header, b"hello").unwrap();

        assert!(decrypt_payload(&key, &session_id, 7, &other_header, &ciphertext).is_err());
    }

    #[test]
    fn hex_round_trips() {
        let bytes = [0xDEu8, 0xAD, 0xBE, 0xEF];
        let s = hex_encode(bytes);
        assert_eq!(s, "deadbeef");
        assert_eq!(hex_decode(&s).unwrap(), bytes.to_vec());
    }
}

#[cfg(test)]
mod vector_gen {
    use super::*;

    /// Not a real test — prints a known-answer vector (`cargo test
    /// print_known_answer_vector -- --nocapture`) used to generate the
    /// hardcoded values in both `known_vector_matches_android_side` below
    /// and `android-app/.../test/.../CryptoTest.kt`'s
    /// `decryptPayload matches the windows-app reference vector` test.
    /// Keep this around — regenerate + update both sides together if the
    /// derivation ever changes.
    #[test]
    #[ignore]
    fn print_known_answer_vector() {
        let key = derive_session_key("042817", "phone-device-id", "laptop-device-id");
        let session_id: SessionId = *b"12345678";
        let header: [u8; 13] = [0, 0, 0, 0, 7, 0, 0, 0, 0x3e, 8, 1, 2, 0];
        let plaintext = b"cross-implementation test vector";
        let ciphertext = encrypt_payload(&key, &session_id, 7, &header, plaintext).unwrap();

        eprintln!("key_hex       = {}", hex_encode(key));
        eprintln!("session_id_hex= {}", hex_encode(session_id));
        eprintln!("header_hex    = {}", hex_encode(header));
        eprintln!("ciphertext_hex= {}", hex_encode(&ciphertext));
    }

    /// The actual regression test: same fixed inputs as the generator
    /// above, asserting the known ciphertext. This is the Rust side of a
    /// cross-implementation check — the Kotlin test decrypts this exact
    /// ciphertext and asserts it recovers the same plaintext.
    #[test]
    fn known_vector_matches_android_side() {
        let key = derive_session_key("042817", "phone-device-id", "laptop-device-id");
        let session_id: SessionId = *b"12345678";
        let header: [u8; 13] = [0, 0, 0, 0, 7, 0, 0, 0, 0x3e, 8, 1, 2, 0];
        let plaintext = b"cross-implementation test vector";

        assert_eq!(
            hex_encode(key),
            "f1056de4e89aa1c938953775624a38a79f961023e9d9955cafcdd2efdc4e67c2",
            "derive_session_key output changed — regenerate the vector (see print_known_answer_vector) and update this + CryptoTest.kt together"
        );

        let ciphertext = encrypt_payload(&key, &session_id, 7, &header, plaintext).unwrap();
        assert_eq!(
            hex_encode(&ciphertext),
            "0094c8034b5be30d97accae01a756b29359ae4759e7324aefc36d8968afbfa9242456b11fc40982feac08aa91dc17d07"
        );

        let decrypted = decrypt_payload(&key, &session_id, 7, &header, &ciphertext).unwrap();
        assert_eq!(decrypted, plaintext);
    }
}
