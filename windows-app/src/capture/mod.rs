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

use tokio::sync::mpsc::UnboundedSender;

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

/// Starts loopback-capturing the default render (output) endpoint on a
/// dedicated OS thread (WASAPI/COM want their own apartment-threaded
/// thread, not an async task) and streams chunks out through `tx`.
/// Returns immediately; call `handle.join()` (or just drop it — capture
/// runs until the process exits or the channel closes) to wait for it.
pub fn start_capture(
    tx: UnboundedSender<CapturedChunk>,
) -> Result<std::thread::JoinHandle<()>, CaptureError> {
    #[cfg(target_os = "windows")]
    {
        windows_impl::start(tx)
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = tx;
        Err(CaptureError::UnsupportedPlatform)
    }
}

#[cfg(target_os = "windows")]
mod windows_impl {
    use super::{CaptureError, CaptureFormat, CapturedChunk};
    use std::time::Instant;
    use tokio::sync::mpsc::UnboundedSender;
    use wasapi::{Direction, ShareMode, WaveFormat};

    /// ~10ms chunks at typical mix-format sample rates. Small enough to
    /// keep the capture-side latency contribution low (docs/architecture.md
    /// §6 budgets 3-10ms here), large enough not to be dominated by
    /// per-packet overhead.
    const TARGET_CHUNK_MS: u32 = 10;

    pub fn start(
        tx: UnboundedSender<CapturedChunk>,
    ) -> Result<std::thread::JoinHandle<()>, CaptureError> {
        let handle = std::thread::Builder::new()
            .name("wasapi-loopback-capture".into())
            .spawn(move || {
                if let Err(e) = capture_loop(tx) {
                    tracing::error!(error = %e, "capture loop exited with an error");
                }
            })
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;
        Ok(handle)
    }

    fn capture_loop(tx: UnboundedSender<CapturedChunk>) -> Result<(), CaptureError> {
        wasapi::initialize_mta().map_err(|e| CaptureError::Wasapi(e.to_string()))?;

        let device = wasapi::get_default_device(&Direction::Render)
            .map_err(|e| CaptureError::Wasapi(format!("no default output device: {e}")))?;
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
                true, // loopback
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
        let bytes_per_chunk_hint = (wave_format.get_blockalign() as u32
            * wave_format.get_samplespersec()
            * TARGET_CHUNK_MS
            / 1000) as usize;
        let mut sample_queue: std::collections::VecDeque<u8> =
            std::collections::VecDeque::with_capacity(bytes_per_chunk_hint * 4);

        loop {
            if event_handle.wait_for_event(1000).is_err() {
                tracing::warn!("WASAPI capture event wait timed out; stopping capture");
                break;
            }

            capture_client
                .read_from_device_to_deque(&mut sample_queue)
                .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

            while sample_queue.len() >= bytes_per_chunk_hint && bytes_per_chunk_hint > 0 {
                let pcm: Vec<u8> = sample_queue.drain(..bytes_per_chunk_hint).collect();
                let chunk = CapturedChunk {
                    format,
                    timestamp_ms: start.elapsed().as_millis() as u32,
                    pcm,
                };
                if tx.send(chunk).is_err() {
                    // Receiver dropped (app shutting down) — stop capturing.
                    let _ = audio_client.stop_stream();
                    return Ok(());
                }
            }
        }

        let _ = audio_client.stop_stream();
        Ok(())
    }
}
