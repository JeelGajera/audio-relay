//! UDP audio packet: 12-byte header + PCM (or, once paired, encrypted) payload.
//! See `/protocol-spec.md` §3.

use thiserror::Error;

/// codec_id(1) + sequence(4) + timestamp_ms(4) + sample_rate_id(1) + channels(1) + reserved(2)
pub const HEADER_LEN: usize = 13;

/// ChaCha20-Poly1305's authentication tag, appended to every encrypted
/// payload (`protocol::crypto`). Counted here because it is part of what
/// actually goes on the wire, and therefore part of the MTU budget below.
pub const AEAD_TAG_LEN: usize = 16;

/// Largest UDP datagram this sender will ever emit, header and AEAD tag
/// included.
///
/// **This is a reliability constant, not a tuning knob.** Ethernet/Wi-Fi
/// MTU is 1500, leaving 1472 bytes of UDP payload over IPv4 — but a 10ms
/// chunk of 48kHz stereo 16-bit PCM is 1920 bytes, so *every single audio
/// packet* used to be IP-fragmented into two. Any one fragment lost drops
/// the whole datagram, which roughly doubles the effective loss rate and is
/// exactly the "song keeps cutting out" symptom on a phone hotspot, where
/// loss is already common. 1200 stays under the real MTU with room to spare
/// for IPv6's larger header and any VPN/tunnel encapsulation in the path —
/// the same conservative budget QUIC and WebRTC use for the same reason.
///
/// `AudioSender::send_frame` splits anything larger across multiple packets
/// rather than handing an oversized datagram to the OS. See
/// `max_payload_bytes` below.
pub const MAX_DATAGRAM_BYTES: usize = 1200;

/// How much *plaintext* PCM fits in one packet without exceeding
/// [`MAX_DATAGRAM_BYTES`], rounded down to a whole frame so a packet never
/// carries a partial sample.
///
/// `bytes_per_frame` is `channels * 2` for the 16-bit PCM this protocol
/// carries (`protocol-spec.md` §3).
pub fn max_payload_bytes(bytes_per_frame: usize) -> usize {
    let budget = MAX_DATAGRAM_BYTES.saturating_sub(HEADER_LEN + AEAD_TAG_LEN);
    if bytes_per_frame == 0 {
        return budget;
    }
    // Whole frames only, and always at least one frame.
    (budget / bytes_per_frame).max(1) * bytes_per_frame
}

/// Payload size to use when splitting `len` bytes across MTU-sized packets
/// so that **every packet is the same size**, rather than a run of full
/// ones followed by a short remainder.
///
/// Uniformity is not cosmetic. The receiver is told nothing about how the
/// sender packetises, so it infers packet duration from the packets it
/// gets (`JitterBuffer.chunkSizeBytes`) and sizes its target depth,
/// concealment silence and latency-trim threshold from that. Splitting a
/// 1920-byte chunk as `[1168, 752]` made every one of those quantities
/// oscillate packet to packet — the target swung between 20 and 31 chunks
/// — which tripped the latency trim spuriously and discarded perfectly
/// good audio every few seconds. Equal packets keep it stable.
pub fn even_split_bytes(len: usize, max_payload: usize, bytes_per_frame: usize) -> usize {
    if len == 0 || max_payload == 0 {
        return max_payload.max(1);
    }
    let packets = len.div_ceil(max_payload).max(1);
    let ideal = len.div_ceil(packets);
    let frame = bytes_per_frame.max(1);
    // Round up to a whole frame so no packet splits a sample. This can only
    // grow `ideal` up to `max_payload`, which is itself frame-aligned.
    ideal.div_ceil(frame) * frame
}

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

    /// The regression this constant exists for: a 10ms chunk of 48kHz
    /// stereo 16-bit PCM is 1920 bytes, which with the header and AEAD tag
    /// made a 1949-byte datagram — over the 1472-byte IPv4/Ethernet UDP
    /// limit, so every audio packet was IP-fragmented and a single lost
    /// fragment dropped the whole thing.
    #[test]
    fn a_ten_millisecond_chunk_no_longer_fits_in_one_datagram() {
        let bytes_per_frame = 2 * 2; // stereo, 16-bit
        let ten_ms = 48_000 * bytes_per_frame * 10 / 1000;
        assert_eq!(ten_ms, 1920);
        assert!(
            ten_ms + HEADER_LEN + AEAD_TAG_LEN > 1472,
            "this test is meaningless if a 10ms chunk already fit the MTU"
        );
        // ...so it must be split into more than one packet.
        assert!(max_payload_bytes(bytes_per_frame) < ten_ms);
    }

    /// The regression that caused periodic audible chopping: an uneven
    /// split made the receiver's inferred packet duration oscillate, which
    /// swung its target depth and tripped its latency trim on good audio.
    #[test]
    fn a_chunk_splits_into_equal_packets() {
        let bytes_per_frame = 4;
        let max_payload = max_payload_bytes(bytes_per_frame);
        for chunk_ms in [5_usize, 10] {
            let len = 48_000 * bytes_per_frame * chunk_ms / 1000;
            let per = even_split_bytes(len, max_payload, bytes_per_frame);
            let sizes: Vec<usize> = (0..len)
                .step_by(per)
                .map(|off| per.min(len - off))
                .collect();
            assert!(
                sizes.windows(2).all(|w| w[0] == w[1]),
                "{chunk_ms}ms chunk split unevenly into {sizes:?}"
            );
            assert!(sizes
                .iter()
                .all(|s| *s + HEADER_LEN + AEAD_TAG_LEN <= MAX_DATAGRAM_BYTES));
            assert_eq!(sizes.iter().sum::<usize>(), len, "split lost audio");
        }
    }

    #[test]
    fn an_even_split_never_exceeds_the_budget_or_splits_a_frame() {
        let bytes_per_frame = 4;
        let max_payload = max_payload_bytes(bytes_per_frame);
        for len in (bytes_per_frame..8000).step_by(bytes_per_frame) {
            let per = even_split_bytes(len, max_payload, bytes_per_frame);
            assert!(per <= max_payload, "len {len} produced an oversized {per}");
            assert_eq!(per % bytes_per_frame, 0, "len {len} split a frame");
            assert!(per > 0);
        }
    }

    #[test]
    fn a_full_packet_stays_within_the_mtu_budget() {
        for channels in [1_usize, 2] {
            let bytes_per_frame = channels * 2;
            let payload = max_payload_bytes(bytes_per_frame);
            // Worst case on the wire: payload + AEAD tag + header.
            assert!(
                payload + AEAD_TAG_LEN + HEADER_LEN <= MAX_DATAGRAM_BYTES,
                "{channels}ch packet would be {} bytes",
                payload + AEAD_TAG_LEN + HEADER_LEN
            );
        }
    }

    #[test]
    fn max_payload_is_always_a_whole_number_of_frames() {
        for channels in [1_usize, 2, 6, 8] {
            let bytes_per_frame = channels * 2;
            let payload = max_payload_bytes(bytes_per_frame);
            assert_eq!(
                payload % bytes_per_frame,
                0,
                "{channels}ch payload {payload} would split a sample in half"
            );
            assert!(payload > 0);
        }
    }

    /// A frame larger than the whole budget still has to produce a
    /// sendable packet rather than a zero-length one that silently drops
    /// audio.
    #[test]
    fn an_absurdly_large_frame_still_yields_one_whole_frame() {
        let huge = MAX_DATAGRAM_BYTES * 4;
        assert_eq!(max_payload_bytes(huge), huge);
        assert_eq!(
            max_payload_bytes(0),
            MAX_DATAGRAM_BYTES - HEADER_LEN - AEAD_TAG_LEN
        );
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
