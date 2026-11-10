//! Capturing a specimen from a real window, for the image diff (macOS only for now).
//!
//! The window is larger than the specimen by [MARGIN] on every side, so the window's rounded corners and edges stay
//! clear of it; the capture is then converted from the display's colour profile to sRGB (`screencapture` tags it with
//! the display's profile, which shifts saturated colours by several levels) and cropped to the specimen.

use std::path::Path;
use std::process::Command;

/// Space around the specimen inside the capture window, in dp.
pub const MARGIN: f32 = 32.0;

/// The macOS window number of [window], which `screencapture -l` takes.
#[cfg(target_os = "macos")]
// objc 0.2's `msg_send!` checks a `cargo-clippy` feature this crate doesn't declare.
#[allow(unexpected_cfgs)]
pub fn window_number(window: &gpui::Window) -> Option<i64> {
    use objc::{msg_send, runtime::Object, sel, sel_impl};
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};

    let handle = HasWindowHandle::window_handle(window).ok()?;
    let RawWindowHandle::AppKit(appkit) = handle.as_raw() else {
        return None;
    };
    let view = appkit.ns_view.as_ptr() as *mut Object;
    // SAFETY: `view` is the live NSView of a window GPUI owns; both messages are plain getters.
    unsafe {
        let ns_window: *mut Object = msg_send![view, window];
        if ns_window.is_null() {
            return None;
        }
        let number: isize = msg_send![ns_window, windowNumber];
        Some(number as i64)
    }
}

#[cfg(not(target_os = "macos"))]
pub fn window_number(_: &gpui::Window) -> Option<i64> {
    None
}

/// Captures window [number] to [out] as an sRGB PNG cropped to the specimen: [width] × [height] dp at [scale] device
/// pixels per dp, [MARGIN] dp in from the window's top left.
pub fn capture(number: i64, out: &Path, width: f32, height: f32, scale: f32) -> Result<(), String> {
    let raw = out.with_extension("raw.png");
    run(Command::new("screencapture")
        .arg("-x")
        .arg("-o")
        .arg(format!("-l{number}"))
        .arg("-t")
        .arg("png")
        .arg(&raw))?;
    run(Command::new("sips")
        .arg("-m")
        .arg("/System/Library/ColorSync/Profiles/sRGB Profile.icc")
        .arg(&raw)
        .arg("--out")
        .arg(out))?;
    let _ = std::fs::remove_file(&raw);
    let image = image::open(out).map_err(|e| e.to_string())?.into_rgba8();
    let px = |dp: f32| (dp * scale).round() as u32;
    let (x, y, w, h) = (px(MARGIN), px(MARGIN), px(width), px(height));
    if x + w > image.width() || y + h > image.height() {
        return Err(format!(
            "capture is {}×{}, too small for the specimen at {x},{y} {w}×{h}",
            image.width(),
            image.height()
        ));
    }
    image::imageops::crop_imm(&image, x, y, w, h)
        .to_image()
        .save(out)
        .map_err(|e| e.to_string())
}

fn run(command: &mut Command) -> Result<(), String> {
    let output = command.output().map_err(|e| format!("{command:?}: {e}"))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(format!(
            "{command:?} failed: {}",
            String::from_utf8_lossy(&output.stderr)
        ))
    }
}
