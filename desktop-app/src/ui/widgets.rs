//! Reusable pieces built on top of [`super::theme`].
//!
//! egui's stock widgets are functional but generic. These wrap them (or
//! replace them with hand-painted equivalents) so every screen gets the same
//! cards, the same switch, the same section headings — which is most of what
//! makes an interface feel designed rather than assembled.
//!
//! Icons are painted from primitives rather than drawn from an icon font.
//! That's deliberate: bundling an icon font would add weight to a binary the
//! project deliberately keeps small, and relying on glyphs being present in
//! the system font is how you end up shipping tofu boxes to somebody.

use eframe::egui::{
    self, Align, Align2, Color32, CornerRadius, Layout, Pos2, Rect, Response, Sense, Stroke,
    StrokeKind, Ui, Vec2,
};

use super::theme::{self, radius, space, Palette};

/// A raised container. The workhorse of every screen here.
pub fn card<R>(ui: &mut Ui, add_contents: impl FnOnce(&mut Ui) -> R) -> R {
    let p = theme::palette(ui.ctx());
    egui::Frame::new()
        .fill(p.surface)
        .stroke(Stroke::new(1.0_f32, p.border))
        .corner_radius(CornerRadius::same(radius::MD))
        .inner_margin(egui::Margin::same(space::LG as i8))
        .show(ui, |ui| ui.vertical(|ui| add_contents(ui)).inner)
        .inner
}

/// A heading plus optional explanatory line. Sections should never be a bare
/// bold label — the second line is where the "why" lives.
pub fn section_header(ui: &mut Ui, title: &str, subtitle: Option<&str>) {
    let p = theme::palette(ui.ctx());
    ui.label(
        egui::RichText::new(title)
            .text_style(theme::text::subtitle())
            .strong()
            .color(p.text_primary),
    );
    if let Some(subtitle) = subtitle {
        ui.add_space(space::XS * 0.5);
        ui.label(
            egui::RichText::new(subtitle)
                .text_style(theme::text::small())
                .color(p.text_secondary),
        );
    }
    ui.add_space(space::MD);
}

/// A coloured capsule with a leading dot — the status indicator on Home.
pub fn status_pill(ui: &mut Ui, label: &str, color: Color32, pulsing: bool) -> Response {
    let p = theme::palette(ui.ctx());
    let font = ui.style().text_styles[&theme::text::body()].clone();
    let galley = ui.painter().layout_no_wrap(label.to_owned(), font, color);

    let dot_radius = 4.0;
    let pad_x = space::MD;
    let pad_y = space::SM * 0.75;
    let gap = space::SM;
    let size = Vec2::new(
        pad_x * 2.0 + dot_radius * 2.0 + gap + galley.size().x,
        (galley.size().y + pad_y * 2.0).max(26.0),
    );
    let (rect, response) = ui.allocate_exact_size(size, Sense::hover());

    if ui.is_rect_visible(rect) {
        let painter = ui.painter();
        painter.rect_filled(rect, CornerRadius::same(radius::PILL), p.muted(color, 0.82));
        painter.rect_stroke(
            rect,
            CornerRadius::same(radius::PILL),
            Stroke::new(1.0_f32, p.muted(color, 0.55)),
            StrokeKind::Inside,
        );

        let dot_center = Pos2::new(rect.left() + pad_x + dot_radius, rect.center().y);
        // A slow breathe while streaming, so a glance at the window tells you
        // audio is actually moving rather than merely connected.
        let alpha = if pulsing {
            let t = ui.input(|i| i.time) as f32;
            ui.ctx().request_repaint();
            0.55 + 0.45 * (t * 2.2).sin().abs()
        } else {
            1.0
        };
        painter.circle_filled(dot_center, dot_radius, color.gamma_multiply(alpha));

        let text_pos = Pos2::new(
            dot_center.x + dot_radius + gap,
            rect.center().y - galley.size().y * 0.5,
        );
        painter.galley(text_pos, galley, color);
    }
    response
}

/// An animated switch. Replaces `ui.checkbox` for the streaming toggle, where
/// the control is the primary action on the screen and a tickbox reads as an
/// afterthought.
pub fn toggle_switch(ui: &mut Ui, on: &mut bool) -> Response {
    let p = theme::palette(ui.ctx());
    let size = Vec2::new(40.0, 22.0);
    let (rect, mut response) = ui.allocate_exact_size(size, Sense::click());
    if response.clicked() {
        *on = !*on;
        response.mark_changed();
    }
    response.widget_info(|| egui::WidgetInfo::selected(egui::WidgetType::Checkbox, true, *on, ""));

    if ui.is_rect_visible(rect) {
        let t = ui.ctx().animate_bool_with_time(response.id, *on, 0.12);
        let track = if *on {
            p.accent
        } else {
            p.muted(p.text_muted, 0.45)
        };
        let painter = ui.painter();
        painter.rect_filled(rect, CornerRadius::same(radius::PILL), track);

        let knob_radius = rect.height() * 0.5 - 3.0;
        let travel = rect.width() - rect.height();
        let knob_x = rect.left() + rect.height() * 0.5 + travel * t;
        painter.circle_filled(
            Pos2::new(knob_x, rect.center().y),
            knob_radius,
            if *on { p.on_accent } else { p.surface },
        );
    }
    response
}

/// Horizontal audio-level bar. `level` is 0.0..=1.0.
///
/// Deliberately shows *something* even at zero (an empty track), because a
/// meter that vanishes when silent is indistinguishable from a broken one.
pub fn level_meter(ui: &mut Ui, level: f32, width: f32) -> Response {
    let p = theme::palette(ui.ctx());
    let size = Vec2::new(width, 8.0);
    let (rect, response) = ui.allocate_exact_size(size, Sense::hover());

    if ui.is_rect_visible(rect) {
        let painter = ui.painter();
        let radius = CornerRadius::same(radius::PILL);
        painter.rect_filled(rect, radius, p.surface_variant);

        let level = level.clamp(0.0, 1.0);
        if level > 0.001 {
            let filled = Rect::from_min_size(
                rect.min,
                Vec2::new((rect.width() * level).max(size.y), rect.height()),
            );
            // Warn as it approaches clipping — the laptop's own volume is the
            // only place the user can fix that, so it's worth showing.
            let color = if level > 0.95 {
                p.danger
            } else if level > 0.8 {
                p.warning
            } else {
                p.accent
            };
            painter.rect_filled(filled, radius, color);
        }
    }
    response
}

/// The pairing code, one digit per box. Boxed digits are far easier to read
/// aloud or copy by eye than a run of six characters.
pub fn pairing_code(ui: &mut Ui, code: &str) {
    let p = theme::palette(ui.ctx());
    let font = ui.style().text_styles[&theme::text::code()].clone();
    let box_size = Vec2::new(38.0, 52.0);

    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = space::SM;
        for ch in code.chars() {
            let (rect, _) = ui.allocate_exact_size(box_size, Sense::hover());
            if ui.is_rect_visible(rect) {
                let painter = ui.painter();
                painter.rect_filled(rect, CornerRadius::same(radius::SM), p.surface_variant);
                painter.rect_stroke(
                    rect,
                    CornerRadius::same(radius::SM),
                    Stroke::new(1.0_f32, p.border),
                    StrokeKind::Inside,
                );
                painter.text(
                    rect.center(),
                    Align2::CENTER_CENTER,
                    ch,
                    font.clone(),
                    p.text_primary,
                );
            }
        }
    });
}

/// One entry in the left nav rail.
pub fn nav_item(ui: &mut Ui, selected: bool, icon: Icon, label: &str) -> Response {
    let p = theme::palette(ui.ctx());
    let size = Vec2::new(ui.available_width(), 38.0);
    let (rect, response) = ui.allocate_exact_size(size, Sense::click());

    if ui.is_rect_visible(rect) {
        let hovered = response.hovered();
        let painter = ui.painter();

        if selected {
            painter.rect_filled(
                rect,
                CornerRadius::same(radius::SM),
                p.muted(p.accent, 0.78),
            );
            // Fluent marks the selected nav item with a short accent bar
            // rather than a full highlight.
            let bar = Rect::from_min_size(
                Pos2::new(rect.left() + 3.0, rect.center().y - 8.0),
                Vec2::new(3.0, 16.0),
            );
            painter.rect_filled(bar, CornerRadius::same(radius::PILL), p.accent);
        } else if hovered {
            painter.rect_filled(rect, CornerRadius::same(radius::SM), p.surface_variant);
        }

        let color = if selected {
            p.text_primary
        } else {
            p.text_secondary
        };
        let icon_center = Pos2::new(rect.left() + 26.0, rect.center().y);
        draw_icon(painter, icon, icon_center, 8.0, color);

        let font = ui.style().text_styles[&theme::text::body()].clone();
        painter.text(
            Pos2::new(rect.left() + 44.0, rect.center().y),
            Align2::LEFT_CENTER,
            label,
            font,
            color,
        );
    }
    response
}

/// The icon set. Small enough to hand-draw, which keeps the binary free of a
/// font dependency — see the module docs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Icon {
    /// Concentric arcs — the app's own "relay" glyph, reused for Home.
    Signal,
    /// Sliders, for Settings.
    Tune,
    /// A circled "i", for About.
    Info,
}

pub fn draw_icon(painter: &egui::Painter, icon: Icon, center: Pos2, r: f32, color: Color32) {
    let stroke = Stroke::new((r * 0.22).max(1.4), color);
    match icon {
        Icon::Signal => {
            painter.circle_filled(
                Pos2::new(center.x - r * 0.55, center.y + r * 0.55),
                r * 0.3,
                color,
            );
            for (i, scale) in [0.55_f32, 0.95].iter().enumerate() {
                let radius = r * scale * 1.5;
                let steps = 12;
                let mut points = Vec::with_capacity(steps + 1);
                for s in 0..=steps {
                    // A quarter arc opening up and to the right.
                    let angle = -std::f32::consts::FRAC_PI_2 * (s as f32 / steps as f32);
                    points.push(Pos2::new(
                        center.x - r * 0.55 + radius * angle.cos(),
                        center.y + r * 0.55 + radius * angle.sin(),
                    ));
                }
                let width = if i == 0 {
                    stroke.width
                } else {
                    stroke.width * 0.85
                };
                painter.add(egui::Shape::line(points, Stroke::new(width, color)));
            }
        }
        Icon::Tune => {
            for (i, y) in [-0.7_f32, 0.0, 0.7].iter().enumerate() {
                let y = center.y + r * y;
                painter.line_segment(
                    [Pos2::new(center.x - r, y), Pos2::new(center.x + r, y)],
                    stroke,
                );
                // Knobs staggered so it reads as sliders, not a hamburger menu.
                let knob_x = center.x + r * [-0.35_f32, 0.45, -0.1][i];
                painter.circle_filled(Pos2::new(knob_x, y), stroke.width * 1.5, color);
            }
        }
        Icon::Info => {
            painter.circle_stroke(center, r, stroke);
            painter.circle_filled(
                Pos2::new(center.x, center.y - r * 0.42),
                stroke.width * 0.8,
                color,
            );
            painter.line_segment(
                [
                    Pos2::new(center.x, center.y - r * 0.1),
                    Pos2::new(center.x, center.y + r * 0.5),
                ],
                stroke,
            );
        }
    }
}

/// A labelled row: description on the left, control right-aligned. Keeps
/// Settings visually consistent regardless of which control a row holds.
pub fn setting_row<R>(
    ui: &mut Ui,
    label: &str,
    hint: Option<&str>,
    control: impl FnOnce(&mut Ui) -> R,
) -> R {
    let p = theme::palette(ui.ctx());
    let mut result = None;
    ui.horizontal(|ui| {
        ui.vertical(|ui| {
            ui.label(egui::RichText::new(label).color(p.text_primary));
            if let Some(hint) = hint {
                ui.label(
                    egui::RichText::new(hint)
                        .text_style(theme::text::small())
                        .color(p.text_muted),
                );
            }
        });
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            result = Some(control(ui));
        });
    });
    result.expect("control closure always runs")
}

/// Secondary text, used often enough to be worth a helper.
pub fn muted_label(ui: &mut Ui, text: &str) {
    let p = theme::palette(ui.ctx());
    ui.label(
        egui::RichText::new(text)
            .text_style(theme::text::small())
            .color(p.text_secondary),
    );
}

/// Palette-aware accent button for the one primary action on a screen.
pub fn primary_button(ui: &mut Ui, label: &str) -> Response {
    let p: Palette = theme::palette(ui.ctx());
    ui.add(
        egui::Button::new(egui::RichText::new(label).color(p.on_accent))
            .fill(p.accent)
            .corner_radius(CornerRadius::same(radius::SM)),
    )
}
