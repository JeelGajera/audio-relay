//! Shared, mutex-guarded runtime state read/written by the network tasks
//! and the UI. Kept intentionally simple (a handful of `Mutex`-wrapped
//! fields behind one `Arc`) rather than an actor/message-passing system —
//! this app has exactly one active session at a time, so the extra
//! machinery isn't worth it.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use crate::config::Config;
use crate::protocol::crypto::{SessionId, SessionKey};

pub const PAIRING_CODE_TTL_SECS: u64 = 5 * 60;

/// User-configurable capture chunk size — the main latency/glitch-resistance
/// tradeoff on the sender side (docs/architecture.md §6). Read live by the
/// capture loop every iteration, so it applies without a restart; changing
/// the *device* (see `CaptureDeviceInfo`/`capture_generation` below) does
/// need a restart, since that means reinitializing the WASAPI stream.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LatencyMode {
    /// ~5ms chunks: lower latency, less headroom for jitter.
    Low,
    /// ~10ms chunks: the default — matches the budget in docs/architecture.md §6.
    Balanced,
}

impl LatencyMode {
    // Only read from the capture loop, which is cfg(windows)-gated — see
    // capture/mod.rs. Not dead code on the platform that matters.
    #[cfg_attr(not(target_os = "windows"), allow(dead_code))]
    pub fn chunk_ms(self) -> u32 {
        match self {
            LatencyMode::Low => 5,
            LatencyMode::Balanced => 10,
        }
    }

    fn from_u8(v: u8) -> Self {
        match v {
            0 => LatencyMode::Low,
            _ => LatencyMode::Balanced,
        }
    }
}

/// One enumerated WASAPI render (output) endpoint the user can choose to
/// loop-back capture from, instead of always using the system default —
/// e.g. a laptop with both built-in speakers and a USB DAC. Populated by
/// the capture thread (device enumeration needs COM initialized on the
/// calling thread, which only the capture thread does) — see
/// `capture::windows_impl::enumerate_devices`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaptureDeviceInfo {
    pub id: String,
    pub name: String,
}

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
    latency_mode: AtomicU8,
    /// Devices found on the last capture-thread enumeration. UI-only reads;
    /// never written from the UI thread (see `CaptureDeviceInfo`'s doc).
    pub available_capture_devices: Mutex<Vec<CaptureDeviceInfo>>,
    selected_capture_device_id: Mutex<Option<String>>,
    /// Bumped on every device-selection change (and by
    /// `request_capture_devices_refresh`) — the capture loop compares this
    /// against the value it captured at stream-start time and restarts the
    /// WASAPI stream when it no longer matches.
    capture_generation: AtomicU64,
}

impl AppState {
    pub fn new(config: Config) -> Arc<Self> {
        Arc::new(AppState {
            config: Mutex::new(config),
            pairing: Mutex::new(PairingState::default()),
            session: Mutex::new(None),
            status: Mutex::new(ConnectionStatus::WaitingForConnection),
            streaming_enabled: AtomicBool::new(true),
            latency_mode: AtomicU8::new(LatencyMode::Balanced as u8),
            available_capture_devices: Mutex::new(Vec::new()),
            selected_capture_device_id: Mutex::new(None),
            capture_generation: AtomicU64::new(0),
        })
    }

    pub fn is_streaming_enabled(&self) -> bool {
        self.streaming_enabled.load(Ordering::Relaxed)
    }

    pub fn set_streaming_enabled(&self, enabled: bool) {
        self.streaming_enabled.store(enabled, Ordering::Relaxed);
    }

    pub fn latency_mode(&self) -> LatencyMode {
        LatencyMode::from_u8(self.latency_mode.load(Ordering::Relaxed))
    }

    pub fn set_latency_mode(&self, mode: LatencyMode) {
        self.latency_mode.store(mode as u8, Ordering::Relaxed);
    }

    pub fn selected_capture_device_id(&self) -> Option<String> {
        self.selected_capture_device_id.lock().unwrap().clone()
    }

    /// `None` means "system default output device".
    pub fn set_selected_capture_device(&self, device_id: Option<String>) {
        *self.selected_capture_device_id.lock().unwrap() = device_id;
        self.capture_generation.fetch_add(1, Ordering::SeqCst);
    }

    // Only read from the capture loop, which is cfg(windows)-gated — see
    // capture/mod.rs. Not dead code on the platform that matters.
    #[cfg_attr(not(target_os = "windows"), allow(dead_code))]
    pub fn capture_generation(&self) -> u64 {
        self.capture_generation.load(Ordering::SeqCst)
    }

    /// Asks the capture thread to re-enumerate devices and restart its
    /// stream on its next check, without changing the selection.
    pub fn request_capture_devices_refresh(&self) {
        self.capture_generation.fetch_add(1, Ordering::SeqCst);
    }

    #[cfg_attr(not(target_os = "windows"), allow(dead_code))]
    pub fn publish_capture_devices(&self, devices: Vec<CaptureDeviceInfo>) {
        *self.available_capture_devices.lock().unwrap() = devices;
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

    /// Verifies a `PAIR_REQUEST` proof against the currently displayed
    /// pairing code (protocol-spec.md §5 — the code itself is never sent
    /// over the network, only this HMAC proof). Returns the code on success
    /// (the caller needs it to derive the session key), or `None` on any
    /// failure — no current code, expired, or proof mismatch. Never
    /// mutates/rotates the code, so the user can retry a mistyped digit.
    pub fn verify_pairing_proof(
        &self,
        phone_device_id: &str,
        nonce: &str,
        proof_hex: &str,
    ) -> Option<String> {
        let pairing = self.pairing.lock().unwrap();
        if !pairing.is_current_valid() {
            return None;
        }
        let code = pairing.current_code.clone()?;
        crate::protocol::crypto::verify_pair_proof(&code, phone_device_id, nonce, proof_hex)
            .then_some(code)
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
    fn verify_pairing_proof_accepts_a_valid_proof_and_returns_the_code() {
        let state = AppState::new(Config::default());
        let code = state.current_pairing_code();
        let proof = crate::protocol::crypto::compute_pair_proof(&code, "phone-1", "nonce-1");
        assert_eq!(
            state.verify_pairing_proof("phone-1", "nonce-1", &proof),
            Some(code)
        );
    }

    #[test]
    fn verify_pairing_proof_rejects_a_bogus_proof() {
        let state = AppState::new(Config::default());
        state.current_pairing_code();
        assert_eq!(
            state.verify_pairing_proof("phone-1", "nonce-1", "not-a-real-proof"),
            None
        );
    }

    #[test]
    fn verify_pairing_proof_never_leaks_or_needs_the_raw_code_from_the_caller() {
        // The whole point: a caller only ever has device_id/nonce/proof —
        // never the code — and verification still succeeds.
        let state = AppState::new(Config::default());
        let code = state.current_pairing_code();
        let proof = crate::protocol::crypto::compute_pair_proof(&code, "phone-1", "nonce-1");
        assert!(state
            .verify_pairing_proof("phone-1", "nonce-1", &proof)
            .is_some());
        // A proof computed for a different device_id must not verify.
        let wrong_device_proof =
            crate::protocol::crypto::compute_pair_proof(&code, "phone-2", "nonce-1");
        assert_eq!(
            state.verify_pairing_proof("phone-1", "nonce-1", &wrong_device_proof),
            None
        );
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

    #[test]
    fn latency_mode_defaults_to_balanced() {
        let state = AppState::new(Config::default());
        assert_eq!(state.latency_mode(), LatencyMode::Balanced);
        assert_eq!(state.latency_mode().chunk_ms(), 10);
    }

    #[test]
    fn latency_mode_round_trips() {
        let state = AppState::new(Config::default());
        state.set_latency_mode(LatencyMode::Low);
        assert_eq!(state.latency_mode(), LatencyMode::Low);
        assert_eq!(state.latency_mode().chunk_ms(), 5);
    }

    #[test]
    fn selecting_a_capture_device_bumps_the_generation() {
        let state = AppState::new(Config::default());
        let g0 = state.capture_generation();
        state.set_selected_capture_device(Some("device-1".into()));
        assert_eq!(
            state.selected_capture_device_id(),
            Some("device-1".to_string())
        );
        assert!(state.capture_generation() > g0);
    }

    #[test]
    fn refresh_bumps_generation_without_changing_selection() {
        let state = AppState::new(Config::default());
        state.set_selected_capture_device(Some("device-1".into()));
        let g1 = state.capture_generation();
        state.request_capture_devices_refresh();
        assert_eq!(
            state.selected_capture_device_id(),
            Some("device-1".to_string())
        );
        assert!(state.capture_generation() > g1);
    }

    #[test]
    fn publish_capture_devices_replaces_the_list() {
        let state = AppState::new(Config::default());
        state.publish_capture_devices(vec![CaptureDeviceInfo {
            id: "d1".into(),
            name: "Speakers".into(),
        }]);
        assert_eq!(state.available_capture_devices.lock().unwrap().len(), 1);
    }
}
