use eframe::egui;

use crate::state::{AppState, LatencyMode};

pub fn show(ui: &mut egui::Ui, state: &AppState) {
    ui.heading("Settings");
    ui.separator();

    capture_device_picker(ui, state);
    ui.separator();
    latency_mode_picker(ui, state);
    ui.separator();
    paired_devices(ui, state);
}

fn capture_device_picker(ui: &mut egui::Ui, state: &AppState) {
    ui.label(egui::RichText::new("Audio source").strong());
    ui.small("Which output device's audio gets relayed to your phone — useful if this laptop has more than one (e.g. built-in speakers vs. a USB DAC).");

    let devices = state.available_capture_devices.lock().unwrap().clone();
    let selected_id = state.selected_capture_device_id();
    let selected_label = match &selected_id {
        None => "System default".to_string(),
        Some(id) => devices
            .iter()
            .find(|d| &d.id == id)
            .map(|d| d.name.clone())
            .unwrap_or_else(|| "(previously selected device, not currently present)".to_string()),
    };

    ui.horizontal(|ui| {
        egui::ComboBox::from_id_source("capture_device")
            .selected_text(selected_label)
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
        ui.small("No devices enumerated yet — this list is populated by the capture thread shortly after startup.");
    }
}

fn latency_mode_picker(ui: &mut egui::Ui, state: &AppState) {
    ui.label(egui::RichText::new("Latency mode").strong());
    ui.small("Low trades a little glitch-resistance for lower delay; Balanced is the safer default. Applies immediately, no reconnect needed.");

    let mut mode = state.latency_mode();
    ui.horizontal(|ui| {
        if ui
            .radio_value(&mut mode, LatencyMode::Low, "Low (~5ms chunks)")
            .clicked()
            || ui
                .radio_value(&mut mode, LatencyMode::Balanced, "Balanced (~10ms chunks)")
                .clicked()
        {
            state.set_latency_mode(mode);
        }
    });
}

fn paired_devices(ui: &mut egui::Ui, state: &AppState) {
    ui.label(egui::RichText::new("Paired phones").strong());

    let devices: Vec<(String, String)> = {
        let config = state.config.lock().unwrap();
        config
            .paired_devices
            .iter()
            .map(|(id, d)| (id.clone(), d.device_name.clone()))
            .collect()
    };

    if devices.is_empty() {
        ui.small("No phone has paired with this laptop yet.");
        return;
    }

    let mut to_forget: Option<String> = None;
    for (id, name) in &devices {
        ui.horizontal(|ui| {
            ui.label(name);
            ui.small(format!("({id})"));
            if ui.button("Forget").clicked() {
                to_forget = Some(id.clone());
            }
        });
    }

    if let Some(id) = to_forget {
        let mut config = state.config.lock().unwrap();
        config.forget_device(&id);
        let _ = config.save();
    }
}
