//! Icons: IntelliJ's (Apache-2.0, see `assets/icons/intellij/manifest.json`) by their `AllIconsKeys` name, such as
//! `General.Add`, and TableGrip's own as `TableGrip.Agent`. Dark themes get the `_dark` variant when there is one.
//! Icons draw in their own colours unless tinted.

use std::borrow::Cow;

use gpui::{App, AssetSource, IntoElement, RenderOnce, SharedString, Window, img, prelude::*, svg};
use tg_theme::Rgba;

use crate::theme::{ThemeExt, hsla};

mod generated {
    include!(concat!(env!("OUT_DIR"), "/icons.rs"));
}

/// Serves the embedded icons to GPUI; install it with `Application::new().with_assets(Assets)`.
pub struct Assets;

impl AssetSource for Assets {
    fn load(&self, path: &str) -> gpui::Result<Option<Cow<'static, [u8]>>> {
        // `<name>@<n>px.svg` is `<name>.svg` declared n pixels wide and high. GPUI rasterises an SVG at its declared
        // size and then scales that to the element, which blurs small icons; declaring the size they are drawn at
        // gives a 1:1 raster, as the Kotlin app draws them.
        if let Some((base, px)) = path
            .strip_suffix("px.svg")
            .and_then(|rest| rest.rsplit_once('@'))
            && let Ok(px) = px.parse::<u32>()
        {
            let Some(bytes) = self.load(&format!("{base}.svg"))? else {
                return Ok(None);
            };
            let text = String::from_utf8_lossy(&bytes).replacen(
                "width=\"16\" height=\"16\"",
                &format!("width=\"{px}\" height=\"{px}\""),
                1,
            );
            return Ok(Some(Cow::Owned(text.into_bytes())));
        }
        Ok(generated::ASSETS
            .iter()
            .find(|(name, _)| *name == path)
            .map(|(_, bytes)| Cow::Borrowed(*bytes)))
    }

    fn list(&self, path: &str) -> gpui::Result<Vec<SharedString>> {
        Ok(generated::ASSETS
            .iter()
            .filter(|(name, _)| name.starts_with(path))
            .map(|(name, _)| SharedString::from(*name))
            .collect())
    }
}

/// Every icon key the kit knows.
pub fn keys() -> impl Iterator<Item = &'static str> {
    generated::KEYS.iter().map(|(key, _, _)| *key)
}

/// The asset path for [key] in a dark or light theme; None for an unknown key.
pub fn path(key: &str, dark: bool) -> Option<&'static str> {
    generated::KEYS
        .iter()
        .find(|(k, _, _)| *k == key)
        .map(|(_, light, dark_path)| {
            if dark {
                dark_path.unwrap_or(light)
            } else {
                light
            }
        })
}

#[derive(IntoElement)]
pub struct Icon {
    key: &'static str,
    size: f32,
    tint: Option<Rgba>,
    crisp: bool,
}

/// The icon [key] at 16dp.
pub fn icon(key: &'static str) -> Icon {
    Icon {
        key,
        size: 16.0,
        tint: None,
        crisp: false,
    }
}

impl Icon {
    /// Size in dp (the app uses 12, 14 and 16).
    pub fn size(mut self, dp: f32) -> Self {
        self.size = dp;
        self
    }

    /// Rasterises the icon at exactly the pixels it is drawn in, for small icons that must look as the Kotlin app's do.
    pub fn crisp(mut self) -> Self {
        self.crisp = true;
        self
    }

    /// Draws the icon's shape in one colour, as the app does for icons on the accent.
    pub fn tint(mut self, color: Rgba) -> Self {
        self.tint = Some(color);
        self
    }
}

impl RenderOnce for Icon {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme();
        let size = t.dp(self.size);
        let path = path(self.key, t.theme.is_dark())
            .unwrap_or_else(|| panic!("unknown icon key {}", self.key));
        match self.tint {
            Some(color) => svg()
                .path(path)
                .size(size)
                .text_color(hsla(color))
                .into_any_element(),
            None if self.crisp => {
                let device = (f32::from(size) * window.scale_factor()).round() as u32;
                img(format!("{}@{device}px.svg", path.trim_end_matches(".svg")))
                    .size(size)
                    .flex_none()
                    .into_any_element()
            }
            None => img(path).size(size).flex_none().into_any_element(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_key_has_an_embedded_light_file() {
        let keys: Vec<_> = keys().collect();
        assert_eq!(keys.len(), 80, "79 IntelliJ keys plus TableGrip.Agent");
        for key in keys {
            let light = path(key, false).unwrap();
            assert!(Assets.load(light).unwrap().is_some(), "{key}");
            assert!(
                Assets.load(path(key, true).unwrap()).unwrap().is_some(),
                "{key} dark"
            );
        }
    }

    #[test]
    fn dark_themes_get_dark_variants() {
        assert_eq!(
            path("General.Add", false),
            Some("icons/intellij/general/add.svg")
        );
        assert_eq!(
            path("General.Add", true),
            Some("icons/intellij/general/add_dark.svg")
        );
        assert_eq!(path("TableGrip.Agent", true), Some("icons/agent_dark.svg"));
        assert_eq!(path("Toolwindows.ToolWindowAskAI", false), None);
    }

    #[test]
    fn intellij_icons_keep_their_apache_header() {
        for (name, bytes) in generated::ASSETS
            .iter()
            .filter(|(n, _)| n.starts_with("icons/intellij/"))
        {
            let head = String::from_utf8_lossy(&bytes[..bytes.len().min(200)]);
            assert!(head.contains("Apache 2.0 license"), "{name}");
        }
    }
}
