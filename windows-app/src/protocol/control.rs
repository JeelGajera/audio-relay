//! TCP control-channel messages: newline-delimited JSON, one object per
//! line. See `/protocol-spec.md` §4 for the full state machine and field
//! reference — this module is a direct transcription of that table.

use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Hello {
    pub protocol_version: u32,
    pub device_id: String,
    pub device_name: String,
    /// UDP port the phone has already bound and is listening on for audio.
    /// Not necessarily the same as the TCP control port.
    pub audio_port: u16,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HelloAck {
    pub protocol_version: u32,
    pub device_id: String,
    pub device_name: String,
    pub paired: bool,
    /// Always sent (not just when `paired == true`) — the challenge used in
    /// the proof the phone sends next, whether that's `PAIR_REQUEST` or
    /// `REPAIR`. See protocol-spec.md §4.2 / §5.
    pub nonce: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PairRequest {
    /// `HMAC-SHA256(code, phone_device_id || nonce)`, hex-encoded. The code
    /// itself is never sent — see protocol-spec.md §5.
    pub proof: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Repair {
    pub device_id: String,
    pub proof: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PairOk {
    pub session_id: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PairFail {
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Capabilities {
    pub sample_rate: u32,
    pub channels: u8,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Ping {
    pub t: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Pong {
    pub t: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ControlMessage {
    Hello(Hello),
    HelloAck(HelloAck),
    PairRequest(PairRequest),
    Repair(Repair),
    PairOk(PairOk),
    PairFail(PairFail),
    Capabilities(Capabilities),
    Ping(Ping),
    Pong(Pong),
    Bye,
}

impl ControlMessage {
    fn type_tag(&self) -> &'static str {
        match self {
            ControlMessage::Hello(_) => "HELLO",
            ControlMessage::HelloAck(_) => "HELLO_ACK",
            ControlMessage::PairRequest(_) => "PAIR_REQUEST",
            ControlMessage::Repair(_) => "REPAIR",
            ControlMessage::PairOk(_) => "PAIR_OK",
            ControlMessage::PairFail(_) => "PAIR_FAIL",
            ControlMessage::Capabilities(_) => "CAPABILITIES",
            ControlMessage::Ping(_) => "PING",
            ControlMessage::Pong(_) => "PONG",
            ControlMessage::Bye => "BYE",
        }
    }

    /// Serializes to a single newline-terminated JSON line, ready to write
    /// directly to the TCP socket.
    pub fn to_line(&self) -> Result<String, ControlError> {
        let mut value = match self {
            ControlMessage::Hello(m) => serde_json::to_value(m)?,
            ControlMessage::HelloAck(m) => serde_json::to_value(m)?,
            ControlMessage::PairRequest(m) => serde_json::to_value(m)?,
            ControlMessage::Repair(m) => serde_json::to_value(m)?,
            ControlMessage::PairOk(m) => serde_json::to_value(m)?,
            ControlMessage::PairFail(m) => serde_json::to_value(m)?,
            ControlMessage::Capabilities(m) => serde_json::to_value(m)?,
            ControlMessage::Ping(m) => serde_json::to_value(m)?,
            ControlMessage::Pong(m) => serde_json::to_value(m)?,
            ControlMessage::Bye => serde_json::json!({}),
        };
        value
            .as_object_mut()
            .expect("control messages always serialize to JSON objects")
            .insert("type".to_string(), serde_json::json!(self.type_tag()));
        let mut line = serde_json::to_string(&value)?;
        line.push('\n');
        Ok(line)
    }

    /// Parses one line of input. Returns `Ok(None)` for a `type` this
    /// version doesn't recognize (forward compatibility, per
    /// protocol-spec.md §6) rather than an error — callers should ignore
    /// those, not disconnect.
    pub fn from_line(line: &str) -> Result<Option<Self>, ControlError> {
        let line = line.trim();
        if line.is_empty() {
            return Ok(None);
        }
        let value: serde_json::Value = serde_json::from_str(line)?;
        let ty = value
            .get("type")
            .and_then(|v| v.as_str())
            .ok_or(ControlError::MissingType)?;
        let msg = match ty {
            "HELLO" => ControlMessage::Hello(serde_json::from_value(value)?),
            "HELLO_ACK" => ControlMessage::HelloAck(serde_json::from_value(value)?),
            "PAIR_REQUEST" => ControlMessage::PairRequest(serde_json::from_value(value)?),
            "REPAIR" => ControlMessage::Repair(serde_json::from_value(value)?),
            "PAIR_OK" => ControlMessage::PairOk(serde_json::from_value(value)?),
            "PAIR_FAIL" => ControlMessage::PairFail(serde_json::from_value(value)?),
            "CAPABILITIES" => ControlMessage::Capabilities(serde_json::from_value(value)?),
            "PING" => ControlMessage::Ping(serde_json::from_value(value)?),
            "PONG" => ControlMessage::Pong(serde_json::from_value(value)?),
            "BYE" => ControlMessage::Bye,
            _ => return Ok(None),
        };
        Ok(Some(msg))
    }
}

#[derive(Debug, Error)]
pub enum ControlError {
    #[error("malformed control message JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("control message missing required \"type\" field")]
    MissingType,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hello_round_trips() {
        let msg = ControlMessage::Hello(Hello {
            protocol_version: 1,
            device_id: "abc-123".into(),
            device_name: "Pixel 9".into(),
            audio_port: 45000,
        });
        let line = msg.to_line().unwrap();
        assert!(line.ends_with('\n'));
        let parsed = ControlMessage::from_line(&line).unwrap().unwrap();
        assert_eq!(msg, parsed);
    }

    #[test]
    fn bye_has_no_fields_but_still_round_trips() {
        let msg = ControlMessage::Bye;
        let line = msg.to_line().unwrap();
        assert_eq!(ControlMessage::from_line(&line).unwrap().unwrap(), msg);
    }

    #[test]
    fn unknown_type_is_ignored_not_erroring() {
        let result = ControlMessage::from_line(r#"{"type":"SOMETHING_FROM_THE_FUTURE"}"#).unwrap();
        assert!(result.is_none());
    }

    #[test]
    fn missing_type_is_an_error() {
        let err = ControlMessage::from_line(r#"{"foo":"bar"}"#).unwrap_err();
        assert!(matches!(err, ControlError::MissingType));
    }

    #[test]
    fn blank_line_is_ignored() {
        assert!(ControlMessage::from_line("").unwrap().is_none());
        assert!(ControlMessage::from_line("   \n").unwrap().is_none());
    }

    #[test]
    fn pair_ok_carries_no_key_material() {
        let msg = ControlMessage::PairOk(PairOk {
            session_id: "deadbeef".into(),
        });
        let line = msg.to_line().unwrap();
        assert!(!line.contains("session_key"));
    }

    #[test]
    fn hello_ack_nonce_round_trips() {
        let msg = ControlMessage::HelloAck(HelloAck {
            protocol_version: 1,
            device_id: "laptop-1".into(),
            device_name: "DESKTOP-A1B2C3".into(),
            paired: false,
            nonce: "deadbeefdeadbeef".into(),
        });
        let line = msg.to_line().unwrap();
        assert_eq!(ControlMessage::from_line(&line).unwrap().unwrap(), msg);
    }

    #[test]
    fn pair_request_carries_a_proof_not_a_code() {
        let msg = ControlMessage::PairRequest(PairRequest {
            proof: "abc123".into(),
        });
        let line = msg.to_line().unwrap();
        assert!(!line.contains("\"code\""));
        assert_eq!(ControlMessage::from_line(&line).unwrap().unwrap(), msg);
    }

    #[test]
    fn extra_unknown_fields_in_a_known_message_are_ignored() {
        let line = r#"{"type":"PING","t":123,"unexpected_new_field":"x"}"#;
        let parsed = ControlMessage::from_line(line).unwrap().unwrap();
        assert_eq!(parsed, ControlMessage::Ping(Ping { t: 123 }));
    }
}
