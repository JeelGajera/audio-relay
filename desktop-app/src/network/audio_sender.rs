//! Sends captured PCM frames to the currently-paired phone over UDP,
//! encrypting each payload once a session key exists. See
//! `protocol-spec.md` §3.

use std::sync::Arc;

use tokio::net::UdpSocket;

use crate::protocol::crypto;
use crate::protocol::packet::{AudioPacket, Codec, SampleRate};
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

    /// Encrypts and sends one frame. A no-op (returns `Ok`) if there's no
    /// active paired session yet — capture keeps running regardless of
    /// pairing state, this is just where we decide whether to actually
    /// transmit.
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
        self.socket
            .send_to(&packet.encode(), session.audio_addr)
            .await?;
        Ok(())
    }
}
