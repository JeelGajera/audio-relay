use eframe::egui;

use super::theme::{self, space};
use super::widgets;
use crate::state::{AppState, ConnectionStatus};

pub fn show(ui: &mut egui::Ui, state: &AppState) {
    let p = theme::palette(ui.ctx());
    let status = state.status.lock().unwrap().clone();

    ui.label(
        egui::RichText::new(heading_for(&status))
            .text_style(theme::text::display())
            .strong()
            .color(p.text_primary),
    );
    ui.add_space(space::XS);
    widgets::muted_label(ui, subheading_for(&status));
    ui.add_space(space::XL);

    match &status {
        ConnectionStatus::WaitingForConnection => pairing_card(ui, state),
        ConnectionStatus::Streaming { device_name } => {
            streaming_card(ui, state, device_name);
        }
        ConnectionStatus::Disconnected { device_name } => {
            disconnected_card(ui, device_name);
        }
    }

    ui.add_space(space::LG);
    output_card(ui, state);

    ui.add_space(space::LG);
    ui.label(
        egui::RichText::new(format!(
            "This laptop's device ID: {}",
            state.config.lock().unwrap().device_id
        ))
        .text_style(theme::text::small())
        .color(p.text_muted),
    );
}

fn heading_for(status: &ConnectionStatus) -> &'static str {
    match status {
        ConnectionStatus::WaitingForConnection => "Ready to pair",
        ConnectionStatus::Streaming { .. } => "Streaming",
        ConnectionStatus::Disconnected { .. } => "Disconnected",
    }
}

fn subheading_for(status: &ConnectionStatus) -> &'static str {
    match status {
        ConnectionStatus::WaitingForConnection => {
            "Open audio-relay on your phone and enter the code below."
        }
        ConnectionStatus::Streaming { .. } => "Audio is being relayed to your phone.",
        ConnectionStatus::Disconnected { .. } => {
            "The phone dropped off. It will reconnect on its own when it comes back."
        }
    }
}

fn pairing_card(ui: &mut egui::Ui, state: &AppState) {
    let p = theme::palette(ui.ctx());
    widgets::card(ui, |ui| {
        widgets::section_header(
            ui,
            "Pairing code",
            Some("Valid for five minutes, then a new one is generated."),
        );

        let code = state.current_pairing_code();
        widgets::pairing_code(ui, &code);

        ui.add_space(space::MD);
        ui.horizontal(|ui| {
            if widgets::primary_button(ui, "Copy code").clicked() {
                ui.ctx().copy_text(code.clone());
            }
            widgets::status_pill(ui, "Waiting for a phone", p.text_secondary, false);
        });
    });
}

fn streaming_card(ui: &mut egui::Ui, state: &AppState, device_name: &str) {
    let p = theme::palette(ui.ctx());
    widgets::card(ui, |ui| {
        ui.horizontal(|ui| {
            widgets::status_pill(ui, &format!("Streaming to {device_name}"), p.success, true);
        });
        ui.add_space(space::LG);

        // The meter is the honest answer to "is it actually working?" — a
        // connected session that is silently sending silence looks identical
        // to a working one without it.
        widgets::section_header(ui, "Output level", None);
        let width = ui.available_width().min(420.0);
        let level = if state.is_streaming_enabled() {
            state.audio_level()
        } else {
            0.0
        };
        widgets::level_meter(ui, level, width);
        ui.add_space(space::XS);
        widgets::muted_label(
            ui,
            if state.is_streaming_enabled() {
                "Level of the audio being captured from this laptop."
            } else {
                "Streaming is paused — nothing is being sent."
            },
        );
    });
}

fn disconnected_card(ui: &mut egui::Ui, device_name: &str) {
    let p = theme::palette(ui.ctx());
    widgets::card(ui, |ui| {
        widgets::status_pill(ui, &format!("{device_name} disconnected"), p.warning, false);
        ui.add_space(space::SM);
        widgets::muted_label(
            ui,
            "No action needed here — the phone retries automatically, and \
             re-pairing is not required.",
        );
    });
}

/// The streaming on/off switch. Its own card because it is the only control
/// on this screen and shouldn't be buried inside a status panel.
fn output_card(ui: &mut egui::Ui, state: &AppState) {
    widgets::card(ui, |ui| {
        let mut enabled = state.is_streaming_enabled();
        let changed = widgets::setting_row(
            ui,
            "Relay audio",
            Some("Pauses sending without dropping the connection or re-pairing."),
            |ui| widgets::toggle_switch(ui, &mut enabled).changed(),
        );
        if changed {
            state.set_streaming_enabled(enabled);
        }
    });
}
