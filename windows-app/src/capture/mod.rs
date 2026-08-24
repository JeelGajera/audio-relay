//! System-audio capture. The real implementation (`windows_impl`) uses
//! WASAPI loopback capture and only compiles on Windows, since that's a
//! Windows-only API (see `docs/architecture.md` §2.1). A stub is provided
//! for other platforms so the rest of the crate — protocol, network,
//! config — still builds and can be tested in CI on any OS (see
//! `AGENTS.md`).
//!
//! **Unverified on real hardware.** This module has not yet been exercised
//! against a physical Windows machine in this repository's history — see
//! `docs/roadmap.md` Phase 0. Treat it as a best-effort implementation of
//! the documented WASAPI loopback pattern, not a proven one, until someone
//! reports back with a real test.

use std::sync::Arc;

use tokio::sync::mpsc::UnboundedSender;

use crate::state::AppState;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureFormat {
    pub sample_rate_hz: u32,
    pub channels: u8,
}

/// One chunk of captured audio: interleaved 16-bit little-endian PCM,
/// stamped with the sender's monotonic clock at the time it was captured.
/// This is what feeds `network::audio_sender::AudioSender`.
#[derive(Debug, Clone)]
pub struct CapturedChunk {
    pub format: CaptureFormat,
    pub timestamp_ms: u32,
    pub pcm: Vec<u8>,
}

#[derive(Debug, thiserror::Error)]
pub enum CaptureError {
    #[error("WASAPI loopback capture is only implemented on Windows")]
    UnsupportedPlatform,
    #[cfg(target_os = "windows")]
    #[error("WASAPI error: {0}")]
    Wasapi(String),
}

/// Starts loopback-capturing on a dedicated OS thread (WASAPI/COM want
/// their own apartment-threaded thread, not an async task) and streams
/// chunks out through `tx`. Which endpoint it captures from, and the
/// chunk size, are read live from `state` (`state::AppState`'s
/// `selected_capture_device_id`/`latency_mode` — set by the Settings tab
/// in `ui/mod.rs`) — changing the device restarts the WASAPI stream;
/// changing the latency mode just changes how much is buffered before a
/// chunk goes out, no restart needed. Returns immediately; call
/// `handle.join()` (or just drop it — capture runs until the process
/// exits or the channel closes) to wait for it.
pub fn start_capture(
    tx: UnboundedSender<CapturedChunk>,
    state: Arc<AppState>,
) -> Result<std::thread::JoinHandle<()>, CaptureError> {
    #[cfg(target_os = "windows")]
    {
        windows_impl::start(tx, state)
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = (tx, state);
        Err(CaptureError::UnsupportedPlatform)
    }
}

#[cfg(target_os = "windows")]
mod windows_impl {
    use super::{CaptureError, CaptureFormat, CapturedChunk};
    use crate::state::{AppState, CaptureDeviceInfo};
    use std::collections::VecDeque;
    use std::sync::Arc;
    use std::time::Instant;
    use tokio::sync::mpsc::UnboundedSender;
    use wasapi::{Device, DeviceCollection, Direction, ShareMode, WaveFormat};

    pub fn start(
        tx: UnboundedSender<CapturedChunk>,
        state: Arc<AppState>,
    ) -> Result<std::thread::JoinHandle<()>, CaptureError> {
        let handle = std::thread::Builder::new()
            .name("wasapi-loopback-capture".into())
            .spawn(move || {
                if let Err(e) = wasapi::initialize_mta().ok() {
                    tracing::error!(error = %e, "failed to initialize COM for the capture thread");
                    return;
                }
                loop {
                    let generation = state.capture_generation();
                    if let Err(e) = capture_until_restart(&tx, &state, generation) {
                        tracing::warn!(error = %e, "capture stream ended; retrying in 1s");
                        std::thread::sleep(std::time::Duration::from_secs(1));
                    }
                    if tx.is_closed() {
                        return; // app shutting down
                    }
                }
            })
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
        Ok(handle)
    }

    /// Enumerates render (output) endpoints. Must be called from a thread
    /// that has already called `wasapi::initialize_mta()` — device
    /// enumeration is a COM call.
    fn enumerate_devices() -> Result<Vec<CaptureDeviceInfo>, CaptureError> {
        let collection = DeviceCollection::new(&Direction::Render)
            .map_err(|e| CaptureError::Wasapi(format!("enumerating output devices: {e}")))?;
        let count = collection
            .get_nbr_devices()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
        let mut devices = Vec::with_capacity(count as usize);
        for i in 0..count {
            let device = collection
                .get_device_at_index(i)
                .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
            let id = device
                .get_id()
                .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
            let name = device
                .get_friendlyname()
                .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
            devices.push(CaptureDeviceInfo { id, name });
        }
        Ok(devices)
    }

    fn open_selected_device(selected_id: Option<&str>) -> Result<Device, CaptureError> {
        match selected_id {
            None => wasapi::get_default_device(&Direction::Render)
                .map_err(|e| CaptureError::Wasapi(format!("no default output device: {e}"))),
            Some(id) => {
                let collection = DeviceCollection::new(&Direction::Render)
                    .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
                let count = collection
                    .get_nbr_devices()
                    .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
                for i in 0..count {
                    let device = collection
                        .get_device_at_index(i)
                        .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
                    if device
                        .get_id()
                        .map_err(|e| CaptureError::Wasapi(e.to_string()))?
                        == id
                    {
                        return Ok(device);
                    }
                }
                tracing::warn!(
                    device_id = id,
                    "selected capture device no longer present; falling back to default"
                );
                wasapi::get_default_device(&Direction::Render)
                    .map_err(|e| CaptureError::Wasapi(format!("no default output device: {e}")))
            }
        }
    }

    /// Runs one WASAPI stream until either an error occurs or
    /// `state.capture_generation()` moves past `started_at_generation`
    /// (the user picked a different device, or asked for a refresh),
    /// in which case it returns `Ok(())` and the caller loops to pick up
    /// the new selection.
    fn capture_until_restart(
        tx: &UnboundedSender<CapturedChunk>,
        state: &Arc<AppState>,
        started_at_generation: u64,
    ) -> Result<(), CaptureError> {
        // Refresh the device list on every (re)start — cheap, and covers
        // "plugged in a new device" without needing a separate poll timer.
        match enumerate_devices() {
            Ok(devices) => state.publish_capture_devices(devices),
            Err(e) => tracing::warn!(error = %e, "failed to enumerate output devices"),
        }

        let selected_id = state.selected_capture_device_id();
        let device = open_selected_device(selected_id.as_deref())?;
        let mut audio_client = device
            .get_iaudioclient()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

        // Capture the endpoint's own mix format rather than forcing a
        // resample in the driver — see docs/architecture.md §2.1. The
        // receiver honors whatever sample_rate/channels each packet
        // declares (protocol-spec.md §3).
        let wave_format: WaveFormat = audio_client
            .get_mixformat()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
        let format = CaptureFormat {
            sample_rate_hz: wave_format.get_samplespersec(),
            channels: wave_format.get_nchannels() as u8,
        };

        let (_default_period, min_period) = audio_client
            .get_periods()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

        audio_client
            .initialize_client(
                &wave_format,
                min_period,
                &Direction::Capture,
                &ShareMode::Shared,
                true,
            )
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

        let event_handle = audio_client
            .set_get_eventhandle()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
        let capture_client = audio_client
            .get_audiocaptureclient()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

        audio_client
            .start_stream()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

        let start = Instant::now();
        let mut sample_queue: VecDeque<u8> = VecDeque::with_capacity(
            wave_format.get_blockalign() as usize * wave_format.get_samplespersec() as usize / 50,
        );

        loop {
            if state.capture_generation() != started_at_generation {
                let _ = audio_client.stop_stream();
                return Ok(()); // device selection changed (or a refresh was requested) — restart
            }

            if event_handle.wait_for_event(1000).is_err() {
                tracing::warn!("WASAPI capture event wait timed out; restarting stream");
                let _ = audio_client.stop_stream();
                return Ok(());
            }

            capture_client
                .read_from_device_to_deque(&mut sample_queue)
                .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

            // Read live, not cached at stream-start — the whole point is
            // that this can change without a restart (see module doc).
            let chunk_ms = state.latency_mode().chunk_ms();
            let bytes_per_chunk =
                (wave_format.get_blockalign() as u32 * wave_format.get_samplespersec() * chunk_ms
                    / 1000) as usize;

            while bytes_per_chunk > 0 && sample_queue.len() >= bytes_per_chunk {
                let pcm: Vec<u8> = sample_queue.drain(..bytes_per_chunk).collect();
                let chunk = CapturedChunk {
                    format,
                    timestamp_ms: start.elapsed().as_millis() as u32,
                    pcm,
                };
                if tx.send(chunk).is_err() {
                    let _ = audio_client.stop_stream();
                    return Ok(()); // receiver dropped — app shutting down
                }
            }
        }
    }
}
