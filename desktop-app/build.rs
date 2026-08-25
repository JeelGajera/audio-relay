use std::path::PathBuf;
use std::process::Command;

/// Build-time work, all of it derived rather than checked in:
///
/// - git metadata (commit hash + date) as compile-time env vars, shown on the
///   About screen;
/// - the app icon, drawn procedurally into both a raw RGBA blob (loaded at
///   runtime for the window/taskbar icon) and a `.ico` (stamped into the
///   `.exe` on Windows).
///
/// Generating the icon rather than committing binary assets means there is
/// one definition of the artwork, and no PNG decoder in the dependency tree.
fn main() {
    println!("cargo:rerun-if-changed=../.git/HEAD");
    println!("cargo:rerun-if-changed=build.rs");
    println!(
        "cargo:rustc-env=GIT_HASH={}",
        git_output(&["rev-parse", "--short=10", "HEAD"])
    );
    println!(
        "cargo:rustc-env=GIT_COMMIT_DATE={}",
        git_output(&["show", "-s", "--format=%cs", "HEAD"])
    );

    let out_dir = PathBuf::from(std::env::var("OUT_DIR").expect("cargo always sets OUT_DIR"));

    // Small enough to keep the binary lean; Windows scales it for larger
    // views. See AGENTS.md on keeping the footprint down.
    let window_icon = render_icon(WINDOW_ICON_SIZE);
    std::fs::write(out_dir.join("icon.rgba"), &window_icon).expect("writing window icon");

    let ico_pixels = render_icon(ICO_SIZE);
    let ico = encode_ico(&ico_pixels, ICO_SIZE);
    let ico_path = out_dir.join("icon.ico");
    std::fs::write(&ico_path, &ico).expect("writing .ico");

    #[cfg(windows)]
    {
        let mut res = winresource::WindowsResource::new();
        res.set_icon(ico_path.to_str().expect("OUT_DIR is valid UTF-8"));
        res.set("ProductName", "audio-relay");
        res.set("FileDescription", "audio-relay");
        res.set("LegalCopyright", "MIT licensed");
        if let Err(e) = res.compile() {
            // Not fatal: an icon-less binary still works, and failing the
            // build over cosmetics would be worse than the missing icon.
            println!("cargo:warning=could not embed Windows resources: {e}");
        }
    }
    #[cfg(not(windows))]
    let _ = ico_path;
}

const WINDOW_ICON_SIZE: u32 = 64;
const ICO_SIZE: u32 = 128;

/// Draws the "relay" glyph: a dot with two arcs radiating from it, on a deep
/// navy disc. Matches the Android adaptive icon so the two apps read as one
/// product.
///
/// Returns tightly-packed RGBA8, top row first.
fn render_icon(size: u32) -> Vec<u8> {
    const BG: [u8; 3] = [0x1B, 0x1F, 0x3B];
    const FG: [u8; 3] = [0xFF, 0xFF, 0xFF];
    // 3x3 supersampling — cheap at these sizes and the difference between a
    // crisp icon and a visibly jagged one.
    const SS: u32 = 3;

    let s = size as f32;
    let disc_center = (s * 0.5, s * 0.5);
    let disc_radius = s * 0.48;
    // Origin of the arcs, low-left of centre.
    let origin = (s * 0.34, s * 0.66);
    let dot_radius = s * 0.075;
    let arc_width = s * 0.075;
    let arc_radii = [s * 0.26, s * 0.42];

    let mut pixels = vec![0u8; (size * size * 4) as usize];
    for y in 0..size {
        for x in 0..size {
            let (mut inside_disc, mut inside_glyph) = (0u32, 0u32);
            for sy in 0..SS {
                for sx in 0..SS {
                    let px = x as f32 + (sx as f32 + 0.5) / SS as f32;
                    let py = y as f32 + (sy as f32 + 0.5) / SS as f32;

                    if distance(px, py, disc_center) <= disc_radius {
                        inside_disc += 1;
                    }

                    let d = distance(px, py, origin);
                    if d <= dot_radius {
                        inside_glyph += 1;
                        continue;
                    }
                    // Arcs sweep through the upper-right quadrant only.
                    if px >= origin.0 && py <= origin.1 {
                        for r in arc_radii {
                            if (d - r).abs() <= arc_width * 0.5 {
                                inside_glyph += 1;
                                break;
                            }
                        }
                    }
                }
            }

            let total = (SS * SS) as f32;
            let disc_alpha = inside_disc as f32 / total;
            let glyph_alpha = (inside_glyph as f32 / total).min(1.0);

            // Glyph over disc, disc over transparency.
            let alpha = disc_alpha.max(0.0);
            let rgb = blend(BG, FG, glyph_alpha);
            let i = ((y * size + x) * 4) as usize;
            pixels[i] = rgb[0];
            pixels[i + 1] = rgb[1];
            pixels[i + 2] = rgb[2];
            pixels[i + 3] = (alpha * 255.0).round() as u8;
        }
    }
    pixels
}

fn distance(x: f32, y: f32, to: (f32, f32)) -> f32 {
    ((x - to.0).powi(2) + (y - to.1).powi(2)).sqrt()
}

fn blend(from: [u8; 3], to: [u8; 3], t: f32) -> [u8; 3] {
    let t = t.clamp(0.0, 1.0);
    [
        (from[0] as f32 + (to[0] as f32 - from[0] as f32) * t) as u8,
        (from[1] as f32 + (to[1] as f32 - from[1] as f32) * t) as u8,
        (from[2] as f32 + (to[2] as f32 - from[2] as f32) * t) as u8,
    ]
}

/// Wraps RGBA pixels in a single-image `.ico`.
///
/// Uses the uncompressed 32bpp DIB form rather than the PNG form, so no image
/// encoder is needed. The DIB is stored bottom-up and BGRA, and carries a
/// (here entirely zero) AND mask that the format requires even when the
/// colour data already has an alpha channel.
fn encode_ico(rgba: &[u8], size: u32) -> Vec<u8> {
    let and_mask_row = size.div_ceil(8).div_ceil(4) * 4; // 1bpp, rows padded to 4 bytes
    let xor_len = size * size * 4;
    let and_len = and_mask_row * size;
    let image_len = 40 + xor_len + and_len;

    let mut out = Vec::with_capacity(22 + image_len as usize);

    // ICONDIR
    out.extend_from_slice(&0u16.to_le_bytes()); // reserved
    out.extend_from_slice(&1u16.to_le_bytes()); // type: icon
    out.extend_from_slice(&1u16.to_le_bytes()); // one image

    // ICONDIRENTRY — 0 encodes 256 in this field, which is why it is a u8.
    out.push(if size >= 256 { 0 } else { size as u8 });
    out.push(if size >= 256 { 0 } else { size as u8 });
    out.push(0); // palette size (none)
    out.push(0); // reserved
    out.extend_from_slice(&1u16.to_le_bytes()); // colour planes
    out.extend_from_slice(&32u16.to_le_bytes()); // bits per pixel
    out.extend_from_slice(&image_len.to_le_bytes());
    out.extend_from_slice(&22u32.to_le_bytes()); // offset: past ICONDIR + one entry

    // BITMAPINFOHEADER. Height is doubled because it describes the XOR
    // bitmap and the AND mask stacked together.
    out.extend_from_slice(&40u32.to_le_bytes());
    out.extend_from_slice(&(size as i32).to_le_bytes());
    out.extend_from_slice(&((size * 2) as i32).to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&32u16.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes()); // BI_RGB
    out.extend_from_slice(&0u32.to_le_bytes()); // size (may be 0 for BI_RGB)
    out.extend_from_slice(&0i32.to_le_bytes()); // x pixels/metre
    out.extend_from_slice(&0i32.to_le_bytes()); // y pixels/metre
    out.extend_from_slice(&0u32.to_le_bytes()); // colours used
    out.extend_from_slice(&0u32.to_le_bytes()); // important colours

    for y in (0..size).rev() {
        for x in 0..size {
            let i = ((y * size + x) * 4) as usize;
            out.push(rgba[i + 2]); // B
            out.push(rgba[i + 1]); // G
            out.push(rgba[i]); // R
            out.push(rgba[i + 3]); // A
        }
    }
    out.extend(std::iter::repeat_n(0u8, and_len as usize));

    out
}

fn git_output(args: &[&str]) -> String {
    Command::new("git")
        .args(args)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}
