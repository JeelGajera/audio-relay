//! System-audio capture. `windows_impl` uses WASAPI loopback capture and
//! only compiles on Windows; `linux_impl` uses each sink's PulseAudio
//! `.monitor` source (via the PulseAudio Simple API — see
//! `docs/architecture.md` §2.1) and only compiles on Linux, where it also
//! transparently covers PipeWire distros through `pipewire-pulse`. A stub
//! is provided for other platforms so the rest of the crate — protocol,
//! network, config — still builds and can be tested in CI on any OS (see
//! `AGENTS.md`).
//!
//! **Unverified on real hardware.** Neither backend has been exercised on a
//! physical machine outputting *and* capturing real music in this
//! repository's history — see `docs/roadmap.md` Phase 0. Treat both as
//! best-effort implementations of their documented loopback pattern, not
//! proven ones, until someone reports back with a real test. The Linux
//! backend's introspection calls (device enumeration, default-sink
//! resolution, the local-mute control) *have* been run against a live
//! `pipewire-pulse` server in a sandbox in this project's history — see
//! `linux_impl::tests::mute_round_trips_against_a_live_server` — but the
//! actual `Simple::new`/`stream.read` audio-capture path has not.

use std::sync::Arc;

use tokio::sync::mpsc::Sender;

use crate::state::{AppState, ConnectionStatus};

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
    tx: Sender<CapturedChunk>,
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

/// Whether this laptop's own output should be muted right now, per the
/// "Also play locally while relaying" setting (`AppState::play_locally_while_relaying`,
/// set from `ui/settings.rs`). Deliberately narrower than just reading that
/// setting: paused streaming (`!is_streaming_enabled()`) or not yet
/// connected to a phone (`status` isn't `Streaming`) leaves local playback
/// alone either way, since there's nothing being relayed *to* yet — muting
/// then would just be silencing the laptop for no reason.
///
/// Lives outside the platform-specific capture implementations so both
/// backends (and a unit test) share one definition of "muted" rather than
/// two loops drifting apart on the exact conditions.
#[cfg_attr(not(any(target_os = "windows", target_os = "linux")), allow(dead_code))]
fn should_mute_local_playback(state: &AppState) -> bool {
    if state.play_locally_while_relaying() || !state.is_streaming_enabled() {
        return false;
    }
    matches!(
        *state.status.lock().unwrap(),
        ConnectionStatus::Streaming { .. }
    )
}

/// How often to log sustained chunk dropping. Logging every drop would
/// itself become a load source at ~100 chunks/sec.
#[cfg_attr(not(any(target_os = "windows", target_os = "linux")), allow(dead_code))]
const DROP_LOG_INTERVAL: u64 = 100;

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
    use super::{
        rms_level, should_mute_local_playback, CaptureError, CaptureFormat, CapturedChunk,
        DROP_LOG_INTERVAL,
    };
    use crate::state::{AppState, CaptureDeviceInfo};
    use std::collections::VecDeque;
    use std::sync::Arc;
    use std::time::Instant;
    use tokio::sync::mpsc::Sender;
    use wasapi::{Device, DeviceCollection, Direction, ShareMode, WaveFormat};

    /// How long to wait for one capture event before looping to re-check
    /// device selection and shutdown. Not a deadline for audio to arrive —
    /// see the timeout handling in `capture_until_restart`.
    const EVENT_WAIT_TIMEOUT_MS: u32 = 1000;

    pub fn start(
        tx: Sender<CapturedChunk>,
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

    /// Mutes or unmutes the render endpoint identified by `device_id` (the
    /// same ID string `Device::get_id()`/`enumerate_devices` already use).
    /// Independent of the `wasapi` crate's `Device`, which doesn't expose
    /// endpoint-volume control at all — this activates `IAudioEndpointVolume`
    /// directly via `IMMDeviceEnumerator`, the standard Win32 pattern for
    /// per-endpoint mute. COM is already initialized on this thread by
    /// `wasapi::initialize_mta()` in `start()`, so this doesn't call
    /// `CoInitializeEx` itself.
    ///
    /// **Unverified without a Windows machine**, unlike the rest of this
    /// module's WASAPI calls which are at least exercised by the `wasapi`
    /// crate's own tests — this is hand-written COM interop with no way to
    /// compile-check it outside a Windows toolchain. If "Also play locally
    /// while relaying" silently does nothing on Windows, this is the first
    /// place to look.
    fn set_endpoint_mute(device_id: &str, mute: bool) -> Result<(), CaptureError> {
        use windows::core::HSTRING;
        use windows::Win32::Foundation::BOOL;
        use windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume;
        use windows::Win32::Media::Audio::{IMMDeviceEnumerator, MMDeviceEnumerator};
        use windows::Win32::System::Com::{CoCreateInstance, CLSCTX_ALL};

        unsafe {
            let enumerator: IMMDeviceEnumerator =
                CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).map_err(|e| {
                    CaptureError::Wasapi(format!("creating device enumerator: {e}"))
                })?;
            let endpoint = enumerator
                .GetDevice(&HSTRING::from(device_id))
                .map_err(|e| {
                    CaptureError::Wasapi(format!("resolving endpoint '{device_id}': {e}"))
                })?;
            let volume: IAudioEndpointVolume =
                endpoint.Activate(CLSCTX_ALL, None).map_err(|e| {
                    CaptureError::Wasapi(format!("activating IAudioEndpointVolume: {e}"))
                })?;
            volume
                .SetMute(BOOL::from(mute), std::ptr::null())
                .map_err(|e| CaptureError::Wasapi(format!("setting endpoint mute: {e}")))
        }
    }

    /// Runs one WASAPI stream until either an error occurs or
    /// `state.capture_generation()` moves past `started_at_generation`
    /// (the user picked a different device, or asked for a refresh),
    /// in which case it returns `Ok(())` and the caller loops to pick up
    /// the new selection.
    fn capture_until_restart(
        tx: &Sender<CapturedChunk>,
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
        // Best-effort, same as the Linux backend: local-mute is simply
        // unavailable for this stream if the ID can't be read, rather than
        // fatal to capture — capture is the feature that matters.
        let endpoint_id = match device.get_id() {
            Ok(id) => Some(id),
            Err(e) => {
                tracing::warn!(error = %e, "could not resolve the endpoint ID for local-mute control");
                None
            }
        };
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

        // `get_periods` returns (default, minimum). Use the **default**: in
        // shared mode the audio engine always runs at its own default
        // period, and this value is the buffer capacity we're asking for.
        // Requesting the hardware *minimum* asked for a buffer smaller than
        // one engine period, which leaves no headroom — any scheduling
        // hiccup between two reads then overruns the buffer and drops
        // audio. The minimum period is only meaningful in exclusive mode,
        // which loopback capture cannot use anyway.
        let (default_period, _min_period) = audio_client
            .get_periods()
            .map_err(|e| CaptureError::Wasapi(e.to_string()))?;

        audio_client
            .initialize_client(
                &wave_format,
                default_period,
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
        // `None` until the first chunk forces a sync — see linux_impl's
        // identical field for why "unknown" rather than assuming unmuted.
        let mut last_applied_mute: Option<bool> = None;
        let mut dropped_chunks: u64 = 0;

        loop {
            if state.capture_generation() != started_at_generation {
                let _ = audio_client.stop_stream();
                return Ok(()); // device selection changed (or a refresh was requested) — restart
            }

            let desired_mute = endpoint_id.is_some() && should_mute_local_playback(state);
            if last_applied_mute != Some(desired_mute) {
                if let Some(id) = endpoint_id.as_deref() {
                    match set_endpoint_mute(id, desired_mute) {
                        Ok(()) => last_applied_mute = Some(desired_mute),
                        Err(e) => {
                            tracing::warn!(error = %e, "failed to set local playback mute state")
                        }
                    }
                } else {
                    last_applied_mute = Some(desired_mute);
                }
            }

            if event_handle.wait_for_event(EVENT_WAIT_TIMEOUT_MS).is_err() {
                // **Not an error, and emphatically not a reason to restart.**
                // A WASAPI loopback stream only produces events while the
                // render endpoint is actually rendering; with nothing
                // playing on the laptop, the endpoint goes idle and this
                // wait simply times out. Treating that as a failure meant
                // every silent second tore the stream down and rebuilt it —
                // COM device enumeration and all — and then clipped the
                // start of whatever played next. Silence is the normal
                // resting state of this app, so just keep waiting.
                //
                // The timeout still bounds how long we can sit here, so a
                // device change or shutdown is noticed promptly rather than
                // blocking forever on an endpoint that will never fire
                // again.
                if tx.is_closed() {
                    let _ = audio_client.stop_stream();
                    return Ok(());
                }
                continue;
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
                if let Err(e) = tx.try_send(chunk) {
                    if matches!(e, tokio::sync::mpsc::error::TrySendError::Full(_)) {
                        dropped_chunks += 1;
                        if dropped_chunks % DROP_LOG_INTERVAL == 1 {
                            tracing::warn!(
                                dropped_chunks,
                                "send path is behind real time; dropping captured audio to stay current"
                            );
                        }
                        continue;
                    }
                    let _ = audio_client.stop_stream();
                    // App shutdown, not a device switch — nothing will run
                    // again to notice and fix a mute left in place.
                    if last_applied_mute == Some(true) {
                        if let Some(id) = endpoint_id.as_deref() {
                            if let Err(e) = set_endpoint_mute(id, false) {
                                tracing::warn!(error = %e, "failed to restore local playback on shutdown");
                            }
                        }
                    }
                    return Ok(()); // receiver dropped — app shutting down
                }
            }
        }
    }
}

#[cfg(target_os = "linux")]
mod linux_impl {
    use super::{
        rms_level, should_mute_local_playback, CaptureError, CaptureFormat, CapturedChunk,
        DROP_LOG_INTERVAL,
    };
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
    use tokio::sync::mpsc::Sender;

    const APP_NAME: &str = "audio-relay";
    // The wire protocol only encodes 44.1kHz or 48kHz (protocol/packet.rs
    // SampleRate); requesting 48kHz stereo directly from PulseAudio (which
    // resamples internally to match) means this backend never has to carry
    // WASAPI's "map whatever rate the endpoint hands back" logic.
    const SAMPLE_RATE_HZ: u32 = 48_000;
    const CHANNELS: u8 = 2;
    const BYTES_PER_SAMPLE: u32 = 2; // S16LE

    /// How much captured audio the server may hold for us before it starts
    /// discarding, as a multiple of one fragment. Bounds how far behind
    /// real time this stream can drift if the capture thread is descheduled
    /// — for live audio, dropping stale audio is right and letting a
    /// backlog accumulate is not, because a backlog is latency that never
    /// goes away on its own. Sixteen fragments (~80ms at a 5ms fragment)
    /// absorbs ordinary scheduling jitter without becoming a buffer.
    const MAX_BUFFERED_FRAGMENTS: u32 = 16;

    /// Record-stream buffer metrics.
    ///
    /// **Passing `None` here instead was the single worst bug in this
    /// project.** PulseAudio's documented default for a record stream's
    /// `fragsize` is "something like 2s", and the server delivers audio one
    /// fragment at a time — so capture arrived as a huge instantaneous
    /// burst followed by a long stall, rather than as a steady stream.
    /// Measured on a real PipeWire server (see `capture_delivery_cadence`):
    /// with `None`, 194 of 200 reads returned instantly and then the stream
    /// stalled for **341ms**; with an explicit fragment size, the median
    /// gap was 10.65ms with a worst case of 11.11ms.
    ///
    /// The receiver cannot paper over that. A 341ms hole needs a >341ms
    /// jitter buffer to hide, which is far more latency than this app
    /// targets — so the phone underran on almost every burst and played
    /// concealment silence instead, which is what "it cuts out constantly"
    /// actually was. It also meant every burst dumped tens of UDP packets
    /// into the network at once, which a phone hotspot answers by dropping
    /// them.
    fn record_buffer_attr(bytes_per_fragment: u32) -> libpulse_binding::def::BufferAttr {
        let fragsize = bytes_per_fragment.max(1);
        libpulse_binding::def::BufferAttr {
            fragsize,
            maxlength: fragsize.saturating_mul(MAX_BUFFERED_FRAGMENTS),
            // Playback-only fields; the server ignores them for a record
            // stream. u32::MAX means "server default" for each.
            tlength: u32::MAX,
            prebuf: u32::MAX,
            minreq: u32::MAX,
        }
    }

    /// Bytes in one fragment: the smallest chunk any latency mode asks for.
    ///
    /// Deliberately keyed to the *smallest* mode rather than the currently
    /// selected one. `fragsize` is fixed when the stream opens, but latency
    /// mode is read live and changes without reopening it (see the module
    /// docs), so sizing to the smallest keeps both modes evenly paced: a
    /// 10ms read is simply served by two 5ms fragments.
    fn fragment_bytes() -> u32 {
        let block_align = BYTES_PER_SAMPLE * CHANNELS as u32;
        block_align * SAMPLE_RATE_HZ * crate::state::LatencyMode::Low.chunk_ms() / 1000
    }

    pub fn start(
        tx: Sender<CapturedChunk>,
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

    /// Finds the sink that owns `monitor_source` (the name
    /// `resolve_monitor_source` returned). Needed because "Also play
    /// locally while relaying" (see `should_mute_local_playback`) mutes the
    /// *sink*, not the monitor source the capture stream was opened
    /// against — PulseAudio has no mute concept on a monitor source itself.
    fn find_sink_name_for_monitor(monitor_source: &str) -> Result<String, CaptureError> {
        let (mut mainloop, context) = connect_context(&format!("{APP_NAME}-find-sink"))?;

        let sink_name: Rc<RefCell<Option<String>>> = Rc::new(RefCell::new(None));
        let sink_name_cb = Rc::clone(&sink_name);
        let target = monitor_source.to_string();
        let op = context.introspect().get_sink_info_list(move |result| {
            if let ListResult::Item(info) = result {
                if info.monitor_source_name.as_deref() == Some(target.as_str()) {
                    *sink_name_cb.borrow_mut() = info.name.as_deref().map(String::from);
                }
            }
        });
        wait_for_operation(&mut mainloop, &op)?;

        Rc::try_unwrap(sink_name)
            .map(RefCell::into_inner)
            .ok()
            .flatten()
            .ok_or_else(|| {
                CaptureError::Pulse(format!("no sink owns monitor source '{monitor_source}'"))
            })
    }

    /// Mutes or unmutes `sink_name`. A short-lived context/mainloop like
    /// every other introspection call here — this only runs on an actual
    /// change (see `capture_until_restart`'s loop), not per chunk.
    fn set_sink_mute(sink_name: &str, mute: bool) -> Result<(), CaptureError> {
        let (mut mainloop, context) = connect_context(&format!("{APP_NAME}-mute"))?;
        let op = context
            .introspect()
            .set_sink_mute_by_name(sink_name, mute, None);
        wait_for_operation(&mut mainloop, &op)
    }

    /// Runs one PulseAudio Simple-API record stream until either an error
    /// occurs or `state.capture_generation()` moves past
    /// `started_at_generation`, mirroring `windows_impl::capture_until_restart`.
    /// The Simple API's blocking `read()` has no way to be interrupted
    /// mid-call, so a generation change is only noticed between chunks —
    /// at most one chunk's worth of latency (a few milliseconds), which is
    /// an acceptable restart delay.
    fn capture_until_restart(
        tx: &Sender<CapturedChunk>,
        state: &Arc<AppState>,
        started_at_generation: u64,
    ) -> Result<(), CaptureError> {
        // Unlike the Windows backend's `open_selected_device`, a selected
        // device here is just a monitor-source *name* string with no live
        // handle to notice going away on its own — e.g. the Bluetooth sink
        // it names gets removed on disconnect. So the fallback has to be
        // done explicitly here: if enumeration succeeded and the selection
        // isn't in the fresh list, treat it as "system default" for this
        // attempt rather than retrying a monitor source that no longer
        // exists forever.
        let selected_id = state.selected_capture_device_id();
        let effective_selected_id = match enumerate_devices() {
            Ok(devices) => {
                state.publish_capture_devices(devices.clone());
                match selected_id.as_deref() {
                    Some(id) if !devices.iter().any(|d| d.id == id) => {
                        tracing::warn!(
                            device_id = id,
                            "selected capture device no longer present; falling back to default"
                        );
                        None
                    }
                    other => other,
                }
            }
            Err(e) => {
                tracing::warn!(error = %e, "failed to enumerate output devices");
                selected_id.as_deref()
            }
        };
        let monitor_source = resolve_monitor_source(effective_selected_id)?;
        // Best-effort: if the owning sink can't be found (e.g. a monitor
        // source PulseAudio exposes without a normal sink behind it), local
        // mute is simply unavailable for this stream rather than fatal to
        // capture — capture is the feature that matters.
        let sink_name = match find_sink_name_for_monitor(&monitor_source) {
            Ok(name) => Some(name),
            Err(e) => {
                tracing::warn!(error = %e, "could not resolve the sink for local-mute control");
                None
            }
        };

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

        // See `record_buffer_attr` — passing `None` here let the server pick
        // a ~2s fragment, which made capture arrive in bursts separated by
        // hundreds of milliseconds of nothing.
        let buffer_attr = record_buffer_attr(fragment_bytes());
        let stream = Simple::new(
            None,
            APP_NAME,
            StreamDirection::Record,
            Some(&monitor_source),
            "system audio loopback",
            &spec,
            None,
            Some(&buffer_attr),
        )
        .map_err(|e| {
            CaptureError::Pulse(format!("opening monitor stream '{monitor_source}': {e}"))
        })?;

        let start = Instant::now();
        let block_align = BYTES_PER_SAMPLE * CHANNELS as u32;
        // `None` until the first chunk forces a sync — see the loop below.
        // Starting unknown rather than assuming "unmuted" means a restart
        // (any reason: device change, a transient error, or the sink coming
        // back after being gone) always re-syncs the real mute state on its
        // first chunk instead of silently trusting a fresh `false` that
        // might not match what the sink is actually still set to.
        let mut last_applied_mute: Option<bool> = None;
        let mut dropped_chunks: u64 = 0;

        loop {
            if state.capture_generation() != started_at_generation {
                return Ok(()); // device selection changed (or a refresh was requested) — restart
            }

            let desired_mute = sink_name.is_some() && should_mute_local_playback(state);
            if last_applied_mute != Some(desired_mute) {
                if let Some(name) = sink_name.as_deref() {
                    match set_sink_mute(name, desired_mute) {
                        Ok(()) => last_applied_mute = Some(desired_mute),
                        Err(e) => {
                            tracing::warn!(error = %e, "failed to set local playback mute state")
                        }
                    }
                } else {
                    last_applied_mute = Some(desired_mute);
                }
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
            // try_send, never a blocking send: if the consumer is behind,
            // the right move for live audio is to drop the newest chunk and
            // stay current, not to stall capture (which would back up into
            // PulseAudio) or queue the chunk (which would become permanent
            // added latency).
            if let Err(e) = tx.try_send(chunk) {
                if matches!(e, tokio::sync::mpsc::error::TrySendError::Full(_)) {
                    dropped_chunks += 1;
                    if dropped_chunks % DROP_LOG_INTERVAL == 1 {
                        tracing::warn!(
                            dropped_chunks,
                            "send path is behind real time; dropping captured audio to stay current"
                        );
                    }
                    continue;
                }
                // The app is shutting down, not just switching devices —
                // unlike a generation-change restart, nothing will run
                // again to notice and fix a sink left muted behind us.
                if last_applied_mute == Some(true) {
                    if let Some(name) = sink_name.as_deref() {
                        if let Err(e) = set_sink_mute(name, false) {
                            tracing::warn!(error = %e, "failed to restore local playback on shutdown");
                        }
                    }
                }
                return Ok(()); // receiver dropped — app shutting down
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn fragment_is_one_low_latency_chunk_and_bounded() {
            let frag = fragment_bytes();
            // 5ms of 48kHz stereo 16-bit = 960 bytes.
            assert_eq!(frag, 960);
            let attr = record_buffer_attr(frag);
            assert_eq!(attr.fragsize, frag);
            // Bounded, so a descheduled capture thread cannot accumulate
            // latency indefinitely...
            assert_eq!(attr.maxlength, frag * MAX_BUFFERED_FRAGMENTS);
            // ...but still generous enough to ride out normal jitter.
            let block_align = BYTES_PER_SAMPLE * CHANNELS as u32;
            let max_buffered_ms = attr.maxlength / (block_align * SAMPLE_RATE_HZ / 1000);
            assert!(
                (40..=200).contains(&max_buffered_ms),
                "capture-side buffer of {max_buffered_ms}ms is outside the sane range"
            );
        }

        /// A zero fragment size would make PulseAudio reject the stream.
        #[test]
        fn a_degenerate_fragment_size_is_clamped() {
            assert_eq!(record_buffer_attr(0).fragsize, 1);
        }

        /// Reports the capture stream's own latency — how far behind live
        /// the audio we read already is, before it has even been sent.
        /// `cargo test -- --ignored --nocapture capture_latency_budget`
        #[test]
        #[ignore]
        fn capture_latency_budget() {
            let block_align = BYTES_PER_SAMPLE * CHANNELS as u32;
            let bytes_per_chunk = (block_align * SAMPLE_RATE_HZ * 10 / 1000) as usize;
            let monitor = resolve_monitor_source(None).expect("no default monitor source");
            let spec = Spec {
                format: Format::S16le,
                channels: CHANNELS,
                rate: SAMPLE_RATE_HZ,
            };

            for (label, attr) in [
                ("attr = None", None),
                (
                    "fragsize only (current)",
                    Some(record_buffer_attr(fragment_bytes())),
                ),
            ] {
                let stream = Simple::new(
                    None,
                    APP_NAME,
                    StreamDirection::Record,
                    Some(&monitor),
                    "latency probe",
                    &spec,
                    None,
                    attr.as_ref(),
                )
                .expect("open failed");
                let mut pcm = vec![0u8; bytes_per_chunk];
                for _ in 0..50 {
                    stream.read(&mut pcm).unwrap();
                }
                let mut samples = Vec::new();
                for _ in 0..30 {
                    stream.read(&mut pcm).unwrap();
                    if let Ok(l) = stream.get_latency() {
                        samples.push(l.0 as f64 / 1000.0);
                    }
                }
                samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
                println!(
                    "  {label:28} capture latency: median {:.1}ms  max {:.1}ms",
                    samples[samples.len() / 2],
                    samples.last().unwrap()
                );
            }
        }

        /// Measures how *evenly* the capture stream actually delivers audio,
        /// which is the thing that decides whether a small jitter buffer on
        /// the phone can work at all. `#[ignore]`d (needs a live server):
        /// `cargo test -- --ignored --nocapture capture_delivery_cadence`
        ///
        /// Reports the gap between consecutive fixed-size reads. Smooth
        /// delivery means every gap is ~= the chunk duration. Bursty
        /// delivery — many instant reads then a long stall — means the
        /// server is handing us one big fragment at a time, and no
        /// reasonable receive buffer can smooth that out.
        #[test]
        #[ignore]
        fn capture_delivery_cadence() {
            let chunk_ms = 10u32;
            let block_align = BYTES_PER_SAMPLE * CHANNELS as u32;
            let bytes_per_chunk = (block_align * SAMPLE_RATE_HZ * chunk_ms / 1000) as usize;

            for (label, attr) in [
                ("attr = None (what the app shipped with)", None),
                (
                    "explicit fragsize = one chunk",
                    Some(libpulse_binding::def::BufferAttr {
                        maxlength: u32::MAX,
                        fragsize: bytes_per_chunk as u32,
                        ..Default::default()
                    }),
                ),
            ] {
                let monitor = resolve_monitor_source(None).expect("no default monitor source");
                let spec = Spec {
                    format: Format::S16le,
                    channels: CHANNELS,
                    rate: SAMPLE_RATE_HZ,
                };
                let stream = Simple::new(
                    None,
                    APP_NAME,
                    StreamDirection::Record,
                    Some(&monitor),
                    "cadence probe",
                    &spec,
                    None,
                    attr.as_ref(),
                )
                .expect("could not open the monitor stream");

                // Discard the first reads: stream startup is not steady state.
                let mut pcm = vec![0u8; bytes_per_chunk];
                for _ in 0..10 {
                    stream.read(&mut pcm).unwrap();
                }

                let mut gaps_ms = Vec::new();
                let mut last = Instant::now();
                for _ in 0..200 {
                    stream.read(&mut pcm).unwrap();
                    let now = Instant::now();
                    gaps_ms.push(now.duration_since(last).as_secs_f64() * 1000.0);
                    last = now;
                }

                gaps_ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
                let median = gaps_ms[gaps_ms.len() / 2];
                let p99 = gaps_ms[gaps_ms.len() * 99 / 100];
                let max = *gaps_ms.last().unwrap();
                // A read that returns in well under the chunk duration was
                // already buffered, i.e. part of a burst rather than paced.
                let instant = gaps_ms
                    .iter()
                    .filter(|g| **g < chunk_ms as f64 / 4.0)
                    .count();

                println!(
                    "\n{label}\n  chunk = {chunk_ms}ms ({bytes_per_chunk}B)\n  \
                     median gap {median:.2}ms | p99 {p99:.2}ms | max {max:.2}ms\n  \
                     {instant}/200 reads returned instantly (burst), \
                     longest stall {max:.1}ms = {:.1}x the chunk",
                    max / chunk_ms as f64
                );
            }
        }

        /// Manual verification against a live PulseAudio/PipeWire server —
        /// `#[ignore]`d since CI has no audio daemon. Run explicitly on a
        /// machine that does:
        /// `cargo test -- --ignored --nocapture mute_round_trips_against_a_live_server`.
        /// Confirms the actual OS-level effect via `wpctl` (not just that
        /// the PulseAudio calls return `Ok`), and leaves the sink exactly
        /// as it found it either way.
        #[test]
        #[ignore]
        fn mute_round_trips_against_a_live_server() {
            let monitor = resolve_monitor_source(None).expect("no default sink monitor source");
            let sink =
                find_sink_name_for_monitor(&monitor).expect("no sink owns that monitor source");

            let is_muted = || {
                let out = std::process::Command::new("wpctl")
                    .args(["get-volume", "@DEFAULT_AUDIO_SINK@"])
                    .output()
                    .expect("wpctl not available to verify with");
                String::from_utf8_lossy(&out.stdout).contains("MUTED")
            };

            let was_muted = is_muted();

            set_sink_mute(&sink, true).expect("mute call failed");
            assert!(
                is_muted(),
                "wpctl did not report MUTED after set_sink_mute(true)"
            );

            set_sink_mute(&sink, false).expect("unmute call failed");
            assert!(
                !is_muted(),
                "wpctl still reported MUTED after set_sink_mute(false)"
            );

            // Restore exactly what was there before, in case this ran on a
            // developer's real machine and it started out muted.
            if was_muted {
                set_sink_mute(&sink, true).ok();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{rms_level, should_mute_local_playback};
    use crate::config::Config;
    use crate::state::{AppState, ConnectionStatus};

    #[test]
    fn does_not_mute_when_play_locally_is_on() {
        let state = AppState::new(Config::default());
        state.set_status(ConnectionStatus::Streaming {
            device_name: "Pixel".into(),
        });
        assert!(state.play_locally_while_relaying(), "defaults on");
        assert!(!should_mute_local_playback(&state));
    }

    #[test]
    fn does_not_mute_while_paused_even_with_play_locally_off() {
        let state = AppState::new(Config::default());
        state.set_play_locally_while_relaying(false);
        state.set_streaming_enabled(false);
        state.set_status(ConnectionStatus::Streaming {
            device_name: "Pixel".into(),
        });
        assert!(!should_mute_local_playback(&state));
    }

    #[test]
    fn does_not_mute_before_a_phone_is_actually_streaming() {
        let state = AppState::new(Config::default());
        state.set_play_locally_while_relaying(false);
        assert!(!should_mute_local_playback(&state)); // still WaitingForConnection
    }

    #[test]
    fn mutes_only_once_actually_streaming_with_play_locally_off() {
        let state = AppState::new(Config::default());
        state.set_play_locally_while_relaying(false);
        state.set_status(ConnectionStatus::Streaming {
            device_name: "Pixel".into(),
        });
        assert!(should_mute_local_playback(&state));
    }

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
