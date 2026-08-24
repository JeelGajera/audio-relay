//! Wire protocol implementation. Must stay in sync with `/protocol-spec.md`
//! at the repo root — see that file for the authoritative field-by-field
//! description of everything in here.

pub mod control;
pub mod crypto;
pub mod packet;

/// v2: PAIR_REQUEST carries a proof instead of the raw code, and PAIR_OK no
/// longer carries key material — see protocol-spec.md's changelog note and §5.
pub const PROTOCOL_VERSION: u32 = 2;
pub const SERVICE_TYPE: &str = "_audiorelay._udp.local.";
