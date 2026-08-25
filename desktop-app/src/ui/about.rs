use eframe::egui;

use super::theme::{self, space};
use super::widgets::{self, Icon};

/// Third-party dependencies worth calling out by name in the About screen.
/// Not the full transitive dependency tree (that's ~150 crates and would
/// drift out of date immediately) — the direct dependencies from
/// `Cargo.toml`, which is what a user actually cares about. For the
/// complete list with exact license texts, `cargo about generate` or
/// `cargo license` against this workspace gives the authoritative answer.
const THIRD_PARTY: &[(&str, &str)] = &[
    ("tokio", "MIT"),
    ("serde / serde_json", "MIT OR Apache-2.0"),
    ("toml", "MIT OR Apache-2.0"),
    ("directories", "MIT OR Apache-2.0"),
    ("rand", "MIT OR Apache-2.0"),
    ("hkdf / sha2 / hmac", "MIT OR Apache-2.0"),
    ("chacha20poly1305", "MIT OR Apache-2.0"),
    ("subtle", "BSD-3-Clause"),
    ("uuid", "MIT OR Apache-2.0"),
    ("mdns-sd", "MIT"),
    ("egui / eframe", "MIT OR Apache-2.0"),
    ("tracing / tracing-subscriber", "MIT"),
    ("thiserror", "MIT OR Apache-2.0"),
    ("hostname", "MIT"),
    ("windows / raw-window-handle", "MIT OR Apache-2.0"),
];

pub fn show(ui: &mut egui::Ui) {
    let p = theme::palette(ui.ctx());

    ui.horizontal(|ui| {
        let (rect, _) = ui.allocate_exact_size(egui::vec2(48.0, 48.0), egui::Sense::hover());
        widgets::draw_icon(ui.painter(), Icon::Signal, rect.center(), 18.0, p.accent);
        ui.add_space(space::SM);
        ui.vertical(|ui| {
            ui.label(
                egui::RichText::new("audio-relay")
                    .text_style(theme::text::display())
                    .strong()
                    .color(p.text_primary),
            );
            widgets::muted_label(
                ui,
                "Low-latency Windows audio streaming to Android over local Wi-Fi.",
            );
        });
    });
    ui.add_space(space::XL);

    widgets::card(ui, |ui| {
        widgets::section_header(ui, "Build", None);
        widgets::setting_row(ui, "Version", None, |ui| {
            ui.label(env!("CARGO_PKG_VERSION"));
        });
        ui.add_space(space::XS);
        widgets::setting_row(ui, "Commit", None, |ui| {
            ui.label(egui::RichText::new(env!("GIT_HASH")).monospace());
        });
        ui.add_space(space::XS);
        widgets::setting_row(ui, "Commit date", None, |ui| {
            ui.label(env!("GIT_COMMIT_DATE"));
        });
    });

    ui.add_space(space::LG);
    widgets::card(ui, |ui| {
        widgets::section_header(ui, "Project", None);
        ui.hyperlink_to("Source on GitHub", env!("CARGO_PKG_REPOSITORY"));
        ui.add_space(space::XS);
        ui.hyperlink_to(
            "Report an issue",
            format!("{}/issues", env!("CARGO_PKG_REPOSITORY")),
        );
    });

    ui.add_space(space::LG);
    widgets::card(ui, |ui| {
        widgets::section_header(
            ui,
            "Licenses",
            Some("audio-relay is MIT licensed — see the LICENSE file in the repository."),
        );
        widgets::muted_label(
            ui,
            "Direct dependencies below. See Cargo.toml and Cargo.lock for the complete \
             transitive tree.",
        );
        ui.add_space(space::MD);

        egui::Grid::new("third_party_licenses")
            .striped(true)
            .num_columns(2)
            .spacing([space::XL, space::SM])
            .show(ui, |ui| {
                for (name, license) in THIRD_PARTY {
                    ui.label(egui::RichText::new(*name).color(p.text_primary));
                    ui.label(
                        egui::RichText::new(*license)
                            .text_style(theme::text::small())
                            .color(p.text_secondary),
                    );
                    ui.end_row();
                }
            });
    });
}
