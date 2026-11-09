//! A theme variant resolved into every colour the app draws with, following the Kotlin `resolveTheme` rule for rule.
//!
//! Keys a theme leaves out are filled in one of three ways: from a formula over the theme's own core colours, from
//! Graphite's variant of the same appearance, or (text and control colours) by keeping Int UI's colours, which live in
//! [crate::ui].

use std::collections::BTreeMap;

use crate::color::{Rgba, lerp, parse_theme_color, readable_on};
use crate::spec::{SyntaxStyle, ThemeSpec};

/// The "Islands" colours: a tinted frame and rounded content islands floating on it.
#[derive(Clone, Debug, PartialEq)]
pub struct Islands {
    pub frame: Rgba,
    pub island: Rgba,
    pub island_border: Rgba,
    /// Separators inside an island.
    pub divider: Rgba,
    pub accent: Rgba,
    /// Text and icons on [accent](Self::accent).
    pub accent_foreground: Rgba,
    pub stripe_hover: Rgba,
    pub chip_hover: Rgba,
    /// Dims the window behind a floating panel.
    pub scrim: Rgba,
}

/// Colours laid over Int UI's globals; None parts keep Int UI's.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct IntUiOverrides {
    pub text: Option<Rgba>,
    pub text_muted: Option<Rgba>,
    pub text_disabled: Option<Rgba>,
    pub error: Option<Rgba>,
    pub warning: Option<Rgba>,
    pub border: Option<Rgba>,
    pub focus: Option<Rgba>,
}

/// Restyled controls, present when a theme sets at least one of [CONTROL_KEYS].
#[derive(Clone, Debug, PartialEq)]
pub struct ControlColors {
    pub text: Rgba,
    pub text_muted: Rgba,
    pub text_disabled: Rgba,
    pub focus: Rgba,
    pub link: Rgba,
    pub input_background: Rgba,
    pub input_border: Rgba,
    pub popup_background: Rgba,
    pub popup_border: Rgba,
    pub selection: Rgba,
    pub selection_inactive: Rgba,
    pub hover: Rgba,
    pub button_background: Rgba,
    pub button_foreground: Rgba,
    pub button_hover: Rgba,
    pub button_pressed: Rgba,
    pub secondary_border: Rgba,
}

#[derive(Clone, Debug, PartialEq)]
pub struct EditorColors {
    /// None keeps the panel colour.
    pub background: Option<Rgba>,
    /// None keeps the theme's text colour.
    pub foreground: Option<Rgba>,
    pub current_line: Rgba,
    pub find_match: Rgba,
    pub find_current: Rgba,
    pub bracket: Rgba,
    pub bracket_border: Rgba,
}

#[derive(Clone, Debug, PartialEq)]
pub struct GridColors {
    pub modified: Rgba,
    pub inserted: Rgba,
    pub deleted: Rgba,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ChartColors {
    pub background: Rgba,
    pub text: Rgba,
    pub secondary_text: Rgba,
    pub grid: Rgba,
    pub palette: Vec<Rgba>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DiagramColors {
    pub background: Rgba,
    pub grid: Rgba,
    pub boxed: Rgba,
    pub header: Rgba,
    pub header_text: Rgba,
    pub border: Rgba,
    pub text: Rgba,
    pub muted: Rgba,
    pub line: Rgba,
    pub primary_key: Rgba,
    pub foreign_key: Rgba,
    pub matched: Rgba,
}

/// A SQL token's resolved look.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TokenStyle {
    pub color: Rgba,
    pub italic: bool,
    pub bold: bool,
}

/// The SQL editor's token kinds, by their theme-file key.
pub const SYNTAX_TOKENS: [&str; 6] = [
    "keyword",
    "string",
    "number",
    "comment",
    "quotedIdentifier",
    "parameter",
];

/// Keys that, when a theme sets any of them, make it restyle the controls.
pub const CONTROL_KEYS: [&str; 14] = [
    "border",
    "border.focused",
    "text.link",
    "input.background",
    "input.border",
    "popup.background",
    "popup.border",
    "list.selection.background",
    "list.selection.inactive.background",
    "list.hover.background",
    "button.background",
    "button.foreground",
    "button.hover.background",
    "button.secondary.border",
];

/// A theme variant with every colour the app draws with.
#[derive(Clone, Debug, PartialEq)]
pub struct ColorTheme {
    pub name: String,
    pub family: String,
    pub is_dark: bool,
    pub islands: Islands,
    /// Text the app draws itself (charts, diagrams); always set.
    pub text: Rgba,
    pub text_muted: Rgba,
    pub int_ui: IntUiOverrides,
    /// Restyled controls, or None to keep Int UI's.
    pub controls: Option<ControlColors>,
    /// By [SYNTAX_TOKENS] key.
    pub syntax: BTreeMap<String, TokenStyle>,
    pub editor: EditorColors,
    pub grid: GridColors,
    pub chart: ChartColors,
    pub diagram: DiagramColors,
}

/// Resolves [spec] against [base], the Graphite variant of the same appearance that supplies keys a theme leaves
/// out. [base] is None only when resolving Graphite itself.
pub fn resolve_theme(spec: &ThemeSpec, family: &str, base: Option<&ThemeSpec>) -> ColorTheme {
    let dark = spec.is_dark();
    let own = |key: &str| spec.colors.get(key).and_then(|v| parse_theme_color(v));
    let inherited = |key: &str| {
        base.and_then(|b| b.colors.get(key))
            .and_then(|v| parse_theme_color(v))
    };
    let color = |key: &str, fallback: Rgba| own(key).or_else(|| inherited(key)).unwrap_or(fallback);
    let pick = |d: u32, l: u32| Rgba::argb(if dark { d } else { l });

    let frame = color("frame.background", pick(0xFF27292E, 0xFFE9EBF0));
    let island = color("island.background", pick(0xFF1E1F22, 0xFFFFFFFF));
    let island_border = color("island.border", pick(0x14FFFFFF, 0x14000000));
    let divider = color("divider", pick(0xFF393B40, 0xFFE4E5EA));
    let accent = color("accent", Rgba::rgb(0x3574F0));
    // The accent's own label colour only carries over when the accent does; a new accent gets black or white.
    let accent_foreground = own("accent.foreground")
        .or_else(|| {
            if own("accent").is_none() {
                inherited("accent.foreground")
            } else {
                None
            }
        })
        .unwrap_or_else(|| readable_on(accent));
    let text = own("text")
        .or_else(|| inherited("text"))
        .unwrap_or(pick(0xFFDFE1E5, 0xFF1E1F22));
    let text_muted = own("text.muted")
        .or_else(|| inherited("text.muted"))
        .unwrap_or_else(|| lerp(text, island, 0.38));
    let text_disabled = own("text.disabled").unwrap_or_else(|| lerp(text, island, 0.55));

    let islands = Islands {
        frame,
        island,
        island_border,
        divider,
        accent,
        accent_foreground,
        stripe_hover: own("toolbar.hover.background").unwrap_or(pick(0x14FFFFFF, 0x14000000)),
        chip_hover: own("chip.hover.background").unwrap_or(pick(0x1AFFFFFF, 0x12000000)),
        scrim: own("scrim").unwrap_or(pick(0x8C000000, 0x47000000)),
    };

    let int_ui = IntUiOverrides {
        text: own("text"),
        text_muted: own("text.muted"),
        text_disabled: own("text.disabled"),
        error: own("text.error"),
        warning: own("text.warning"),
        border: own("border"),
        focus: own("border.focused"),
    };

    let controls = if spec
        .colors
        .keys()
        .any(|k| CONTROL_KEYS.contains(&k.as_str()))
    {
        let button_background = own("button.background").unwrap_or(accent);
        let input_border = own("input.border").unwrap_or(divider);
        let toward = if dark { Rgba::WHITE } else { Rgba::BLACK };
        Some(ControlColors {
            text,
            text_muted,
            text_disabled,
            focus: own("border.focused").unwrap_or(accent),
            link: own("text.link").unwrap_or(accent),
            input_background: own("input.background").unwrap_or(island),
            input_border,
            popup_background: own("popup.background").unwrap_or(island),
            popup_border: own("popup.border").unwrap_or(divider),
            selection: own("list.selection.background")
                .unwrap_or_else(|| accent.with_alpha(if dark { 0.35 } else { 0.2 })),
            selection_inactive: own("list.selection.inactive.background")
                .unwrap_or_else(|| text.with_alpha(0.1)),
            hover: own("list.hover.background").unwrap_or_else(|| text.with_alpha(0.06)),
            button_background,
            button_foreground: own("button.foreground").unwrap_or_else(|| {
                if own("button.background").is_none() {
                    accent_foreground
                } else {
                    readable_on(button_background)
                }
            }),
            button_hover: own("button.hover.background")
                .unwrap_or_else(|| lerp(button_background, toward, 0.08)),
            button_pressed: lerp(button_background, Rgba::BLACK, 0.12),
            secondary_border: own("button.secondary.border").unwrap_or(input_border),
        })
    } else {
        None
    };

    let editor = EditorColors {
        background: own("editor.background"),
        foreground: own("editor.foreground"),
        current_line: color("editor.currentLine", pick(0x0FFFFFFF, 0x0A000000)),
        find_match: color("editor.findMatch", pick(0x4D3D6185, 0x66F2E36B)),
        find_current: color("editor.findMatch.current", pick(0x996F8FBF, 0xCCF2C94C)),
        bracket: color("editor.bracket", pick(0x3343A047, 0x3393D9A3)),
        bracket_border: color("editor.bracket.border", Rgba::argb(0x9943A047)),
    };

    // Edits are tinted with the theme's accent; inserts and deletes keep Graphite's green and red.
    let grid = GridColors {
        modified: own("grid.modified")
            .unwrap_or_else(|| accent.with_alpha(if dark { 0.25 } else { 0.2 })),
        inserted: color("grid.inserted", pick(0x3349A35B, 0x3355A76A)),
        deleted: color("grid.deleted", pick(0x33DB5C5C, 0x33E55765)),
    };

    let parse_all = |list: &[String]| -> Vec<Rgba> {
        list.iter().filter_map(|c| parse_theme_color(c)).collect()
    };
    let mut palette = parse_all(&spec.chart);
    if palette.is_empty() {
        palette = base.map(|b| parse_all(&b.chart)).unwrap_or_default();
    }
    if palette.is_empty() {
        palette = vec![accent];
    }
    let chart = ChartColors {
        background: own("chart.background").unwrap_or(island),
        text: own("chart.text").unwrap_or(text),
        secondary_text: own("chart.text.muted").unwrap_or(text_muted),
        grid: own("chart.grid").unwrap_or_else(|| lerp(divider, island, 0.4)),
        palette,
    };

    let diagram = DiagramColors {
        background: own("diagram.background").unwrap_or(island),
        grid: own("diagram.grid").unwrap_or_else(|| lerp(island, text, 0.05)),
        boxed: own("diagram.box").unwrap_or_else(|| lerp(island, text, 0.05)),
        header: own("diagram.header").unwrap_or(divider),
        header_text: own("diagram.header.text").unwrap_or(text),
        border: own("diagram.border").unwrap_or_else(|| lerp(divider, text, 0.15)),
        text: own("diagram.text").unwrap_or(text),
        muted: own("diagram.text.muted").unwrap_or(text_muted),
        line: own("diagram.line").unwrap_or(text_muted),
        primary_key: color("diagram.primaryKey", Rgba::rgb(0xE0B53E)),
        foreign_key: own("diagram.foreignKey").unwrap_or(accent),
        matched: color("diagram.match", Rgba::rgb(0xE0B53E)),
    };

    let syntax = SYNTAX_TOKENS
        .iter()
        .map(|&token| {
            let style = spec
                .syntax
                .get(token)
                .or_else(|| base.and_then(|b| b.syntax.get(token)));
            (
                token.to_owned(),
                token_style(style, text, token == "comment"),
            )
        })
        .collect();

    ColorTheme {
        name: spec.name.clone(),
        family: family.to_owned(),
        is_dark: dark,
        islands,
        text,
        text_muted,
        int_ui,
        controls,
        syntax,
        editor,
        grid,
        chart,
        diagram,
    }
}

fn token_style(style: Option<&SyntaxStyle>, fallback: Rgba, italic_by_default: bool) -> TokenStyle {
    let italic = match style
        .and_then(|s| s.font_style.as_deref())
        .map(str::to_lowercase)
        .as_deref()
    {
        Some("italic") => true,
        Some("normal") => false,
        _ => italic_by_default,
    };
    TokenStyle {
        color: style
            .and_then(|s| s.color.as_deref())
            .and_then(parse_theme_color)
            .unwrap_or(fallback),
        italic,
        bold: style
            .and_then(|s| s.font_weight.as_deref())
            .is_some_and(|w| w.eq_ignore_ascii_case("bold")),
    }
}

impl ColorTheme {
    /// Every colour by its dotted token name, None where the app keeps an Int UI colour or the panel's. The names are
    /// those of `conformance/golden/themes/<name>.json`.
    pub fn tokens(&self) -> BTreeMap<&'static str, Option<Rgba>> {
        let i = &self.islands;
        let o = &self.int_ui;
        let c = self.controls.as_ref();
        let e = &self.editor;
        let d = &self.diagram;
        [
            ("frame.background", Some(i.frame)),
            ("island.background", Some(i.island)),
            ("island.border", Some(i.island_border)),
            ("divider", Some(i.divider)),
            ("accent", Some(i.accent)),
            ("accent.foreground", Some(i.accent_foreground)),
            ("toolbar.hover.background", Some(i.stripe_hover)),
            ("chip.hover.background", Some(i.chip_hover)),
            ("scrim", Some(i.scrim)),
            ("text", Some(self.text)),
            ("text.muted", Some(self.text_muted)),
            ("jewel.text", o.text),
            ("jewel.text.muted", o.text_muted),
            ("jewel.text.disabled", o.text_disabled),
            ("jewel.text.error", o.error),
            ("jewel.text.warning", o.warning),
            ("jewel.border", o.border),
            ("jewel.border.focused", o.focus),
            ("controls.text", c.map(|c| c.text)),
            ("controls.text.muted", c.map(|c| c.text_muted)),
            ("controls.text.disabled", c.map(|c| c.text_disabled)),
            ("controls.focus", c.map(|c| c.focus)),
            ("controls.link", c.map(|c| c.link)),
            ("controls.input.background", c.map(|c| c.input_background)),
            ("controls.input.border", c.map(|c| c.input_border)),
            ("controls.popup.background", c.map(|c| c.popup_background)),
            ("controls.popup.border", c.map(|c| c.popup_border)),
            ("controls.selection", c.map(|c| c.selection)),
            (
                "controls.selection.inactive",
                c.map(|c| c.selection_inactive),
            ),
            ("controls.hover", c.map(|c| c.hover)),
            ("controls.button.background", c.map(|c| c.button_background)),
            ("controls.button.foreground", c.map(|c| c.button_foreground)),
            ("controls.button.hover", c.map(|c| c.button_hover)),
            ("controls.button.pressed", c.map(|c| c.button_pressed)),
            ("controls.secondary.border", c.map(|c| c.secondary_border)),
            ("editor.background", e.background),
            ("editor.foreground", e.foreground),
            ("editor.currentLine", Some(e.current_line)),
            ("editor.findMatch", Some(e.find_match)),
            ("editor.findMatch.current", Some(e.find_current)),
            ("editor.bracket", Some(e.bracket)),
            ("editor.bracket.border", Some(e.bracket_border)),
            ("grid.modified", Some(self.grid.modified)),
            ("grid.inserted", Some(self.grid.inserted)),
            ("grid.deleted", Some(self.grid.deleted)),
            ("chart.background", Some(self.chart.background)),
            ("chart.text", Some(self.chart.text)),
            ("chart.text.muted", Some(self.chart.secondary_text)),
            ("chart.grid", Some(self.chart.grid)),
            ("diagram.background", Some(d.background)),
            ("diagram.grid", Some(d.grid)),
            ("diagram.box", Some(d.boxed)),
            ("diagram.header", Some(d.header)),
            ("diagram.header.text", Some(d.header_text)),
            ("diagram.border", Some(d.border)),
            ("diagram.text", Some(d.text)),
            ("diagram.text.muted", Some(d.muted)),
            ("diagram.line", Some(d.line)),
            ("diagram.primaryKey", Some(d.primary_key)),
            ("diagram.foreignKey", Some(d.foreign_key)),
            ("diagram.match", Some(d.matched)),
        ]
        .into_iter()
        .collect()
    }
}
