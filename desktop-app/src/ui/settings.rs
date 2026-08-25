use eframe::egui;

use super::theme::{self, space, Appearance};
use super::widgets;
use crate::state::{AppState, LatencyMode};

/// Returns a new [`Appearance`] when the user changed it, so the caller can
/// apply and persist it. Everything else on this screen writes straight
/// through to `AppState`/`Config`, but the theme has to round-trip through
/// the app so `egui`'s theme preference is updated too.
#[must_use]
pub fn show(ui: &mut egui::Ui, state: &AppState, appearance: Appearance) -> Option<Appearance> {
    let p = theme::palette(ui.ctx());
    ui.label(
        egui::RichText::new("Settings")
            .text_style(theme::text::display())
            .strong()
            .color(p.text_primary),
    );
    ui.add_space(space::XL);

    capture_device_card(ui, state);
    ui.add_space(space::LG);
    latency_card(ui, state);
    ui.add_space(space::LG);
    let changed = appearance_card(ui, appearance);
    ui.add_space(space::LG);
    paired_devices_card(ui, state);

    changed
}

fn capture_device_card(ui: &mut egui::Ui, state: &AppState) {
    widgets::card(ui, |ui| {
        widgets::section_header(
            ui,
            "Audio source",
            Some(
                "Which output device's audio gets relayed — useful when this laptop has more \
                 than one, such as built-in speakers versus a USB DAC.",
            ),
        );

        let devices = state.available_capture_devices.lock().unwrap().clone();
        let selected_id = state.selected_capture_device_id();
        let selected_label = match &selected_id {
            None => "System default".to_string(),
            Some(id) => devices
                .iter()
                .find(|d| &d.id == id)
                .map(|d| d.name.clone())
                .unwrap_or_else(|| "Previously selected device (not connected)".to_string()),
        };

        ui.horizontal(|ui| {
            egui::ComboBox::from_id_salt("capture_device")
                .selected_text(selected_label)
                .width(300.0)
                .show_ui(ui, |ui| {
                    if ui
                        .selectable_label(selected_id.is_none(), "System default")
                        .clicked()
                    {
                        state.set_selected_capture_device(None);
                    }
                    for device in &devices {
                        let is_selected = selected_id.as_deref() == Some(device.id.as_str());
                        if ui.selectable_label(is_selected, &device.name).clicked() {
                            state.set_selected_capture_device(Some(device.id.clone()));
                        }
                    }
                });
            if ui.button("Refresh").clicked() {
                state.request_capture_devices_refresh();
            }
        });

        if devices.is_empty() {
            ui.add_space(space::SM);
            widgets::muted_label(
                ui,
                "No devices enumerated yet — the capture thread fills this in shortly after \
                 startup.",
            );
        }
    });
}

fn latency_card(ui: &mut egui::Ui, state: &AppState) {
    widgets::card(ui, |ui| {
        widgets::section_header(
            ui,
            "Latency",
            Some("Applies immediately — no reconnect, no re-pairing."),
        );

        let mut mode = state.latency_mode();
        let before = mode;
        for (option, label, hint) in [
            (
                LatencyMode::Low,
                "Low",
                "~5ms chunks. Lowest delay, slightly less tolerant of a busy network.",
            ),
            (
                LatencyMode::Balanced,
                "Balanced",
                "~10ms chunks. The safer default.",
            ),
        ] {
            ui.radio_value(&mut mode, option, label);
            ui.indent(label, |ui| widgets::muted_label(ui, hint));
            ui.add_space(space::XS);
        }
        if mode != before {
            state.set_latency_mode(mode);
        }
    });
}

fn appearance_card(ui: &mut egui::Ui, current: Appearance) -> Option<Appearance> {
    let mut selected = current;
    widgets::card(ui, |ui| {
        widgets::section_header(
            ui,
            "Appearance",
            Some("Follow system uses whatever light/dark mode Windows is set to."),
        );
        ui.horizontal(|ui| {
            for option in Appearance::ALL {
                ui.selectable_value(&mut selected, option, option.label());
            }
        });
    });
    (selected != current).then_some(selected)
}

fn paired_devices_card(ui: &mut egui::Ui, state: &AppState) {
    widgets::card(ui, |ui| {
        widgets::section_header(
            ui,
            "Paired phones",
            Some("Forgetting a phone means it has to enter a new pairing code next time."),
        );

        let devices: Vec<(String, String)> = {
            let config = state.config.lock().unwrap();
            let mut devices: Vec<_> = config
                .paired_devices
                .iter()
                .map(|(id, d)| (id.clone(), d.device_name.clone()))
                .collect();
            // HashMap iteration order is arbitrary; without this the list
            // visibly reshuffles between frames.
            devices.sort_by(|a, b| a.1.cmp(&b.1).then_with(|| a.0.cmp(&b.0)));
            devices
        };

        if devices.is_empty() {
            widgets::muted_label(ui, "No phone has paired with this laptop yet.");
            return;
        }

        let mut to_forget: Option<String> = None;
        for (id, name) in &devices {
            let short_id: String = id.chars().take(8).collect();
            widgets::setting_row(ui, name, Some(&short_id), |ui| {
                if ui.button("Forget").clicked() {
                    to_forget = Some(id.clone());
                }
            });
            ui.add_space(space::XS);
        }

        if let Some(id) = to_forget {
            let mut config = state.config.lock().unwrap();
            config.forget_device(&id);
            if let Err(e) = config.save() {
                tracing::warn!(error = %e, "could not persist forgetting a device");
            }
        }
    });
}
