//! The element that shapes and paints a [`TextInput`]'s text, selection, marked text and caret, and registers it with
//! the window as the input handler while it has focus.

use gpui::{
    App, Bounds, ContentMask, Element, ElementId, ElementInputHandler, Entity, GlobalElementId,
    InspectorElementId, IntoElement, LayoutId, Pixels, Point, SharedString, Style, TextAlign,
    TextRun, UnderlineStyle, Window, WrappedLine, fill, font, point, px, relative, size,
};

use super::layout::TextLayout;
use super::{CARET_WIDTH, FieldColors, TextInput};
use crate::text::leading_trim;
use crate::theme::ThemeExt;

pub(super) struct TextElement {
    pub input: Entity<TextInput>,
    pub colors: FieldColors,
    pub focused: bool,
}

pub(super) struct Prepaint {
    layout: TextLayout,
    placeholder: Option<WrappedLine>,
    origin: Point<Pixels>,
    caret: Option<Bounds<Pixels>>,
    selection: Vec<Bounds<Pixels>>,
}

impl IntoElement for TextElement {
    type Element = Self;

    fn into_element(self) -> Self {
        self
    }
}

impl Element for TextElement {
    type RequestLayoutState = ();
    type PrepaintState = Prepaint;

    fn id(&self) -> Option<ElementId> {
        None
    }

    fn source_location(&self) -> Option<&'static core::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        let t = cx.theme().clone();
        let (size, line) = (
            t.dp(t.theme.fonts.ui_size),
            t.dp(t.theme.fonts.ui_line_height),
        );
        let mut style = Style {
            flex_grow: 1.0,
            ..Style::default()
        };
        style.size.width = relative(1.).into();
        style.size.height = if self.input.read(cx).multi_line() {
            relative(1.).into()
        } else {
            (line - leading_trim(cx, &t.ui_font, size, line) * 2.0).into()
        };
        (window.request_layout(style, [], cx), ())
    }

    fn prepaint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) -> Prepaint {
        let t = cx.theme().clone();
        let (font_size, line) = (
            t.dp(t.theme.fonts.ui_size),
            t.dp(t.theme.fonts.ui_line_height),
        );
        let trim = leading_trim(cx, &t.ui_font, font_size, line);
        let input = self.input.read(cx);
        let multi = input.multi_line();
        let editor = &input.editor;
        let display: SharedString = input.display_text().into();
        let color = if editor.options().enabled {
            self.colors.text
        } else {
            self.colors.text_disabled
        };
        let base = TextRun {
            len: display.len(),
            font: font(t.ui_font.clone()),
            color,
            background_color: None,
            underline: None,
            strikethrough: None,
        };
        // The input method's marked text is underlined in the text colour.
        let runs = match editor.marked_range() {
            Some(m) => {
                let (s, e) = (input.display_offset(m.start), input.display_offset(m.end));
                vec![
                    TextRun {
                        len: s,
                        ..base.clone()
                    },
                    TextRun {
                        len: e - s,
                        underline: Some(UnderlineStyle {
                            color: Some(color),
                            thickness: t.dp(1.0),
                            wavy: false,
                        }),
                        ..base.clone()
                    },
                    TextRun {
                        len: display.len() - e,
                        ..base
                    },
                ]
                .into_iter()
                .filter(|r| r.len > 0)
                .collect()
            }
            None => vec![base],
        };
        let wrap = multi.then_some(bounds.size.width);
        let shaped = window
            .text_system()
            .shape_text(display, font_size, &runs, wrap, None)
            .unwrap_or_default();
        let layout = TextLayout::new(shaped, line);

        let placeholder = editor.placeholder_visible().then(|| {
            let text: SharedString = editor.options().placeholder.clone().into();
            let run = TextRun {
                len: text.len(),
                font: font(t.ui_font.clone()),
                color: self.colors.placeholder,
                background_color: None,
                underline: None,
                strikethrough: None,
            };
            window
                .text_system()
                .shape_text(text, font_size, &[run], wrap, None)
                .ok()
                .and_then(|lines| lines.into_iter().next())
                .unwrap_or_default()
        });

        // Keep the caret in view: a field scrolls sideways, an area down.
        let selection = editor.selection();
        let head = input.display_offset(selection.head);
        let caret_at = layout.position(head);
        let caret_w = t.dp(CARET_WIDTH);
        let mut scroll = input.scroll;
        if multi {
            let view = bounds.size.height;
            let (top, bottom) = (caret_at.y, caret_at.y + line - trim * 2.0);
            if bottom - scroll.y > view {
                scroll.y = bottom - view;
            }
            if top < scroll.y {
                scroll.y = top;
            }
            scroll.y = scroll
                .y
                .clamp(px(0.0), (layout.height() - trim * 2.0 - view).max(px(0.0)));
            scroll.x = px(0.0);
        } else {
            let view = bounds.size.width;
            if caret_at.x + caret_w - scroll.x > view {
                scroll.x = caret_at.x + caret_w - view;
            }
            if caret_at.x < scroll.x {
                scroll.x = caret_at.x;
            }
            scroll.x = scroll
                .x
                .clamp(px(0.0), (layout.width() + caret_w - view).max(px(0.0)));
            scroll.y = px(0.0);
        }
        let origin = bounds.origin + point(-scroll.x, -trim - scroll.y);

        let row_box = |p: Point<Pixels>, width: Pixels| {
            Bounds::new(
                origin + p + point(px(0.0), trim),
                size(width, line - trim * 2.0),
            )
        };
        let (selection_rects, caret) = if !self.focused {
            (Vec::new(), None)
        } else if selection.is_empty() {
            let caret = (input.caret_on && input.look.is_none() && editor.options().enabled)
                .then(|| row_box(caret_at - point(caret_w / 2.0, px(0.0)), caret_w));
            (Vec::new(), caret)
        } else {
            let range = input.display_offset(selection.range().start)
                ..input.display_offset(selection.range().end);
            let rects = layout
                .selection_rects(range)
                .into_iter()
                .map(|r| row_box(r.origin, r.size.width))
                .collect();
            (rects, None)
        };

        self.input.update(cx, |input, _| input.scroll = scroll);
        Prepaint {
            layout,
            placeholder,
            origin,
            caret,
            selection: selection_rects,
        }
    }

    fn paint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        prepaint: &mut Prepaint,
        window: &mut Window,
        cx: &mut App,
    ) {
        let (focus, enabled) = {
            let input = self.input.read(cx);
            (input.focus.clone(), input.enabled())
        };
        if enabled {
            window.handle_input(
                &focus,
                ElementInputHandler::new(bounds, self.input.clone()),
                cx,
            );
        }
        let line = prepaint.layout.line_height;
        // Let the caret show at the very ends.
        let mask = Bounds {
            origin: bounds.origin - point(px(CARET_WIDTH), px(0.0)),
            size: bounds.size + size(px(CARET_WIDTH * 2.0), px(0.0)),
        };
        window.with_content_mask(Some(ContentMask { bounds: mask }), |window| {
            for rect in &prepaint.selection {
                window.paint_quad(fill(*rect, self.colors.selection));
            }
            if let Some(placeholder) = &prepaint.placeholder {
                placeholder
                    .paint(prepaint.origin, line, TextAlign::Left, None, window, cx)
                    .ok();
            }
            for l in &prepaint.layout.lines {
                l.shaped
                    .paint(
                        prepaint.origin + point(px(0.0), l.y),
                        line,
                        TextAlign::Left,
                        None,
                        window,
                        cx,
                    )
                    .ok();
            }
            if let Some(caret) = prepaint.caret {
                window.paint_quad(fill(caret, self.colors.text));
            }
        });
        let layout = std::mem::replace(&mut prepaint.layout, TextLayout::new([], line));
        let origin = prepaint.origin;
        self.input.update(cx, |input, _| {
            input.layout = Some(layout);
            input.origin = origin;
        });
    }
}
