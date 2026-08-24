//! Minimal status window (Phase 6 of `docs/roadmap.md` — functional, not
//! polished; a latency-mode toggle and richer pairing UI are tracked as
//! follow-ups there). Runs on the main thread via `eframe`; the network/
//! capture pipeline runs on a background tokio runtime. The two talk only
//! through the shared `AppState` — the UI never blocks on network I/O.

use std::sync::Arc;

use crate::state::{AppState, ConnectionStatus};

pub struct StatusApp {
    state: Arc<AppState>,
}

impl StatusApp {
    pub fn new(state: Arc<AppState>) -> Self {
        StatusApp { state }
    }

    pub fn run(self) -> eframe::Result<()> {
        let options = eframe::NativeOptions {
            viewport: eframe::egui::ViewportBuilder::default().with_inner_size([360.0, 220.0]),
            ..Default::default()
        };
        eframe::run_native("audio-relay", options, Box::new(|_cc| Box::new(self)))
    }
}

impl eframe::App for StatusApp {
    fn update(&mut self, ctx: &eframe::egui::Context, _frame: &mut eframe::Frame) {
        // Cheap poll-based refresh — a heartbeat/session change should show
        // up within a frame or two. Simpler and plenty responsive for a
        // status window; avoids wiring a second channel just for repaints.
        ctx.request_repaint_after(std::time::Duration::from_millis(500));

        eframe::egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("audio-relay");
            ui.separator();

            let status = self.state.status.lock().unwrap().clone();
            match &status {
                ConnectionStatus::WaitingForConnection => {
                    ui.label("Waiting for a phone to connect…");
                    ui.label(format!(
                        "Pairing code: {}",
                        self.state.current_pairing_code()
                    ));
                    ui.small("Enter this in the Android app. Valid for 5 minutes.");
                }
                ConnectionStatus::Streaming { device_name } => {
                    ui.colored_label(
                        eframe::egui::Color32::from_rgb(70, 200, 120),
                        format!("● Streaming to {device_name}"),
                    );
                }
                ConnectionStatus::Disconnected { device_name } => {
                    ui.colored_label(
                        eframe::egui::Color32::from_rgb(220, 100, 90),
                        format!("Disconnected from {device_name} — waiting to reconnect"),
                    );
                }
            }

            ui.separator();

            let mut enabled = self.state.is_streaming_enabled();
            if ui.checkbox(&mut enabled, "Streaming enabled").changed() {
                self.state.set_streaming_enabled(enabled);
            }

            ui.separator();
            let device_id = self.state.config.lock().unwrap().device_id.clone();
            ui.small(format!("Device ID: {device_id}"));
        });
    }
}
