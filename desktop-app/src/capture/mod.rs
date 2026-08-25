//! System-audio capture. `windows_impl` uses WASAPI loopback capture and
//! only compiles on Windows; `linux_impl` uses each sink's PulseAudio
//! `.monitor` source (via the PulseAudio Simple API — see
//! `docs/architecture.md` §2.1) and only compiles on Linux, where it also
//! transparently covers PipeWire distros through `pipewire-pulse`. A stub
//! is provided for other platforms so the rest of the crate — protocol,
//! network, config — still builds and can be tested in CI on any OS (see
//! `AGENTS.md`).
//!
//! **Unverified on real hardware.** Neither backend has been exercised
//! against a physical machine in this repository's history — see
//! `docs/roadmap.md` Phase 0. Treat both as best-effort implementations of
//! their documented loopback pattern, not proven ones, until someone
//! reports back with a real test. The Linux backend is additionally
//! unverified against any actual PulseAudio/PipeWire *server* even in CI —
//! only that it compiles and links against `libpulse-dev` — since no
//! sandbox in this project's history has had one running.

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
    // Only ever constructed by the stub below, on platforms with neither a
    // WASAPI nor a PulseAudio backend.
    #[cfg_attr(any(target_os = "windows", target_os = "linux"), allow(dead_code))]
    #[error("loopback capture is only implemented on Windows and Linux")]
    UnsupportedPlatform,
    #[cfg(target_os = "windows")]
    #[error("WASAPI error: {0}")]
    Wasapi(String),
    #[cfg(target_os = "linux")]
    #[error("PulseAudio error: {0}")]
    Pulse(String),
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
    #[cfg(target_os = "linux")]
    {
        linux_impl::start(tx, state)
    }
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    {
        let _ = (tx, state);
        Err(CaptureError::UnsupportedPlatform)
    }
}

/// Loudness of one chunk as a 0.0..=1.0 meter reading.
///
/// Not raw RMS: a linear bar sits almost at the floor for ordinary listening
/// levels, because loudness is perceived logarithmically. This maps
/// -60dBFS..0dBFS onto the full bar, which is the range a level meter is
/// actually useful over.
///
/// Lives outside the platform-specific capture implementations so it can be
/// tested on any platform — which is also why it needs a `dead_code` allow
/// on platforms with neither backend: only the Windows and Linux capture
/// loops call it outside of tests.
#[cfg_attr(not(any(target_os = "windows", target_os = "linux")), allow(dead_code))]
pub(crate) fn rms_level(pcm: &[u8]) -> f32 {
    let mut sum_squares = 0.0f64;
    let mut samples = 0u32;
    // as_chunks discards a trailing odd byte rather than panicking; a partial
    // sample can't be interpreted anyway.
    let (samples_le, _remainder) = pcm.as_chunks::<2>();
    for sample in samples_le {
        let value = i16::from_le_bytes(*sample) as f64 / 32768.0;
        sum_squares += value * value;
        samples += 1;
    }
    if samples == 0 {
        return 0.0;
    }

    let rms = (sum_squares / samples as f64).sqrt() as f32;
    if rms <= 1e-6 {
        return 0.0;
    }
    let dbfs = 20.0 * rms.log10();
    ((dbfs + 60.0) / 60.0).clamp(0.0, 1.0)
}

#[cfg(target_os = "windows")]
mod windows_impl {
    use super::{rms_level, CaptureError, CaptureFormat, CapturedChunk};
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
                (wave_format.get_blockalign() * wave_format.get_samplespersec() * chunk_ms / 1000)
                    as usize;

            while bytes_per_chunk > 0 && sample_queue.len() >= bytes_per_chunk {
                let pcm: Vec<u8> = sample_queue.drain(..bytes_per_chunk).collect();
                state.set_audio_level(rms_level(&pcm));
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

#[cfg(target_os = "linux")]
mod linux_impl {
    use super::{rms_level, CaptureError, CaptureFormat, CapturedChunk};
    use crate::state::{AppState, CaptureDeviceInfo};
    use libpulse_binding::callbacks::ListResult;
    use libpulse_binding::context::{Context, FlagSet as ContextFlagSet, State as ContextState};
    use libpulse_binding::mainloop::standard::{IterateResult, Mainloop};
    use libpulse_binding::operation::{Operation, State as OperationState};
    use libpulse_binding::sample::{Format, Spec};
    use libpulse_binding::stream::Direction as StreamDirection;
    use libpulse_simple_binding::Simple;
    use std::cell::RefCell;
    use std::rc::Rc;
    use std::sync::Arc;
    use std::time::Instant;
    use tokio::sync::mpsc::UnboundedSender;

    const APP_NAME: &str = "audio-relay";
    // The wire protocol only encodes 44.1kHz or 48kHz (protocol/packet.rs
    // SampleRate); requesting 48kHz stereo directly from PulseAudio (which
    // resamples internally to match) means this backend never has to carry
    // WASAPI's "map whatever rate the endpoint hands back" logic.
    const SAMPLE_RATE_HZ: u32 = 48_000;
    const CHANNELS: u8 = 2;
    const BYTES_PER_SAMPLE: u32 = 2; // S16LE

    pub fn start(
        tx: UnboundedSender<CapturedChunk>,
        state: Arc<AppState>,
    ) -> Result<std::thread::JoinHandle<()>, CaptureError> {
        let handle = std::thread::Builder::new()
            .name("pulse-monitor-capture".into())
            .spawn(move || loop {
                let generation = state.capture_generation();
                if let Err(e) = capture_until_restart(&tx, &state, generation) {
                    tracing::warn!(error = %e, "capture stream ended; retrying in 1s");
                    std::thread::sleep(std::time::Duration::from_secs(1));
                }
                if tx.is_closed() {
                    return; // app shutting down
                }
            })
            .map_err(|e| CaptureError::Pulse(e.to_string()))?;
        Ok(handle)
    }

    /// Connects a fresh `Context` and blocks (via the standard mainloop's
    /// synchronous `iterate`) until it's `Ready`. Each caller gets its own
    /// short-lived mainloop/context pair — this is only used for the
    /// occasional introspection call, not held open across the capture
    /// loop, so there's no long-running event loop to manage.
    fn connect_context(name: &str) -> Result<(Mainloop, Context), CaptureError> {
        let mut mainloop = Mainloop::new()
            .ok_or_else(|| CaptureError::Pulse("failed to create PulseAudio mainloop".into()))?;
        let mut context = Context::new(&mainloop, name)
            .ok_or_else(|| CaptureError::Pulse("failed to create PulseAudio context".into()))?;
        context
            .connect(None, ContextFlagSet::NOFLAGS, None)
            .map_err(|e| CaptureError::Pulse(format!("connecting to PulseAudio server: {e}")))?;

        loop {
            match mainloop.iterate(true) {
                IterateResult::Success(_) => {}
                IterateResult::Quit(_) | IterateResult::Err(_) => {
                    return Err(CaptureError::Pulse(
                        "PulseAudio mainloop terminated while connecting".into(),
                    ));
                }
            }
            match context.get_state() {
                ContextState::Ready => return Ok((mainloop, context)),
                ContextState::Failed | ContextState::Terminated => {
                    return Err(CaptureError::Pulse(
                        "PulseAudio server connection failed".into(),
                    ));
                }
                _ => {}
            }
        }
    }

    /// Blocks until an introspection `Operation` finishes, driving the
    /// mainloop that will eventually invoke its callback.
    fn wait_for_operation<G: ?Sized>(
        mainloop: &mut Mainloop,
        op: &Operation<G>,
    ) -> Result<(), CaptureError> {
        loop {
            match op.get_state() {
                OperationState::Done => return Ok(()),
                OperationState::Cancelled => {
                    return Err(CaptureError::Pulse(
                        "PulseAudio operation was cancelled".into(),
                    ));
                }
                OperationState::Running => {}
            }
            match mainloop.iterate(true) {
                IterateResult::Success(_) => {}
                IterateResult::Quit(_) | IterateResult::Err(_) => {
                    return Err(CaptureError::Pulse(
                        "PulseAudio mainloop terminated mid-operation".into(),
                    ));
                }
            }
        }
    }

    /// Lists every sink (output device) that has a monitor source, i.e.
    /// every sink this backend can actually capture from. `CaptureDeviceInfo`
    /// stores the monitor source name in `id` — that's the exact string
    /// `Simple::new`'s `dev` parameter needs — and the sink's description in
    /// `name`, for the Settings picker.
    fn enumerate_devices() -> Result<Vec<CaptureDeviceInfo>, CaptureError> {
        let (mut mainloop, context) = connect_context(&format!("{APP_NAME}-enumerate"))?;

        let devices: Rc<RefCell<Vec<CaptureDeviceInfo>>> = Rc::new(RefCell::new(Vec::new()));
        let devices_cb = Rc::clone(&devices);
        let op = context.introspect().get_sink_info_list(move |result| {
            if let ListResult::Item(info) = result {
                if let Some(monitor) = info.monitor_source_name.as_deref() {
                    let name = info
                        .description
                        .as_deref()
                        .or(info.name.as_deref())
                        .unwrap_or("Unknown output")
                        .to_string();
                    devices_cb.borrow_mut().push(CaptureDeviceInfo {
                        id: monitor.to_string(),
                        name,
                    });
                }
            }
        });
        wait_for_operation(&mut mainloop, &op)?;

        Ok(Rc::try_unwrap(devices)
            .map(RefCell::into_inner)
            .unwrap_or_default())
    }

    /// Resolves a device selection to the monitor-source name `Simple::new`
    /// needs: the selection itself if one was made, otherwise the system
    /// default sink's monitor source (deliberately *not*
    /// `default_source_name`, which is a microphone, not an output).
    fn resolve_monitor_source(selected_id: Option<&str>) -> Result<String, CaptureError> {
        if let Some(id) = selected_id {
            return Ok(id.to_string());
        }

        let (mut mainloop, context) = connect_context(&format!("{APP_NAME}-default-sink"))?;

        let default_sink: Rc<RefCell<Option<String>>> = Rc::new(RefCell::new(None));
        let default_sink_cb = Rc::clone(&default_sink);
        let op = context.introspect().get_server_info(move |info| {
            *default_sink_cb.borrow_mut() = info.default_sink_name.as_deref().map(String::from);
        });
        wait_for_operation(&mut mainloop, &op)?;
        let sink_name = Rc::try_unwrap(default_sink)
            .map(RefCell::into_inner)
            .ok()
            .flatten()
            .ok_or_else(|| CaptureError::Pulse("no default output sink reported".into()))?;

        let monitor: Rc<RefCell<Option<String>>> = Rc::new(RefCell::new(None));
        let monitor_cb = Rc::clone(&monitor);
        let op = context
            .introspect()
            .get_sink_info_by_name(&sink_name, move |result| {
                if let ListResult::Item(info) = result {
                    *monitor_cb.borrow_mut() =
                        info.monitor_source_name.as_deref().map(String::from);
                }
            });
        wait_for_operation(&mut mainloop, &op)?;

        Rc::try_unwrap(monitor)
            .map(RefCell::into_inner)
            .ok()
            .flatten()
            .ok_or_else(|| {
                CaptureError::Pulse(format!("default sink '{sink_name}' has no monitor source"))
            })
    }

    /// Runs one PulseAudio Simple-API record stream until either an error
    /// occurs or `state.capture_generation()` moves past
    /// `started_at_generation`, mirroring `windows_impl::capture_until_restart`.
    /// The Simple API's blocking `read()` has no way to be interrupted
    /// mid-call, so a generation change is only noticed between chunks —
    /// at most one chunk's worth of latency (a few milliseconds), which is
    /// an acceptable restart delay.
    fn capture_until_restart(
        tx: &UnboundedSender<CapturedChunk>,
        state: &Arc<AppState>,
        started_at_generation: u64,
    ) -> Result<(), CaptureError> {
        match enumerate_devices() {
            Ok(devices) => state.publish_capture_devices(devices),
            Err(e) => tracing::warn!(error = %e, "failed to enumerate output devices"),
        }

        let selected_id = state.selected_capture_device_id();
        let monitor_source = resolve_monitor_source(selected_id.as_deref())?;

        let spec = Spec {
            format: Format::S16le,
            channels: CHANNELS,
            rate: SAMPLE_RATE_HZ,
        };
        if !spec.is_valid() {
            return Err(CaptureError::Pulse("invalid PulseAudio sample spec".into()));
        }
        let format = CaptureFormat {
            sample_rate_hz: SAMPLE_RATE_HZ,
            channels: CHANNELS,
        };

        let stream = Simple::new(
            None,
            APP_NAME,
            StreamDirection::Record,
            Some(&monitor_source),
            "system audio loopback",
            &spec,
            None,
            None,
        )
        .map_err(|e| {
            CaptureError::Pulse(format!("opening monitor stream '{monitor_source}': {e}"))
        })?;

        let start = Instant::now();
        let block_align = BYTES_PER_SAMPLE * CHANNELS as u32;

        loop {
            if state.capture_generation() != started_at_generation {
                return Ok(()); // device selection changed (or a refresh was requested) — restart
            }

            // Read live, not cached at stream-start — see module doc.
            let chunk_ms = state.latency_mode().chunk_ms();
            let bytes_per_chunk = (block_align * SAMPLE_RATE_HZ * chunk_ms / 1000) as usize;
            if bytes_per_chunk == 0 {
                continue;
            }

            let mut pcm = vec![0u8; bytes_per_chunk];
            stream
                .read(&mut pcm)
                .map_err(|e| CaptureError::Pulse(format!("reading from monitor stream: {e}")))?;

            state.set_audio_level(rms_level(&pcm));
            let chunk = CapturedChunk {
                format,
                timestamp_ms: start.elapsed().as_millis() as u32,
                pcm,
            };
            if tx.send(chunk).is_err() {
                return Ok(()); // receiver dropped — app shutting down
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::rms_level;

    fn pcm(samples: &[i16]) -> Vec<u8> {
        samples.iter().flat_map(|s| s.to_le_bytes()).collect()
    }

    #[test]
    fn silence_reads_zero() {
        assert_eq!(rms_level(&pcm(&[0; 64])), 0.0);
    }

    #[test]
    fn empty_input_is_not_a_panic() {
        assert_eq!(rms_level(&[]), 0.0);
        assert_eq!(rms_level(&[0x00]), 0.0); // half a sample
    }

    #[test]
    fn a_trailing_odd_byte_is_ignored_rather_than_misread() {
        let mut buffer = pcm(&[i16::MAX; 8]);
        buffer.push(0x7F);
        assert!((rms_level(&buffer) - 1.0).abs() < 0.01);
    }

    #[test]
    fn full_scale_reads_full() {
        // Alternating +/-full scale is the loudest signal representable.
        let samples: Vec<i16> = (0..64)
            .map(|i| if i % 2 == 0 { i16::MAX } else { i16::MIN + 1 })
            .collect();
        assert!((rms_level(&pcm(&samples)) - 1.0).abs() < 0.01);
    }

    /// Halving amplitude is -6dB, which on a 60dB scale is 0.9 of the bar.
    #[test]
    fn halving_amplitude_drops_about_six_decibels() {
        let loud: Vec<i16> = (0..64)
            .map(|i| if i % 2 == 0 { i16::MAX } else { i16::MIN + 1 })
            .collect();
        let quiet: Vec<i16> = loud.iter().map(|s| s / 2).collect();
        let delta = rms_level(&pcm(&loud)) - rms_level(&pcm(&quiet));
        assert!(
            (delta - 0.1).abs() < 0.01,
            "expected ~0.1 of the bar, got {delta}"
        );
    }

    #[test]
    fn output_is_always_within_the_meter_range() {
        for amplitude in [0, 1, 100, 5_000, i16::MAX] {
            let level = rms_level(&pcm(&[amplitude; 32]));
            assert!((0.0..=1.0).contains(&level), "{amplitude} produced {level}");
        }
    }

    /// Anything below the -60dBFS floor pins to zero rather than going negative.
    #[test]
    fn very_quiet_signals_pin_to_the_floor() {
        assert_eq!(rms_level(&pcm(&[1; 128])), 0.0);
    }
}
