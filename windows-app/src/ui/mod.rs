//! The `egui` desktop window. Runs on the main thread via `eframe`; the
//! network/capture pipeline runs on a background tokio runtime. The two talk
//! only through the shared `AppState` — the UI never blocks on network I/O.
//!
//! Three screens behind a left nav rail: `home` (status, pairing code,
//! streaming toggle, level meter), `settings` (capture device, latency,
//! appearance, paired devices), and `about` (version, build, licences).
//! Still no routing crate — three destinations and an enum is the whole
//! navigation model.
//!
//! Look and feel live in [`theme`] and [`widgets`] rather than being spread
//! through the screens; see those modules for why.

mod about;
mod home;
mod platform;
mod settings;
mod theme;
mod widgets;

use std::sync::Arc;
use std::time::Duration;

use eframe::egui;

use crate::state::AppState;
use theme::space;
pub use theme::Appearance;
use widgets::Icon;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Screen {
    Home,
    Settings,
    About,
}

impl Screen {
    const ALL: [Self; 3] = [Self::Home, Self::Settings, Self::About];

    fn label(self) -> &'static str {
        match self {
            Self::Home => "Status",
            Self::Settings => "Settings",
            Self::About => "About",
        }
    }

    fn icon(self) -> Icon {
        match self {
            Self::Home => Icon::Signal,
            Self::Settings => Icon::Tune,
            Self::About => Icon::Info,
        }
    }
}

pub struct StatusApp {
    state: Arc<AppState>,
    screen: Screen,
    appearance: Appearance,
}

impl StatusApp {
    pub fn new(state: Arc<AppState>) -> Self {
        let appearance = state.config.lock().unwrap().appearance;
        StatusApp {
            state,
            screen: Screen::Home,
            appearance,
        }
    }

    pub fn run(self) -> eframe::Result<()> {
        let mut viewport = egui::ViewportBuilder::default()
            .with_inner_size([760.0, 540.0])
            .with_min_inner_size([620.0, 460.0])
            .with_title("audio-relay");
        if let Some(icon) = platform::app_icon() {
            viewport = viewport.with_icon(icon);
        }

        let options = eframe::NativeOptions {
            viewport,
            ..Default::default()
        };

        eframe::run_native(
            "audio-relay",
            options,
            Box::new(|cc| {
                // The one hook where the design system gets installed — before
                // this existed the app ran on egui's stock defaults.
                theme::install(&cc.egui_ctx);
                if let Some(accent) = platform::system_accent_color() {
                    theme::set_accent(&cc.egui_ctx, accent);
                }
                platform::install_fonts(&cc.egui_ctx);
                // The window already exists by this point, so the backdrop can
                // be applied here rather than deferred to the first frame.
                platform::configure_window(cc);
                self.apply_appearance(&cc.egui_ctx);
                Ok(Box::new(self))
            }),
        )
    }

    fn apply_appearance(&self, ctx: &egui::Context) {
        ctx.set_theme(match self.appearance {
            Appearance::System => egui::ThemePreference::System,
            Appearance::Light => egui::ThemePreference::Light,
            Appearance::Dark => egui::ThemePreference::Dark,
        });
    }

    fn nav_rail(&mut self, ctx: &egui::Context) {
        let p = theme::palette(ctx);
        egui::SidePanel::left("nav")
            .exact_width(184.0)
            .resizable(false)
            .frame(
                egui::Frame::new()
                    .fill(p.surface)
                    .inner_margin(egui::Margin::same(space::MD as i8)),
            )
            .show(ctx, |ui| {
                ui.add_space(space::SM);
                ui.horizontal(|ui| {
                    ui.add_space(space::XS);
                    let (rect, _) =
                        ui.allocate_exact_size(egui::vec2(24.0, 24.0), egui::Sense::hover());
                    widgets::draw_icon(ui.painter(), Icon::Signal, rect.center(), 9.0, p.accent);
                    ui.label(
                        egui::RichText::new("audio-relay")
                            .text_style(theme::text::subtitle())
                            .strong()
                            .color(p.text_primary),
                    );
                });
                ui.add_space(space::LG);

                for screen in Screen::ALL {
                    let selected = self.screen == screen;
                    if widgets::nav_item(ui, selected, screen.icon(), screen.label()).clicked() {
                        self.screen = screen;
                    }
                    ui.add_space(2.0);
                }

                // Version pinned to the bottom of the rail — always visible,
                // never in the way.
                ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
                    ui.add_space(space::SM);
                    ui.label(
                        egui::RichText::new(format!("v{}", env!("CARGO_PKG_VERSION")))
                            .text_style(theme::text::small())
                            .color(p.text_muted),
                    );
                });
            });
    }
}

impl eframe::App for StatusApp {
    /// Transparent so the Windows 11 Mica backdrop can show through. On
    /// platforms or versions where Mica isn't applied this is simply painted
    /// over by the panel fill, so it costs nothing.
    fn clear_color(&self, visuals: &egui::Visuals) -> [f32; 4] {
        visuals.panel_fill.to_normalized_gamma_f32()
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Poll-based refresh: a heartbeat, session or device-list change should
        // show up within a frame or two. While streaming the level meter needs
        // real frames, so ask for them — but only then, so an idle window
        // isn't burning a GPU wake-up 60 times a second.
        let streaming = self.state.is_streaming_enabled()
            && matches!(
                *self.state.status.lock().unwrap(),
                crate::state::ConnectionStatus::Streaming { .. }
            );
        ctx.request_repaint_after(if streaming && self.screen == Screen::Home {
            Duration::from_millis(33)
        } else {
            Duration::from_millis(500)
        });

        self.nav_rail(ctx);

        egui::CentralPanel::default()
            .frame(
                egui::Frame::new()
                    .fill(theme::palette(ctx).bg)
                    .inner_margin(egui::Margin::same(space::XL as i8)),
            )
            .show(ctx, |ui| {
                egui::ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .show(ui, |ui| match self.screen {
                        Screen::Home => home::show(ui, &self.state),
                        Screen::Settings => {
                            if let Some(appearance) =
                                settings::show(ui, &self.state, self.appearance)
                            {
                                self.appearance = appearance;
                                self.apply_appearance(ui.ctx());
                                let mut config = self.state.config.lock().unwrap();
                                config.appearance = appearance;
                                if let Err(e) = config.save() {
                                    tracing::warn!(error = %e, "could not persist appearance");
                                }
                            }
                        }
                        Screen::About => about::show(ui),
                    });
            });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_screen_has_a_distinct_label_and_icon() {
        let labels: Vec<_> = Screen::ALL.iter().map(|s| s.label()).collect();
        let icons: Vec<_> = Screen::ALL.iter().map(|s| s.icon()).collect();
        for (i, screen) in Screen::ALL.iter().enumerate() {
            for other in Screen::ALL.iter().skip(i + 1) {
                assert_ne!(screen.label(), other.label());
                assert_ne!(screen.icon(), other.icon());
            }
        }
        assert_eq!(labels.len(), 3);
        assert_eq!(icons.len(), 3);
    }
}
