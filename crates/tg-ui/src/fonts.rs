//! Fonts the kit draws with but doesn't ship yet: Inter for the UI and JetBrains Mono for the editor, as in the
//! Kotlin app. Until they are bundled, a host can load them from a folder; without them the system UI font and a
//! platform monospace font stand in.

use std::borrow::Cow;
use std::path::Path;

use gpui::App;

/// Loads every `.ttf` and `.otf` in [dir] into the text system; returns how many were loaded.
pub fn load_font_dir(cx: &mut App, dir: &Path) -> usize {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return 0;
    };
    let fonts: Vec<Cow<'static, [u8]>> = entries
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            p.extension()
                .is_some_and(|e| e.eq_ignore_ascii_case("ttf") || e.eq_ignore_ascii_case("otf"))
        })
        .filter_map(|p| std::fs::read(p).ok().map(Cow::Owned))
        .collect();
    let count = fonts.len();
    if count > 0 && cx.text_system().add_fonts(fonts).is_err() {
        return 0;
    }
    count
}
