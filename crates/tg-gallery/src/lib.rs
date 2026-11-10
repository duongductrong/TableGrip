//! The UI kit's gallery: every specimen drawn on a canvas of a fixed size, laid out exactly like its Kotlin twin in
//! `app/src/test/kotlin/dev/tablegrip/app/conformance/gallery/<Name>Specimen.kt`, so a capture can be compared pixel
//! for pixel with `conformance/reference/<theme>/<name>.png`.
//!
//! To add a specimen, add a file to `src/specimens/` with `pub fn specimen() -> Specimen`; `build.rs` registers it.

use gpui::{AnyElement, App, Div, Window, div, prelude::*};
use tg_ui::ThemeExt;

pub mod capture;

mod registry {
    include!(concat!(env!("OUT_DIR"), "/specimens.rs"));
}

pub use registry::all;

/// One specimen: a name (the reference file's), a canvas size in dp and what to draw on it.
pub struct Specimen {
    pub name: &'static str,
    pub width: f32,
    pub height: f32,
    pub render: fn(&mut Window, &mut App) -> AnyElement,
}

/// A box at ([x], [y]) dp on the canvas, as the Kotlin specimens place things with `Modifier.offset`.
pub fn at(cx: &App, x: f32, y: f32) -> Div {
    let t = cx.theme();
    div().absolute().left(t.dp(x)).top(t.dp(y))
}

/// The specimen's canvas: [width] × [height] dp on the frame colour, children positioned absolutely.
pub fn canvas(cx: &App, width: f32, height: f32) -> Div {
    let t = cx.theme();
    div()
        .relative()
        .flex_none()
        .w(t.dp(width))
        .h(t.dp(height))
        .bg(tg_ui::hsla(t.theme.colors.islands.frame))
}

/// The theme named [name] (a built-in variant such as `One Dark`) with default settings.
pub fn theme_named(name: &str) -> Option<tg_theme::Theme> {
    let registry = tg_theme::Registry::new();
    let dark = registry.entry(name)?.spec.is_dark();
    let mut settings = tg_theme::AppearanceSettings::default();
    if dark {
        settings.theme.dark = name.to_owned();
    } else {
        settings.theme.light = name.to_owned();
    }
    Some(settings.theme(&registry, dark))
}
