//! Minimalist `egui` status window (docs/roadmap.md Phase 6). Runs on the
//! main thread via `eframe`; the network/capture pipeline runs on a
//! background tokio runtime. The two talk only through the shared
//! `AppState` — the UI never blocks on network I/O.
//!
//! Three tabs, kept intentionally small and un-nested rather than pulling
//! in a routing/navigation crate for three screens: `home` (status +
//! start/stop), `settings` (capture device, latency mode, paired-device
//! management — everything the user can actually configure), and `about`
//! (version/build/license info).

mod about;
mod home;
mod settings;

use std::sync::Arc;

use eframe::egui;

use crate::state::AppState;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Tab {
    Home,
    Settings,
    About,
}

pub struct StatusApp {
    state: Arc<AppState>,
    tab: Tab,
}

impl StatusApp {
    pub fn new(state: Arc<AppState>) -> Self {
        StatusApp {
            state,
            tab: Tab::Home,
        }
    }

    pub fn run(self) -> eframe::Result<()> {
        let options = eframe::NativeOptions {
            viewport: egui::ViewportBuilder::default()
                .with_inner_size([420.0, 460.0])
                .with_min_inner_size([340.0, 360.0]),
            ..Default::default()
        };
        eframe::run_native("audio-relay", options, Box::new(|_cc| Box::new(self)))
    }
}

impl eframe::App for StatusApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Cheap poll-based refresh — a heartbeat/session/device-list change
        // should show up within a frame or two. Simpler and plenty
        // responsive for a status window; avoids wiring a second channel
        // just for repaints.
        ctx.request_repaint_after(std::time::Duration::from_millis(500));

        egui::TopBottomPanel::top("tabs").show(ctx, |ui| {
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                ui.selectable_value(&mut self.tab, Tab::Home, "Home");
                ui.selectable_value(&mut self.tab, Tab::Settings, "Settings");
                ui.selectable_value(&mut self.tab, Tab::About, "About");
            });
            ui.add_space(4.0);
        });

        egui::CentralPanel::default().show(ctx, |ui| match self.tab {
            Tab::Home => home::show(ui, &self.state),
            Tab::Settings => settings::show(ui, &self.state),
            Tab::About => about::show(ui),
        });
    }
}
