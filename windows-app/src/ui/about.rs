use eframe::egui;

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
    ("uuid", "MIT OR Apache-2.0"),
    ("mdns-sd", "MIT"),
    ("egui / eframe", "MIT OR Apache-2.0"),
    ("tracing / tracing-subscriber", "MIT"),
    ("thiserror", "MIT OR Apache-2.0"),
    ("hostname", "MIT"),
];

pub fn show(ui: &mut egui::Ui) {
    ui.heading("About audio-relay");
    ui.separator();

    ui.label(format!("Version {}", env!("CARGO_PKG_VERSION")));
    ui.label(format!(
        "Build commit {} ({})",
        env!("GIT_HASH"),
        env!("GIT_COMMIT_DATE")
    ));
    ui.add_space(6.0);
    ui.hyperlink_to("Source on GitHub", env!("CARGO_PKG_REPOSITORY"));
    ui.hyperlink_to(
        "Report an issue",
        format!("{}/issues", env!("CARGO_PKG_REPOSITORY")),
    );

    ui.separator();
    ui.label(egui::RichText::new("License").strong());
    ui.label("MIT — see the LICENSE file in the repository.");

    ui.separator();
    ui.label(egui::RichText::new("Third-party open source").strong());
    ui.small("Direct dependencies and their licenses — see Cargo.toml / Cargo.lock for the complete transitive tree.");
    egui::Grid::new("third_party_licenses")
        .striped(true)
        .show(ui, |ui| {
            for (name, license) in THIRD_PARTY {
                ui.label(*name);
                ui.label(*license);
                ui.end_row();
            }
        });
}
