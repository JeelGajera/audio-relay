//! Windows integration that makes the window feel native rather than merely
//! present: the Mica backdrop, the immersive dark titlebar, the system accent
//! colour, and the system UI font.
//!
//! Every one of these is best-effort. If a call fails, or the OS is older
//! than the feature, the app falls back to a perfectly usable opaque window
//! with its own palette — none of it is load-bearing. Non-Windows builds get
//! no-ops so the crate still compiles and tests on Linux CI.

use eframe::egui;

/// The window/taskbar icon, drawn at build time (see `build.rs`).
pub fn app_icon() -> Option<std::sync::Arc<egui::IconData>> {
    const SIZE: u32 = 64;
    let rgba: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/icon.rgba"));
    if rgba.len() != (SIZE * SIZE * 4) as usize {
        return None;
    }
    Some(std::sync::Arc::new(egui::IconData {
        rgba: rgba.to_vec(),
        width: SIZE,
        height: SIZE,
    }))
}

#[cfg(target_os = "windows")]
pub fn system_accent_color() -> Option<egui::Color32> {
    windows_impl::accent_color()
}

#[cfg(not(target_os = "windows"))]
pub fn system_accent_color() -> Option<egui::Color32> {
    None
}

/// Applies the Mica backdrop and dark-mode titlebar to the native window.
///
/// Called once from the `eframe` creation hook, where the window handle is
/// already available.
#[cfg(target_os = "windows")]
pub fn configure_window(cc: &eframe::CreationContext<'_>) {
    windows_impl::configure_window(cc);
}

#[cfg(not(target_os = "windows"))]
pub fn configure_window(cc: &eframe::CreationContext<'_>) {
    let _ = cc;
}

/// Swaps egui's bundled proportional font for the system UI font.
///
/// egui's default (Ubuntu Light) is a fine typeface but an immediately
/// foreign one on Windows. Loading Segoe UI from the system — rather than
/// bundling a font — costs no binary size, and falls back silently to the
/// bundled default if the file isn't where we expect.
#[cfg(target_os = "windows")]
pub fn install_fonts(ctx: &egui::Context) {
    windows_impl::install_fonts(ctx);
}

#[cfg(not(target_os = "windows"))]
pub fn install_fonts(ctx: &egui::Context) {
    let _ = ctx;
}

#[cfg(target_os = "windows")]
mod windows_impl {
    use eframe::egui;
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use windows::core::w;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Dwm::{
        DwmSetWindowAttribute, DWMWA_SYSTEMBACKDROP_TYPE, DWMWA_USE_IMMERSIVE_DARK_MODE,
    };
    use windows::Win32::System::Registry::{
        RegCloseKey, RegOpenKeyExW, RegQueryValueExW, HKEY, HKEY_CURRENT_USER, KEY_READ, REG_DWORD,
    };

    /// `DWMSBT_MAINWINDOW` — the Mica material, as used by Explorer and
    /// Settings. Windows 10 ignores the attribute entirely.
    const DWMSBT_MAINWINDOW: u32 = 2;

    pub fn configure_window(cc: &eframe::CreationContext<'_>) {
        let Ok(handle) = cc.window_handle() else {
            return;
        };
        let RawWindowHandle::Win32(win32) = handle.as_raw() else {
            return;
        };
        let hwnd = HWND(win32.hwnd.get() as *mut std::ffi::c_void);

        let dark: windows::Win32::Foundation::BOOL =
            matches!(cc.egui_ctx.theme(), egui::Theme::Dark).into();
        // Both calls fail harmlessly on Windows 10 and on builds predating the
        // attributes; there is nothing useful to do about it either way.
        unsafe {
            let _ = DwmSetWindowAttribute(
                hwnd,
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                std::ptr::addr_of!(dark).cast(),
                std::mem::size_of_val(&dark) as u32,
            );
            let backdrop = DWMSBT_MAINWINDOW;
            let _ = DwmSetWindowAttribute(
                hwnd,
                DWMWA_SYSTEMBACKDROP_TYPE,
                std::ptr::addr_of!(backdrop).cast(),
                std::mem::size_of_val(&backdrop) as u32,
            );
        }
    }

    /// Reads the user's accent colour from `HKCU\Software\Microsoft\Windows\DWM`.
    ///
    /// Stored as a DWORD in 0xAABBGGRR order — note that is *not* the RGBA
    /// order it looks like at a glance.
    pub fn accent_color() -> Option<egui::Color32> {
        unsafe {
            let mut key = HKEY::default();
            if RegOpenKeyExW(
                HKEY_CURRENT_USER,
                w!("Software\\Microsoft\\Windows\\DWM"),
                Some(0),
                KEY_READ,
                &mut key,
            )
            .is_err()
            {
                return None;
            }

            let mut value: u32 = 0;
            let mut size = std::mem::size_of::<u32>() as u32;
            let mut kind = REG_DWORD;
            let result = RegQueryValueExW(
                key,
                w!("AccentColor"),
                None,
                Some(&mut kind),
                Some(std::ptr::addr_of_mut!(value).cast()),
                Some(&mut size),
            );
            let _ = RegCloseKey(key);

            if result.is_err() || kind != REG_DWORD {
                return None;
            }
            let [r, g, b, _a] = [
                (value & 0xFF) as u8,
                ((value >> 8) & 0xFF) as u8,
                ((value >> 16) & 0xFF) as u8,
                ((value >> 24) & 0xFF) as u8,
            ];
            Some(egui::Color32::from_rgb(r, g, b))
        }
    }

    pub fn install_fonts(ctx: &egui::Context) {
        // Segoe UI Variable is the Windows 11 UI face; plain Segoe UI is the
        // Windows 10 one. Try the modern file first.
        const CANDIDATES: [&str; 3] = [
            r"C:\Windows\Fonts\SegUIVar.ttf",
            r"C:\Windows\Fonts\segoeui.ttf",
            r"C:\Windows\Fonts\tahoma.ttf",
        ];

        let Some(bytes) = CANDIDATES.iter().find_map(|path| std::fs::read(path).ok()) else {
            return; // keep egui's bundled font
        };

        let mut fonts = egui::FontDefinitions::default();
        fonts.font_data.insert(
            "system-ui".to_owned(),
            std::sync::Arc::new(egui::FontData::from_owned(bytes)),
        );
        // Insert ahead of the bundled font rather than replacing the list, so
        // the fallback chain (and therefore emoji and CJK coverage) survives.
        fonts
            .families
            .entry(egui::FontFamily::Proportional)
            .or_default()
            .insert(0, "system-ui".to_owned());
        ctx.set_fonts(fonts);
    }
}
