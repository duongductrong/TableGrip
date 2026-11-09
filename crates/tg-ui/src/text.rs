//! Plain text in the UI style (Inter at `uiFontSize`, 13 by default) or the editor style, in the colours the app
//! passes: normal, info, error, warning, disabled, or any theme colour.

use gpui::{
    App, FontWeight, IntoElement, Pixels, RenderOnce, SharedString, StyleRefinement, Styled,
    Window, div, font, prelude::*, px,
};
use tg_theme::Rgba;

use crate::theme::{ThemeExt, hsla};

/// The colour of a [Text]; the named ones follow the theme.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub enum TextColor {
    #[default]
    Normal,
    Info,
    Error,
    Warning,
    Disabled,
    Custom(Rgba),
}

#[derive(IntoElement)]
pub struct Text {
    text: SharedString,
    color: TextColor,
    size: Option<f32>,
    bold: bool,
    editor: bool,
    ellipsis: bool,
    style: StyleRefinement,
}

/// [text] in the default UI style.
pub fn text(text: impl Into<SharedString>) -> Text {
    Text {
        text: text.into(),
        color: TextColor::Normal,
        size: None,
        bold: false,
        editor: false,
        ellipsis: false,
        style: StyleRefinement::default(),
    }
}

impl Text {
    pub fn color(mut self, color: TextColor) -> Self {
        self.color = color;
        self
    }

    /// A font size in sp (dp); the line height keeps the style's leading.
    pub fn size(mut self, sp: f32) -> Self {
        self.size = Some(sp);
        self
    }

    pub fn bold(mut self) -> Self {
        self.bold = true;
        self
    }

    /// The editor style: the editor font from settings (JetBrains Mono by default).
    pub fn editor(mut self) -> Self {
        self.editor = true;
        self
    }

    /// One line, cut with `…` when it doesn't fit (`maxLines = 1, overflow = Ellipsis`).
    pub fn ellipsis(mut self) -> Self {
        self.ellipsis = true;
        self
    }
}

impl Styled for Text {
    fn style(&mut self) -> &mut StyleRefinement {
        &mut self.style
    }
}

impl RenderOnce for Text {
    fn render(self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let fonts = &t.theme.fonts;
        let ui = &t.theme.ui;
        let (family, base_size, base_line) = if self.editor {
            (
                t.editor_font.clone(),
                fonts.editor_size,
                fonts.editor_line_height,
            )
        } else {
            (t.ui_font.clone(), fonts.ui_size, fonts.ui_line_height)
        };
        let size = self.size.unwrap_or(base_size);
        let line = base_line - base_size + size;
        let color = match self.color {
            TextColor::Normal => ui.text,
            TextColor::Info => ui.text_info,
            TextColor::Error => ui.text_error,
            TextColor::Warning => ui.text_warning,
            TextColor::Disabled => ui.text_disabled,
            TextColor::Custom(c) => c,
        };
        let trim = leading_trim(cx, &family, t.dp(size), t.dp(line));
        let t = cx.theme();
        let mut el = div()
            .font_family(family)
            .text_size(t.dp(size))
            .line_height(t.dp(line))
            .my(-trim)
            .text_color(hsla(color))
            .when(self.bold, |d| d.font_weight(FontWeight::BOLD))
            .when(self.ellipsis, |d| {
                d.overflow_hidden().whitespace_nowrap().text_ellipsis()
            });
        el.style().refine(&self.style);
        el.child(self.text)
    }
}

/// The leading Compose trims above the first line and below the last (`LineHeightStyle` with `Trim.Both`, which
/// Int UI's text styles use): half of what the line height adds to the font's own height. A one-line text is then as
/// tall as the font, and its glyphs sit where the Kotlin app puts them; lines further down keep the full line height.
pub(crate) fn leading_trim(cx: &App, family: &SharedString, size: Pixels, line: Pixels) -> Pixels {
    let text = cx.text_system();
    let id = text.resolve_font(&font(family.clone()));
    let natural = text.ascent(id, size) + text.descent(id, size).abs();
    ((line - natural) / 2.0).max(px(0.0))
}
