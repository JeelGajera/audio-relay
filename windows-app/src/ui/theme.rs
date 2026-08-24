//! The app's design system: one place that decides every colour, size and
//! radius the UI uses.
//!
//! egui ships a perfectly reasonable default look, but it's a *toolkit*
//! default — it reads as "an egui app" rather than as a Windows app. This
//! module replaces it wholesale with a Fluent-flavoured palette and type
//! scale, in matched dark and light variants, so the result belongs on a
//! Windows 11 desktop.
//!
//! Everything is expressed as semantic roles (`surface`, `text_secondary`,
//! `danger`) rather than raw colours at the call site. That's what keeps the
//! two themes honest: a screen written against roles is automatically correct
//! in both, and there is exactly one place to change if a colour is wrong.

use eframe::egui;
use eframe::egui::{
    Color32, CornerRadius, FontFamily, FontId, Margin, Shadow, Stroke, TextStyle, Theme,
};

/// What the user picked in Settings. `System` follows the OS.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Appearance {
    #[default]
    System,
    Light,
    Dark,
}

impl Appearance {
    pub const ALL: [Self; 3] = [Self::System, Self::Light, Self::Dark];

    pub fn label(self) -> &'static str {
        match self {
            Self::System => "Follow system",
            Self::Light => "Light",
            Self::Dark => "Dark",
        }
    }
}

/// Semantic colour roles. Two instances exist: [`Palette::DARK`] and
/// [`Palette::LIGHT`].
#[derive(Debug, Clone, Copy)]
pub struct Palette {
    /// The window background, behind everything.
    pub bg: Color32,
    /// Raised content — cards, the nav rail.
    pub surface: Color32,
    /// A second level of raise, for controls sitting on a surface.
    pub surface_variant: Color32,
    /// Hairlines and card outlines.
    pub border: Color32,
    pub text_primary: Color32,
    pub text_secondary: Color32,
    pub text_muted: Color32,
    /// Brand/selection colour. Replaced at runtime by the Windows accent colour.
    pub accent: Color32,
    /// Text drawn on top of `accent`.
    pub on_accent: Color32,
    pub success: Color32,
    pub warning: Color32,
    pub danger: Color32,
}

impl Palette {
    pub const DARK: Self = Self {
        bg: Color32::from_rgb(0x16, 0x18, 0x1D),
        surface: Color32::from_rgb(0x1E, 0x21, 0x28),
        surface_variant: Color32::from_rgb(0x26, 0x2A, 0x33),
        border: Color32::from_rgb(0x32, 0x37, 0x3F),
        text_primary: Color32::from_rgb(0xE8, 0xEA, 0xED),
        text_secondary: Color32::from_rgb(0x9B, 0xA1, 0xAC),
        text_muted: Color32::from_rgb(0x6B, 0x71, 0x7B),
        accent: Color32::from_rgb(0x4C, 0x8D, 0xFF),
        on_accent: Color32::from_rgb(0x0B, 0x0D, 0x11),
        success: Color32::from_rgb(0x3F, 0xB9, 0x50),
        warning: Color32::from_rgb(0xD2, 0x99, 0x22),
        danger: Color32::from_rgb(0xF8, 0x51, 0x49),
    };

    pub const LIGHT: Self = Self {
        bg: Color32::from_rgb(0xF3, 0xF4, 0xF6),
        surface: Color32::from_rgb(0xFF, 0xFF, 0xFF),
        surface_variant: Color32::from_rgb(0xED, 0xEF, 0xF2),
        border: Color32::from_rgb(0xDD, 0xE1, 0xE6),
        text_primary: Color32::from_rgb(0x1B, 0x1F, 0x26),
        text_secondary: Color32::from_rgb(0x5A, 0x61, 0x6B),
        text_muted: Color32::from_rgb(0x87, 0x8E, 0x98),
        accent: Color32::from_rgb(0x0F, 0x6C, 0xBD),
        on_accent: Color32::WHITE,
        success: Color32::from_rgb(0x1A, 0x7F, 0x37),
        warning: Color32::from_rgb(0x9A, 0x67, 0x00),
        danger: Color32::from_rgb(0xCF, 0x22, 0x2E),
    };

    pub fn for_theme(theme: Theme) -> Self {
        match theme {
            Theme::Dark => Self::DARK,
            Theme::Light => Self::LIGHT,
        }
    }

    /// Blend towards the background — for disabled states and subtle fills.
    pub fn muted(&self, color: Color32, amount: f32) -> Color32 {
        let t = amount.clamp(0.0, 1.0);
        let lerp = |a: u8, b: u8| (a as f32 + (b as f32 - a as f32) * t) as u8;
        Color32::from_rgb(
            lerp(color.r(), self.bg.r()),
            lerp(color.g(), self.bg.g()),
            lerp(color.b(), self.bg.b()),
        )
    }
}

/// Spacing scale. Using a scale rather than ad-hoc numbers is what makes
/// unrelated screens line up with each other.
pub mod space {
    pub const XS: f32 = 4.0;
    pub const SM: f32 = 8.0;
    pub const MD: f32 = 12.0;
    pub const LG: f32 = 16.0;
    pub const XL: f32 = 24.0;
}

pub mod radius {
    pub const SM: u8 = 6;
    pub const MD: u8 = 10;
    pub const LG: u8 = 14;
    /// Effectively a capsule at any realistic control height.
    pub const PILL: u8 = 255;
}

/// Named text styles, so screens ask for `theme::text::TITLE` rather than
/// hard-coding a font size.
pub mod text {
    use eframe::egui::TextStyle;

    pub fn display() -> TextStyle {
        TextStyle::Name("display".into())
    }
    pub fn subtitle() -> TextStyle {
        TextStyle::Name("subtitle".into())
    }
    pub fn body() -> TextStyle {
        TextStyle::Body
    }
    pub fn small() -> TextStyle {
        TextStyle::Small
    }
    /// The pairing code, and nothing else.
    pub fn code() -> TextStyle {
        TextStyle::Name("code".into())
    }
}

/// Resolves the palette currently in force. Screens call this rather than
/// matching on the theme themselves.
pub fn palette(ctx: &egui::Context) -> Palette {
    let mut palette = Palette::for_theme(ctx.theme());
    if let Some(accent) = ctx.data(|d| d.get_temp::<Color32>(accent_key())) {
        palette.accent = accent;
        palette.on_accent = readable_on(accent);
    }
    palette
}

fn accent_key() -> egui::Id {
    egui::Id::new("audio-relay-accent")
}

/// Stores a system accent colour for [`palette`] to pick up. Called once at
/// startup on Windows; a no-op everywhere else.
pub fn set_accent(ctx: &egui::Context, accent: Color32) {
    ctx.data_mut(|d| d.insert_temp(accent_key(), accent));
    // The stored style was built with the old accent baked into selection and
    // hyperlink colours, so it has to be rebuilt.
    install(ctx);
}

/// Black or white, whichever stays readable on `background`. Windows accent
/// colours range from near-black to bright yellow, so this can't be a constant.
fn readable_on(background: Color32) -> Color32 {
    // Rec. 601 luma is good enough for a two-way choice and avoids a
    // gamma-correct pipeline for one decision.
    let luma = 0.299 * background.r() as f32
        + 0.587 * background.g() as f32
        + 0.114 * background.b() as f32;
    if luma > 140.0 {
        Color32::from_rgb(0x0B, 0x0D, 0x11)
    } else {
        Color32::WHITE
    }
}

/// Installs the design system into `ctx`, for both themes at once.
///
/// Registering visuals for dark *and* light (rather than just the active one)
/// means the OS flipping theme at runtime is handled by egui without the app
/// noticing.
pub fn install(ctx: &egui::Context) {
    let accent_override = ctx.data(|d| d.get_temp::<Color32>(accent_key()));

    for theme in [Theme::Dark, Theme::Light] {
        let mut palette = Palette::for_theme(theme);
        if let Some(accent) = accent_override {
            palette.accent = accent;
            palette.on_accent = readable_on(accent);
        }
        ctx.set_visuals_of(theme, visuals(&palette, theme));
    }

    let mut style = (*ctx.style()).clone();
    style.text_styles = text_styles();
    style.spacing.item_spacing = egui::vec2(space::SM, space::SM);
    style.spacing.button_padding = egui::vec2(space::MD, space::SM);
    style.spacing.menu_margin = Margin::same(space::XS as i8);
    style.spacing.indent = space::LG;
    style.spacing.slider_width = 220.0;
    style.spacing.combo_width = 220.0;
    style.spacing.interact_size.y = 30.0;
    // Wrapping mid-word looks broken in a settings list; wrap on word
    // boundaries and let long device names elide instead.
    style.wrap_mode = Some(egui::TextWrapMode::Wrap);
    ctx.set_style(style);
}

fn text_styles() -> std::collections::BTreeMap<TextStyle, FontId> {
    use FontFamily::{Monospace, Proportional};
    [
        (text::display(), FontId::new(26.0, Proportional)),
        (TextStyle::Heading, FontId::new(18.0, Proportional)),
        (text::subtitle(), FontId::new(15.0, Proportional)),
        (TextStyle::Body, FontId::new(14.0, Proportional)),
        (TextStyle::Button, FontId::new(14.0, Proportional)),
        (TextStyle::Small, FontId::new(12.0, Proportional)),
        (TextStyle::Monospace, FontId::new(13.0, Monospace)),
        (text::code(), FontId::new(34.0, Monospace)),
    ]
    .into_iter()
    .collect()
}

fn visuals(p: &Palette, theme: Theme) -> egui::Visuals {
    let mut v = match theme {
        Theme::Dark => egui::Visuals::dark(),
        Theme::Light => egui::Visuals::light(),
    };

    v.panel_fill = p.bg;
    v.window_fill = p.surface;
    v.extreme_bg_color = p.surface_variant;
    v.faint_bg_color = p.surface_variant;
    v.code_bg_color = p.surface_variant;
    v.window_stroke = Stroke::new(1.0_f32, p.border);
    v.window_corner_radius = CornerRadius::same(radius::LG);
    v.menu_corner_radius = CornerRadius::same(radius::MD);
    v.override_text_color = Some(p.text_primary);
    v.hyperlink_color = p.accent;
    v.warn_fg_color = p.warning;
    v.error_fg_color = p.danger;
    v.selection.bg_fill = p.accent.gamma_multiply(0.35);
    v.selection.stroke = Stroke::new(1.0_f32, p.accent);
    v.slider_trailing_fill = true;
    v.striped = false;

    // Softer, larger shadows than egui's default — the default reads as a
    // 90s drop shadow next to Fluent's diffuse elevation.
    v.window_shadow = Shadow {
        offset: [0, 8],
        blur: 24,
        spread: 0,
        color: Color32::from_black_alpha(if theme == Theme::Dark { 120 } else { 40 }),
    };
    v.popup_shadow = Shadow {
        offset: [0, 4],
        blur: 16,
        spread: 0,
        color: Color32::from_black_alpha(if theme == Theme::Dark { 100 } else { 32 }),
    };

    let radius = CornerRadius::same(radius::SM);
    v.widgets.noninteractive.bg_fill = p.surface;
    v.widgets.noninteractive.weak_bg_fill = p.surface;
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0_f32, p.border);
    v.widgets.noninteractive.fg_stroke = Stroke::new(1.0_f32, p.text_secondary);
    v.widgets.noninteractive.corner_radius = radius;

    v.widgets.inactive.bg_fill = p.surface_variant;
    v.widgets.inactive.weak_bg_fill = p.surface_variant;
    v.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, p.border);
    v.widgets.inactive.fg_stroke = Stroke::new(1.0_f32, p.text_primary);
    v.widgets.inactive.corner_radius = radius;

    v.widgets.hovered.bg_fill = p.muted(p.accent, 0.72);
    v.widgets.hovered.weak_bg_fill = p.muted(p.accent, 0.72);
    v.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, p.muted(p.accent, 0.4));
    v.widgets.hovered.fg_stroke = Stroke::new(1.0_f32, p.text_primary);
    v.widgets.hovered.corner_radius = radius;
    // egui's default grows widgets on hover; a 1px jitter under the cursor
    // looks like a rendering bug rather than feedback.
    v.widgets.hovered.expansion = 0.0;

    v.widgets.active.bg_fill = p.accent;
    v.widgets.active.weak_bg_fill = p.accent;
    v.widgets.active.bg_stroke = Stroke::new(1.0_f32, p.accent);
    v.widgets.active.fg_stroke = Stroke::new(1.0_f32, p.on_accent);
    v.widgets.active.corner_radius = radius;
    v.widgets.active.expansion = 0.0;

    v.widgets.open.bg_fill = p.surface_variant;
    v.widgets.open.weak_bg_fill = p.surface_variant;
    v.widgets.open.bg_stroke = Stroke::new(1.0_f32, p.border);
    v.widgets.open.fg_stroke = Stroke::new(1.0_f32, p.text_primary);
    v.widgets.open.corner_radius = radius;

    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn readable_text_is_chosen_for_both_extremes() {
        assert_eq!(
            readable_on(Color32::WHITE),
            Color32::from_rgb(0x0B, 0x0D, 0x11)
        );
        assert_eq!(readable_on(Color32::BLACK), Color32::WHITE);
    }

    /// A mid-bright Windows accent (the default blue) must take white text,
    /// and a bright yellow one must not.
    #[test]
    fn readable_text_handles_real_windows_accents() {
        assert_eq!(
            readable_on(Color32::from_rgb(0x00, 0x78, 0xD4)),
            Color32::WHITE
        );
        assert_eq!(
            readable_on(Color32::from_rgb(0xFF, 0xE5, 0x00)),
            Color32::from_rgb(0x0B, 0x0D, 0x11),
        );
    }

    #[test]
    fn muted_blends_towards_the_background() {
        let p = Palette::DARK;
        assert_eq!(p.muted(p.accent, 0.0), p.accent);
        assert_eq!(p.muted(p.accent, 1.0), p.bg);
    }

    #[test]
    fn muted_clamps_out_of_range_amounts() {
        let p = Palette::LIGHT;
        assert_eq!(p.muted(p.accent, -5.0), p.accent);
        assert_eq!(p.muted(p.accent, 5.0), p.bg);
    }

    /// Every role has to differ from the surface it sits on, or the theme is
    /// silently unreadable somewhere.
    #[test]
    fn both_palettes_separate_text_from_background() {
        for p in [Palette::DARK, Palette::LIGHT] {
            assert_ne!(p.text_primary, p.bg);
            assert_ne!(p.text_primary, p.surface);
            assert_ne!(p.border, p.surface);
            assert_ne!(p.surface, p.bg);
        }
    }

    #[test]
    fn appearance_labels_are_distinct() {
        let labels: Vec<_> = Appearance::ALL.iter().map(|a| a.label()).collect();
        assert_eq!(labels.len(), 3);
        for (i, a) in labels.iter().enumerate() {
            for b in labels.iter().skip(i + 1) {
                assert_ne!(a, b);
            }
        }
    }
}
