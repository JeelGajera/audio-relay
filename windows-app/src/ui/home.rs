use eframe::egui;

use crate::state::{AppState, ConnectionStatus};

pub fn show(ui: &mut egui::Ui, state: &AppState) {
    ui.heading("audio-relay");
    ui.separator();

    let status = state.status.lock().unwrap().clone();
    match &status {
        ConnectionStatus::WaitingForConnection => {
            ui.label("Waiting for a phone to connect…");
            ui.add_space(6.0);
            ui.label(
                egui::RichText::new(state.current_pairing_code())
                    .monospace()
                    .size(28.0)
                    .strong(),
            );
            ui.small("Enter this in the Android app. Valid for 5 minutes.");
        }
        ConnectionStatus::Streaming { device_name } => {
            ui.colored_label(
                egui::Color32::from_rgb(70, 200, 120),
                format!("● Streaming to {device_name}"),
            );
        }
        ConnectionStatus::Disconnected { device_name } => {
            ui.colored_label(
                egui::Color32::from_rgb(220, 100, 90),
                format!("Disconnected from {device_name} — waiting to reconnect"),
            );
        }
    }

    ui.separator();

    let mut enabled = state.is_streaming_enabled();
    if ui.checkbox(&mut enabled, "Streaming enabled").changed() {
        state.set_streaming_enabled(enabled);
    }
    ui.small("Turns audio transmission on/off without dropping the pairing — capture keeps running underneath.");

    ui.separator();
    let device_id = state.config.lock().unwrap().device_id.clone();
    ui.small(format!("This device's ID: {device_id}"));
}
