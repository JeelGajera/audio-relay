//! Shared, mutex-guarded runtime state read/written by the network tasks
//! and the UI. Kept intentionally simple (a handful of `Mutex`-wrapped
//! fields behind one `Arc`) rather than an actor/message-passing system —
//! this app has exactly one active session at a time, so the extra
//! machinery isn't worth it.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use crate::config::Config;
use crate::protocol::crypto::{SessionId, SessionKey};

pub const PAIRING_CODE_TTL_SECS: u64 = 5 * 60;

#[derive(Debug, Clone)]
pub struct ActiveSession {
    pub phone_device_id: String,
    pub phone_device_name: String,
    pub audio_addr: SocketAddr,
    pub session_key: SessionKey,
    pub session_id: SessionId,
}

#[derive(Debug, Default)]
pub struct PairingState {
    pub current_code: Option<String>,
    pub generated_at: Option<Instant>,
}

impl PairingState {
    fn is_current_valid(&self) -> bool {
        match (&self.current_code, self.generated_at) {
            (Some(_), Some(t)) => t.elapsed().as_secs() < PAIRING_CODE_TTL_SECS,
            _ => false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectionStatus {
    WaitingForConnection,
    Streaming { device_name: String },
    Disconnected { device_name: String },
}

pub struct AppState {
    pub config: Mutex<Config>,
    pairing: Mutex<PairingState>,
    pub session: Mutex<Option<ActiveSession>>,
    pub status: Mutex<ConnectionStatus>,
    /// Gates `AudioSender::send_frame` — the UI's Start/Stop toggle.
    /// Capture keeps running regardless; this only controls transmission,
    /// so toggling it back on doesn't need to re-negotiate anything.
    streaming_enabled: AtomicBool,
}

impl AppState {
    pub fn new(config: Config) -> Arc<Self> {
        Arc::new(AppState {
            config: Mutex::new(config),
            pairing: Mutex::new(PairingState::default()),
            session: Mutex::new(None),
            status: Mutex::new(ConnectionStatus::WaitingForConnection),
            streaming_enabled: AtomicBool::new(true),
        })
    }

    pub fn is_streaming_enabled(&self) -> bool {
        self.streaming_enabled.load(Ordering::Relaxed)
    }

    pub fn set_streaming_enabled(&self, enabled: bool) {
        self.streaming_enabled.store(enabled, Ordering::Relaxed);
    }

    /// Returns the currently displayed pairing code, generating a fresh
    /// one if there isn't a still-valid one (protocol-spec.md §5: codes
    /// are valid for 5 minutes). Call this whenever the UI needs something
    /// to show, and whenever an unpaired device connects.
    pub fn current_pairing_code(&self) -> String {
        let mut pairing = self.pairing.lock().unwrap();
        if !pairing.is_current_valid() {
            pairing.current_code = Some(crate::protocol::crypto::generate_pairing_code());
            pairing.generated_at = Some(Instant::now());
        }
        pairing.current_code.clone().expect("just set above")
    }

    /// Validates a code a connecting device supplied against the currently
    /// displayed one. Does not consume/rotate the code on failure, so the
    /// user can retry a mistyped digit.
    pub fn check_pairing_code(&self, candidate: &str) -> bool {
        let pairing = self.pairing.lock().unwrap();
        pairing.is_current_valid() && pairing.current_code.as_deref() == Some(candidate)
    }

    pub fn set_status(&self, status: ConnectionStatus) {
        *self.status.lock().unwrap() = status;
    }

    /// Clears the active session if it belongs to `phone_device_id`,
    /// returning the device's name for logging/status purposes.
    pub fn clear_session_if(&self, phone_device_id: &str) -> Option<String> {
        let mut session = self.session.lock().unwrap();
        if session
            .as_ref()
            .is_some_and(|s| s.phone_device_id == phone_device_id)
        {
            return session.take().map(|s| s.phone_device_name);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;

    #[test]
    fn pairing_code_is_stable_until_it_expires() {
        let state = AppState::new(Config::default());
        let a = state.current_pairing_code();
        let b = state.current_pairing_code();
        assert_eq!(a, b, "should not rotate the code on every call");
    }

    #[test]
    fn check_pairing_code_matches_current() {
        let state = AppState::new(Config::default());
        let code = state.current_pairing_code();
        assert!(state.check_pairing_code(&code));
        assert!(!state.check_pairing_code("000000000")); // wrong shape
    }

    #[test]
    fn clear_session_if_only_clears_matching_device() {
        let state = AppState::new(Config::default());
        *state.session.lock().unwrap() = Some(ActiveSession {
            phone_device_id: "phone-1".into(),
            phone_device_name: "Pixel".into(),
            audio_addr: "127.0.0.1:9000".parse().unwrap(),
            session_key: [0u8; 32],
            session_id: [0u8; 8],
        });

        assert_eq!(state.clear_session_if("some-other-phone"), None);
        assert!(state.session.lock().unwrap().is_some());

        assert_eq!(state.clear_session_if("phone-1"), Some("Pixel".to_string()));
        assert!(state.session.lock().unwrap().is_none());
    }
}
