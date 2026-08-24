// Release builds are GUI apps: without this, double-clicking the .exe opens a
// console window behind the UI. Debug builds keep the console so `tracing`
// output is visible while developing.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod capture;
mod config;
mod network;
mod protocol;
mod state;
mod ui;

use std::sync::Arc;

use tokio::sync::mpsc;
use tracing::{error, info, warn};

use capture::CapturedChunk;
use config::Config;
use network::audio_sender::AudioSender;
use protocol::packet::SampleRate;
use state::AppState;

/// TCP control port and (via mDNS TXT/HELLO) the base for the UDP audio
/// port. Not user-configurable yet — see docs/roadmap.md Phase 6 for the
/// latency-mode toggle and any future settings UI.
const CONTROL_PORT: u16 = 45108;

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env().add_directive(
                "audio_relay_windows=info"
                    .parse()
                    .expect("valid tracing directive"),
            ),
        )
        .init();

    let config = Config::load().unwrap_or_else(|e| {
        warn!(error = %e, "failed to load config, starting fresh");
        Config::default()
    });
    if let Err(e) = config.save() {
        warn!(error = %e, "failed to persist initial config");
    }
    let state = AppState::new(config);

    // Network + capture run on a background tokio runtime; the UI owns the
    // main thread (eframe wants it on most platforms). They only ever
    // touch each other through `state`, so this split is safe.
    let net_state = state.clone();
    std::thread::Builder::new()
        .name("audio-relay-net".into())
        .spawn(move || {
            let rt = tokio::runtime::Runtime::new().expect("failed to start tokio runtime");
            rt.block_on(run_network(net_state));
        })
        .expect("failed to spawn network thread");

    if let Err(e) = ui::StatusApp::new(state).run() {
        error!(error = %e, "UI exited with an error");
    }
}

async fn run_network(state: Arc<AppState>) {
    let device_id = state.config.lock().unwrap().device_id.clone();
    let device_name = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "audio-relay-laptop".to_string());

    // Keep the mDNS daemon alive for the process lifetime — dropping it
    // unregisters the advertisement.
    let _mdns_daemon = match network::discovery::advertise(&device_id, &device_name, CONTROL_PORT) {
        Ok(daemon) => Some(daemon),
        Err(e) => {
            warn!(error = %e, "mDNS advertisement failed to start; phone will need manual discovery");
            None
        }
    };

    let (tx, mut rx) = mpsc::unbounded_channel::<CapturedChunk>();
    match capture::start_capture(tx, state.clone()) {
        Ok(_handle) => info!("audio capture started"),
        Err(e) => warn!(error = %e, "audio capture unavailable on this platform/build"),
    }

    let sender_state = state.clone();
    tokio::spawn(async move {
        let mut sender = match AudioSender::bind().await {
            Ok(s) => s,
            Err(e) => {
                error!(error = %e, "failed to bind UDP audio socket");
                return;
            }
        };
        while let Some(chunk) = rx.recv().await {
            let sample_rate = match chunk.format.sample_rate_hz {
                44_100 => SampleRate::Hz44100,
                _ => SampleRate::Hz48000,
            };
            if let Err(e) = sender
                .send_frame(
                    &sender_state,
                    sample_rate,
                    chunk.format.channels,
                    chunk.timestamp_ms,
                    &chunk.pcm,
                )
                .await
            {
                warn!(error = %e, "failed to send an audio frame");
            }
        }
    });

    if let Err(e) = network::control_channel::run(state, CONTROL_PORT).await {
        error!(error = %e, "control channel server exited");
    }
}
