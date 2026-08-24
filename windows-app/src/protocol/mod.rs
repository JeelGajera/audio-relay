//! Wire protocol implementation. Must stay in sync with `/protocol-spec.md`
//! at the repo root — see that file for the authoritative field-by-field
//! description of everything in here.

pub mod control;
pub mod crypto;
pub mod packet;

pub const PROTOCOL_VERSION: u32 = 1;
pub const SERVICE_TYPE: &str = "_audiorelay._udp.local.";
