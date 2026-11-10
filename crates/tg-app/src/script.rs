//! A scripted run, for screenshots and for checking the app end to end without clicking (`TG_APP_SCRIPT`, never set
//! in normal use). Steps are separated by `;`:
//!
//! * `wait <ms>`
//! * `pick <connection id>`: make it current and reveal it in the explorer (connecting it)
//! * `expand <connection id> <label>/<label>/…`: open explorer nodes by their labels, below the connection
//! * `table <connection id> <schema or -> <name>`, `console <connection id> <text>`: open tabs
//! * `menu <connection id> <label>/…`: open the context menu of a node
//! * `zoom <percent>`, `connect-dialog <connection id>`: open the editor of a profile
//! * `shot <file.png>`: capture the window (macOS)
//! * `quit`: quit as ⌘Q does, saving the session

use std::time::Duration;

use gpui::{AnyWindowHandle, App, AsyncApp, Entity, Point, px};
use tg_app_logic::explorer::{Node, connection_key};
use tg_sql::model::{Namespace, TableRef};

use crate::shell::Shell;

pub fn start(script: String, shell: Entity<Shell>, window: AnyWindowHandle, cx: &mut App) {
    cx.spawn(async move |cx: &mut AsyncApp| {
        for step in script.split(';').map(str::trim).filter(|s| !s.is_empty()) {
            let (verb, rest) = step.split_once(' ').unwrap_or((step, ""));
            match verb {
                "wait" => {
                    let ms = rest.trim().parse().unwrap_or(500);
                    cx.background_executor()
                        .timer(Duration::from_millis(ms))
                        .await;
                    continue;
                }
                "shot" => {
                    let out = rest.trim().to_owned();
                    // Let the last change draw.
                    cx.background_executor()
                        .timer(Duration::from_millis(400))
                        .await;
                    let number = window
                        .update(cx, |_, window, _| window_number(window))
                        .ok()
                        .flatten();
                    match number {
                        Some(n) => match capture(n, &out) {
                            Ok(()) => println!("captured {out}"),
                            Err(e) => eprintln!("shot: {e}"),
                        },
                        None => eprintln!("shot: capturing needs macOS"),
                    }
                    continue;
                }
                "quit" => {
                    cx.update(|cx| cx.quit()).ok();
                    return;
                }
                _ => {}
            }
            let step = step.to_owned();
            window
                .update(cx, |_, window, cx| {
                    shell.update(cx, |s, cx| run(s, &step, window, cx));
                })
                .ok();
            cx.background_executor()
                .timer(Duration::from_millis(150))
                .await;
        }
    })
    .detach();
}

fn run(s: &mut Shell, step: &str, window: &mut gpui::Window, cx: &mut gpui::Context<Shell>) {
    let mut words = step.splitn(3, ' ');
    let verb = words.next().unwrap_or("");
    let id = words.next().unwrap_or("").to_owned();
    let rest = words.next().unwrap_or("").to_owned();
    match verb {
        "pick" => s.pick_connection(&id, cx),
        "expand" | "menu" => {
            let Some((node, path)) = find(s, &id, &rest) else {
                eprintln!("script: no node {rest} under {id}");
                return;
            };
            if verb == "menu" {
                s.open_menu(
                    Some(node),
                    Point {
                        x: px(180.0),
                        y: px(260.0),
                    },
                    cx,
                );
                return;
            }
            let profiles = s.model.connections.profiles.clone();
            // Every node on the way opens, as clicking down to it would.
            let mut jobs = Vec::new();
            for key in &path {
                jobs.extend(
                    s.model
                        .explorer
                        .opened(key, &profiles)
                        .map(tg_app_logic::Effect::Load),
                );
            }
            s.model.explorer.reveal_keys(path);
            s.run(jobs, cx);
        }
        "table" => {
            let Some(config) = s.model.connections.by_id(&id).cloned() else {
                return;
            };
            let (schema, name) = rest.split_once(' ').unwrap_or(("-", rest.as_str()));
            let schema = (schema != "-").then_some(schema);
            let key = s.model.workspace.open_table(
                &config,
                &TableRef::new(Namespace::new(None, schema), name),
                None,
            );
            s.model.select_tab(key);
            cx.notify();
        }
        "console" => {
            let Some(config) = s.model.connections.by_id(&id).cloned() else {
                return;
            };
            let key = s.model.workspace.open_console(&config, &rest, 0, None);
            s.model.select_tab(key);
            cx.notify();
        }
        "zoom" => s.run(
            vec![tg_app_logic::Effect::Zoom(id.parse().unwrap_or(100))],
            cx,
        ),
        "connect-dialog" => {
            s.model.open_edit_connection(&id, &crate::shell::os_user());
            s.after_dialog_change(window, cx);
        }
        other => eprintln!("script: unknown step {other}"),
    }
}

/// The node at `path` (labels joined by `/`) below connection `id`, in the tree as last built, with the keys from the
/// connection down to it.
fn find(s: &mut Shell, id: &str, path: &str) -> Option<(Node, Vec<String>)> {
    let profiles = s.model.connections.profiles.clone();
    let cache = s.tree_cache();
    let built = cache.built.clone();
    let root = connection_key(id);
    fn search<'a>(
        items: &'a [tg_ui_logic::Item<String>],
        key: &str,
    ) -> Option<&'a tg_ui_logic::Item<String>> {
        items.iter().find_map(|i| {
            if i.key == key {
                Some(i)
            } else {
                search(&i.children, key)
            }
        })
    }
    let mut item = search(&built.items, &root)?;
    let mut keys = vec![item.key.clone()];
    for label in path.split('/').filter(|l| !l.is_empty()) {
        item = item
            .children
            .iter()
            .find(|c| built.nodes[&c.key].label(&profiles) == label)?;
        keys.push(item.key.clone());
    }
    built.nodes.get(&item.key).cloned().map(|n| (n, keys))
}

#[cfg(target_os = "macos")]
// objc 0.2's `msg_send!` checks a `cargo-clippy` feature this crate doesn't declare.
#[allow(unexpected_cfgs)]
fn window_number(window: &gpui::Window) -> Option<i64> {
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
fn window_number(_: &gpui::Window) -> Option<i64> {
    None
}

fn capture(number: i64, out: &str) -> Result<(), String> {
    let output = std::process::Command::new("screencapture")
        .args(["-x", "-o", &format!("-l{number}"), "-t", "png", out])
        .output()
        .map_err(|e| e.to_string())?;
    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).into_owned())
    }
}
