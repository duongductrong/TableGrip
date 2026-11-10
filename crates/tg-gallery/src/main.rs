//! `tg-gallery [--theme NAME] [--specimen NAME] [--fonts DIR] [--capture DIR]`
//!
//! Without `--capture`, opens a window with the specimens. With it, opens each specimen in its own window, writes
//! `DIR/<name>.png` (sRGB, cropped to the specimen) and quits; `scripts/imgdiff.sh` drives this.
//! `--fonts` (or `TG_UI_FONT_DIR`) loads Inter and JetBrains Mono, which the kit doesn't bundle yet.

use std::path::PathBuf;
use std::time::Duration;

use gpui::{
    App, Application, Bounds, Context, Render, SharedString, Window, WindowBounds, WindowKind,
    WindowOptions, div, point, prelude::*, px, size,
};
use tg_gallery::{Specimen, capture, theme_named};
use tg_ui::{ThemeExt, hsla, set_theme};

struct Args {
    theme: String,
    specimen: Option<String>,
    fonts: Option<PathBuf>,
    capture: Option<PathBuf>,
}

fn args() -> Args {
    let mut args = Args {
        theme: tg_theme::DEFAULT_DARK_THEME.to_owned(),
        specimen: None,
        fonts: std::env::var_os("TG_UI_FONT_DIR").map(PathBuf::from),
        capture: None,
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        let mut value = || it.next().unwrap_or_else(|| panic!("{arg} needs a value"));
        match arg.as_str() {
            "--theme" => args.theme = value(),
            "--specimen" => args.specimen = Some(value()),
            "--fonts" => args.fonts = Some(value().into()),
            "--capture" => args.capture = Some(value().into()),
            other => panic!("unknown argument {other}; see the doc comment in main.rs"),
        }
    }
    args
}

/// One specimen on its canvas, inset by the capture margin.
struct SpecimenView {
    specimen: Specimen,
}

impl Render for SpecimenView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        div()
            .size_full()
            .bg(hsla(t.theme.colors.islands.frame))
            .p(t.dp(capture::MARGIN))
            .child((self.specimen.render)(window, cx))
    }
}

/// Every specimen with its name, for looking at.
struct GalleryView {
    specimens: Vec<Specimen>,
}

impl Render for GalleryView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        let mut column = div()
            .id("gallery")
            .size_full()
            .overflow_y_scroll()
            .bg(hsla(t.theme.colors.islands.frame))
            .p(t.dp(24.0))
            .pt(t.dp(48.0))
            .flex()
            .flex_col()
            .gap(t.dp(12.0));
        for s in &self.specimens {
            column = column
                .child(tg_ui::text(SharedString::from(s.name)).color(tg_ui::TextColor::Info))
                .child((s.render)(window, cx));
        }
        column
    }
}

fn main() {
    let args = args();
    let theme =
        theme_named(&args.theme).unwrap_or_else(|| panic!("no built-in theme {:?}", args.theme));
    let specimens: Vec<Specimen> = tg_gallery::all()
        .into_iter()
        .filter(|s| args.specimen.as_deref().is_none_or(|n| n == s.name))
        .collect();
    assert!(!specimens.is_empty(), "no specimen {:?}", args.specimen);

    Application::new()
        .with_assets(tg_ui::Assets)
        .run(move |cx: &mut App| {
            if let Some(dir) = &args.fonts {
                tg_ui::fonts::load_font_dir(cx, dir);
            }
            set_theme(cx, theme);
            match args.capture {
                Some(dir) => capture_all(cx, specimens, dir),
                None => {
                    let bounds = Bounds::centered(None, size(px(720.), px(900.)), cx);
                    cx.open_window(
                        WindowOptions {
                            window_bounds: Some(WindowBounds::Windowed(bounds)),
                            titlebar: Some(gpui::TitlebarOptions {
                                title: Some(format!("TableGrip gallery — {}", args.theme).into()),
                                appears_transparent: true,
                                traffic_light_position: Some(point(px(12.), px(12.))),
                            }),
                            ..Default::default()
                        },
                        |_, cx| cx.new(|_| GalleryView { specimens }),
                    )
                    .unwrap();
                    cx.activate(true);
                }
            }
        });
}

fn capture_all(cx: &mut App, specimens: Vec<Specimen>, dir: PathBuf) {
    std::fs::create_dir_all(&dir).unwrap();
    cx.spawn(async move |cx| {
        let mut failed = false;
        for specimen in specimens {
            let name = specimen.name;
            let (w, h) = (specimen.width, specimen.height);
            let margin = 2.0 * capture::MARGIN;
            let handle = cx
                .open_window(
                    WindowOptions {
                        window_bounds: Some(WindowBounds::Windowed(Bounds {
                            origin: point(px(80.), px(80.)),
                            size: size(px(w + margin), px(h + margin)),
                        })),
                        titlebar: None,
                        kind: WindowKind::PopUp,
                        is_movable: false,
                        is_resizable: false,
                        focus: false,
                        ..Default::default()
                    },
                    |_, cx| cx.new(|_| SpecimenView { specimen }),
                )
                .unwrap();
            // A few frames, so images (icons) have loaded and the window server has the content.
            cx.background_executor()
                .timer(Duration::from_millis(700))
                .await;
            let shot = handle
                .update(cx, |_, window, _| {
                    (capture::window_number(window), window.scale_factor())
                })
                .unwrap();
            let out = dir.join(format!("{name}.png"));
            let result = match shot {
                (Some(number), scale) => capture::capture(number, &out, w, h, scale),
                (None, _) => Err("capturing needs macOS".to_owned()),
            };
            match result {
                Ok(()) => println!("captured {}", out.display()),
                Err(e) => {
                    eprintln!("{name}: {e}");
                    failed = true;
                }
            }
            handle
                .update(cx, |_, window, _| window.remove_window())
                .ok();
        }
        cx.update(|cx| cx.quit()).ok();
        if failed {
            std::process::exit(1);
        }
    })
    .detach();
}
