//! UDP audio packet: 12-byte header + PCM (or, once paired, encrypted) payload.
//! See `/protocol-spec.md` §3.

use thiserror::Error;

/// codec_id(1) + sequence(4) + timestamp_ms(4) + sample_rate_id(1) + channels(1) + reserved(2)
pub const HEADER_LEN: usize = 13;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Codec {
    RawPcm = 0x00,
}

impl Codec {
    // Not called by this sender-only binary today, but decode() and everything
    // it depends on are kept as the reference implementation of the full wire
    // format (protocol-spec.md §3) — exercised by the round-trip tests below,
    // and needed if this crate ever grows a debug/replay tool.
    #[allow(dead_code)]
    fn from_u8(v: u8) -> Option<Self> {
        match v {
            0x00 => Some(Codec::RawPcm),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum SampleRate {
    Hz44100 = 0,
    Hz48000 = 1,
}

impl SampleRate {
    #[allow(dead_code)] // part of the public wire-format API; not yet called by this sender binary
    pub fn as_hz(self) -> u32 {
        match self {
            SampleRate::Hz44100 => 44_100,
            SampleRate::Hz48000 => 48_000,
        }
    }

    #[allow(dead_code)] // see Codec::from_u8 above
    fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(SampleRate::Hz44100),
            1 => Some(SampleRate::Hz48000),
            _ => None,
        }
    }
}

#[allow(dead_code)] // constructed by AudioPacket::decode, which this sender binary doesn't call yet
#[derive(Debug, Error, PartialEq, Eq)]
pub enum PacketError {
    #[error("packet shorter than the {HEADER_LEN}-byte header (got {0} bytes)")]
    TooShort(usize),
    #[error("unrecognized codec_id {0:#04x}")]
    UnknownCodec(u8),
    #[error("unrecognized sample_rate_id {0}")]
    UnknownSampleRate(u8),
}

/// A single audio frame ready to go on the wire. `payload` is raw PCM
/// before encryption is applied by the caller (see `protocol::crypto`) —
/// this type deliberately doesn't know about encryption so it stays easy
/// to unit test.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioPacket {
    pub codec: Codec,
    pub sequence: u32,
    pub timestamp_ms: u32,
    pub sample_rate: SampleRate,
    pub channels: u8,
    pub payload: Vec<u8>,
}

impl AudioPacket {
    pub fn encode(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(HEADER_LEN + self.payload.len());
        buf.push(self.codec as u8);
        buf.extend_from_slice(&self.sequence.to_be_bytes());
        buf.extend_from_slice(&self.timestamp_ms.to_be_bytes());
        buf.push(self.sample_rate as u8);
        buf.push(self.channels);
        buf.extend_from_slice(&0u16.to_be_bytes()); // reserved
        buf.extend_from_slice(&self.payload);
        buf
    }

    #[allow(dead_code)] // see the comment on Codec::from_u8 above
    pub fn decode(buf: &[u8]) -> Result<Self, PacketError> {
        if buf.len() < HEADER_LEN {
            return Err(PacketError::TooShort(buf.len()));
        }
        let codec = Codec::from_u8(buf[0]).ok_or(PacketError::UnknownCodec(buf[0]))?;
        let sequence = u32::from_be_bytes([buf[1], buf[2], buf[3], buf[4]]);
        let timestamp_ms = u32::from_be_bytes([buf[5], buf[6], buf[7], buf[8]]);
        let sample_rate =
            SampleRate::from_u8(buf[9]).ok_or(PacketError::UnknownSampleRate(buf[9]))?;
        let channels = buf[10];
        // buf[11..13] (reserved u16) intentionally ignored.
        let payload = buf[HEADER_LEN..].to_vec();
        Ok(AudioPacket {
            codec,
            sequence,
            timestamp_ms,
            sample_rate,
            channels,
            payload,
        })
    }

    /// Header bytes only, for use as encryption AAD or nonce material —
    /// `sequence`/`timestamp_ms` must stay readable even on an
    /// undecryptable packet so the jitter buffer can still register loss.
    pub fn header_bytes(&self) -> [u8; HEADER_LEN] {
        let full = self.encode();
        let mut header = [0u8; HEADER_LEN];
        header.copy_from_slice(&full[..HEADER_LEN]);
        header
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_packet() -> AudioPacket {
        AudioPacket {
            codec: Codec::RawPcm,
            sequence: 42,
            timestamp_ms: 123_456,
            sample_rate: SampleRate::Hz48000,
            channels: 2,
            payload: vec![1, 2, 3, 4, 5, 6, 7, 8],
        }
    }

    #[test]
    fn round_trips_through_encode_decode() {
        let pkt = sample_packet();
        let encoded = pkt.encode();
        assert_eq!(encoded.len(), HEADER_LEN + pkt.payload.len());
        let decoded = AudioPacket::decode(&encoded).unwrap();
        assert_eq!(pkt, decoded);
    }

    #[test]
    fn header_is_exactly_header_len_bytes() {
        let pkt = sample_packet();
        assert_eq!(pkt.header_bytes().len(), HEADER_LEN);
    }

    #[test]
    fn rejects_short_buffers() {
        let err = AudioPacket::decode(&[0u8; 4]).unwrap_err();
        assert_eq!(err, PacketError::TooShort(4));
    }

    #[test]
    fn rejects_unknown_codec() {
        let mut buf = sample_packet().encode();
        buf[0] = 0x7F;
        let err = AudioPacket::decode(&buf).unwrap_err();
        assert_eq!(err, PacketError::UnknownCodec(0x7F));
    }

    #[test]
    fn rejects_unknown_sample_rate() {
        let mut buf = sample_packet().encode();
        buf[9] = 200;
        let err = AudioPacket::decode(&buf).unwrap_err();
        assert_eq!(err, PacketError::UnknownSampleRate(200));
    }

    #[test]
    fn sequence_and_timestamp_survive_wraparound_values() {
        let mut pkt = sample_packet();
        pkt.sequence = u32::MAX;
        pkt.timestamp_ms = u32::MAX;
        let decoded = AudioPacket::decode(&pkt.encode()).unwrap();
        assert_eq!(decoded.sequence, u32::MAX);
        assert_eq!(decoded.timestamp_ms, u32::MAX);
    }

    #[test]
    fn empty_payload_is_valid() {
        let mut pkt = sample_packet();
        pkt.payload.clear();
        let decoded = AudioPacket::decode(&pkt.encode()).unwrap();
        assert!(decoded.payload.is_empty());
    }
}
