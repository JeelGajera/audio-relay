//! Sends captured PCM frames to the currently-paired phone over UDP,
//! encrypting each payload once a session key exists. See
//! `protocol-spec.md` §3.

use std::sync::Arc;

use tokio::net::UdpSocket;

use crate::protocol::crypto;
use crate::protocol::packet::{self, AudioPacket, Codec, SampleRate};
use crate::state::AppState;

#[derive(Debug, thiserror::Error)]
pub enum SendError {
    #[error("socket error: {0}")]
    Io(#[from] std::io::Error),
    #[error("encryption error: {0}")]
    Crypto(#[from] crypto::CryptoError),
}

pub struct AudioSender {
    socket: UdpSocket,
    sequence: u32,
}

impl AudioSender {
    pub async fn bind() -> std::io::Result<Self> {
        let socket = UdpSocket::bind("0.0.0.0:0").await?;
        Ok(AudioSender {
            socket,
            sequence: 0,
        })
    }

    /// Encrypts and sends one captured chunk, split across as many packets
    /// as it takes to keep every datagram within
    /// [`packet::MAX_DATAGRAM_BYTES`] (see that constant for why
    /// oversized datagrams are a reliability problem, not just a tidiness
    /// one). Each resulting packet is independently sequenced and
    /// encrypted, exactly as if it had been captured on its own — the
    /// receiver cannot tell a split chunk from natively-small ones, so this
    /// needs no protocol change.
    ///
    /// A no-op (returns `Ok`) if there's no active paired session yet —
    /// capture keeps running regardless of pairing state, this is just
    /// where we decide whether to actually transmit.
    pub async fn send_frame(
        &mut self,
        state: &Arc<AppState>,
        sample_rate: SampleRate,
        channels: u8,
        timestamp_ms: u32,
        pcm: &[u8],
    ) -> Result<(), SendError> {
        if !state.is_streaming_enabled() {
            return Ok(());
        }
        let session = state.session.lock().unwrap().clone();
        let Some(session) = session else {
            return Ok(());
        };

        let bytes_per_frame = (channels as usize).max(1) * 2; // 16-bit PCM
        let max_payload = packet::max_payload_bytes(bytes_per_frame);
        // Equal-sized packets, not "fill each one then a short remainder" —
        // see `even_split_bytes` for why an uneven split destabilises the
        // receiver's jitter buffer.
        let per_packet = packet::even_split_bytes(pcm.len(), max_payload, bytes_per_frame);
        let bytes_per_ms = (sample_rate.as_hz() as usize * bytes_per_frame) / 1000;

        for (i, slice) in pcm.chunks(per_packet).enumerate() {
            // Each sub-packet carries the capture time of *its own* audio,
            // not the parent chunk's, so the receiver's drift measurement
            // (which keys off forward progress of this timestamp) still
            // sees a monotonic clock rather than a stutter of repeats.
            // `checked_div` rather than a hand-rolled zero guard: identical
            // behaviour, and it says "this division may not be defined"
            // in one place instead of two.
            let offset_ms = (i * per_packet).checked_div(bytes_per_ms).unwrap_or(0) as u32;
            self.send_one(
                &session,
                sample_rate,
                channels,
                timestamp_ms.wrapping_add(offset_ms),
                slice,
            )
            .await?;
        }
        Ok(())
    }

    async fn send_one(
        &mut self,
        session: &crate::state::ActiveSession,
        sample_rate: SampleRate,
        channels: u8,
        timestamp_ms: u32,
        pcm: &[u8],
    ) -> Result<(), SendError> {
        let sequence = self.sequence;
        self.sequence = self.sequence.wrapping_add(1);

        let header_packet = AudioPacket {
            codec: Codec::RawPcm,
            sequence,
            timestamp_ms,
            sample_rate,
            channels,
            payload: Vec::new(),
        };
        let header = header_packet.header_bytes();

        let ciphertext = crypto::encrypt_payload(
            &session.session_key,
            &session.session_id,
            sequence,
            &header,
            pcm,
        )?;

        let packet = AudioPacket {
            payload: ciphertext,
            ..header_packet
        };
        let encoded = packet.encode();
        debug_assert!(
            encoded.len() <= packet::MAX_DATAGRAM_BYTES,
            "emitted a {}-byte datagram, over the {}-byte MTU budget",
            encoded.len(),
            packet::MAX_DATAGRAM_BYTES
        );
        self.socket.send_to(&encoded, session.audio_addr).await?;
        Ok(())
    }
}
