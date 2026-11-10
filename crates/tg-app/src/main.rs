//! TableGrip, the desktop app. The window, its screens and dialogs are drawn with `tg-ui`; state lives in
//! `tg-app-logic` and the core crates. The architecture is in the S6 log of docs/plans/rust-migration.md.
//!
//! `tg-app [--home DIR] [--fonts DIR]`: `--home` (or `TABLEGRIP_HOME`) uses another data folder than `~/.tablegrip`;
//! `--fonts` (or `TG_UI_FONT_DIR`) loads Inter and JetBrains Mono, which the kit doesn't bundle yet.

mod dialogs;
mod explorer_view;
mod io;
mod script;
mod shell;
mod tabs;
mod workspace_view;

use std::path::PathBuf;
use std::sync::Arc;

use gpui::{
    App, AppContext, Application, Bounds, KeyBinding, WindowBounds, actions, point, px, size,
};
use tg_app_logic::{AppModel, Registry, keymap, session};
use tg_core::TableGripPaths;
use tg_core::connection::ConnectionStore;
use tg_core::session::SessionStore;
use tg_core::settings::SettingsStore;
use tg_driver::Secrets;
use tg_ui::window_chrome::{Os, window_options};
use tg_ui_logic::keymap::Platform;

use crate::shell::{Shell, TITLE_BAR_HEIGHT};

actions!(tablegrip, [Quit]);

struct Args {
    home: Option<PathBuf>,
    fonts: Option<PathBuf>,
}

fn args() -> Args {
    let mut args = Args {
        home: None,
        fonts: std::env::var_os("TG_UI_FONT_DIR").map(PathBuf::from),
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        let mut value = || it.next().unwrap_or_else(|| panic!("{arg} needs a value"));
        match arg.as_str() {
            "--home" => args.home = Some(value().into()),
            "--fonts" => args.fonts = Some(value().into()),
            other => panic!("unknown argument {other}; see the doc comment in main.rs"),
        }
    }
    args
}

fn main() {
    let args = args();
    let paths = match &args.home {
        Some(home) => TableGripPaths::new(home),
        None => TableGripPaths::from_env(),
    };
    // Database work runs here; the UI awaits it (see io.rs).
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .thread_name("tablegrip-io")
        .enable_all()
        .build()
        .expect("starting the I/O runtime");

    let settings_store = Arc::new(SettingsStore::new(paths.settings()));
    let settings = settings_store.load();
    if let Some(e) = settings_store.load_error() {
        eprintln!("settings.json: {e}; running on defaults");
    }
    let store = ConnectionStore::with_keyring(paths.connections());
    let (profiles, load_error) = match store.load() {
        Ok(p) => (p, None),
        Err(e) => (Vec::new(), Some(format!("Failed to load connections: {e}"))),
    };
    let secrets_store = Arc::new(ConnectionStore::with_keyring(paths.connections()));
    let registry = Registry::new(
        runtime.handle().clone(),
        paths.clone(),
        Arc::new(move |config| Secrets {
            password: secrets_store.password(&config.id),
            ssh: config
                .ssh
                .as_ref()
                .and_then(|_| secrets_store.ssh_secret(&config.id)),
        }),
    );
    let keymap = keymap::keymap(&settings, Platform::current());
    let mut model = AppModel::new(settings, profiles);
    if let Some(e) = load_error {
        model.status = e;
    }
    // The last session's tabs and layout, as Settings → Startup & Layout ask.
    let saved = SessionStore::new(paths.session()).load();
    let restored = session::restore(&mut model, &saved);

    Application::new()
        .with_assets(tg_ui::Assets)
        .run(move |cx: &mut App| {
            cx.set_global(io::Io(runtime.handle().clone()));
            // The runtime lives as long as the app.
            std::mem::forget(runtime);
            if let Some(dir) = &args.fonts {
                tg_ui::fonts::load_font_dir(cx, dir);
            }
            // A first theme so the window can be built; the shell applies the settings' own once it knows the window's
            // light or dark appearance.
            tg_ui::set_theme(
                cx,
                tg_app_logic::appearance::theme(
                    &Default::default(),
                    &tg_theme::Registry::new(),
                    true,
                ),
            );
            tg_ui::keymap::bind_keymap(cx, &keymap);
            cx.bind_keys([KeyBinding::new(
                if cfg!(target_os = "macos") {
                    "cmd-q"
                } else {
                    "ctrl-q"
                },
                Quit,
                None,
            )]);
            cx.on_action(|_: &Quit, cx| cx.quit());

            let os = Os::current();
            let mut options = window_options(os, "TableGrip", TITLE_BAR_HEIGHT);
            let default = Bounds::centered(None, size(px(1360.0), px(860.0)), cx);
            options.window_bounds = Some(match &restored.window {
                Some(w) => {
                    let place = Bounds {
                        origin: point(px(w.x), px(w.y)),
                        size: size(px(w.width), px(w.height)),
                    };
                    let place = if on_screen(&place, cx) {
                        place
                    } else {
                        Bounds::centered(None, place.size, cx)
                    };
                    if w.maximized {
                        WindowBounds::Maximized(place)
                    } else {
                        WindowBounds::Windowed(place)
                    }
                }
                None => WindowBounds::Windowed(default),
            });
            options.window_min_size = Some(size(px(640.0), px(420.0)));
            let window = cx
                .open_window(options, |window, cx| {
                    cx.new(|cx| {
                        let mut shell = Shell::new(
                            model,
                            registry,
                            store,
                            paths,
                            settings_store,
                            keymap,
                            window,
                            cx,
                        );
                        shell.apply_theme(window, cx);
                        shell.mark_saved(saved.window.clone());
                        window.focus(&shell.focus);
                        shell
                    })
                })
                .expect("opening the main window");
            window
                .update(cx, |shell, window, cx| {
                    let entity = cx.entity();
                    window.on_window_should_close(cx, move |_, cx| {
                        entity.update(cx, |s, _| s.save_session_now());
                        cx.quit();
                        true
                    });
                    // Connections the session's tabs need open in the explorer, as the Kotlin app reveals them.
                    if let Some(id) = shell.model.current_connection_id.clone() {
                        shell.pick_connection(&id, cx);
                    }
                })
                .ok();
            if let Ok(script) = std::env::var("TG_APP_SCRIPT")
                && let Ok(shell) = window.update(cx, |_, _, cx| cx.entity())
            {
                script::start(script, shell, window.into(), cx);
            }
            cx.activate(true);
        });
}

/// Whether a saved window place is still on a screen (a monitor may have been unplugged).
fn on_screen(bounds: &Bounds<gpui::Pixels>, cx: &App) -> bool {
    let probe = point(bounds.origin.x + px(40.0), bounds.origin.y + px(20.0));
    cx.displays().iter().any(|d| d.bounds().contains(&probe))
}
