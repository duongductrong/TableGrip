//! The dialogs in front of the main window (`MainScreen.AppDialogs`): the connection editor, deleting a connection,
//! naming a group, importing connections, and the keyboard shortcuts. The Kotlin app opens each in a window of its
//! own; here they are drawn over the main window on a scrim, in the same dialog frame (`tg_ui::app_dialog`).

use gpui::{
    AnyElement, App, AppContext, Context, Entity, Focusable, IntoElement, ParentElement,
    PathPromptOptions, Render, Styled, WeakEntity, Window, div, prelude::*,
};
use tg_app_logic::app::{ConnectionDialog, Message};
use tg_app_logic::connections::{ConnectionForm, FormTab, connectable_types, dropped};
use tg_app_logic::imports::{self, ImportState};
use tg_app_logic::{Dialog, registry};
use tg_core::connection::{Environment, SshAuth};
use tg_driver::Secrets;
use tg_sql::model::DatabaseType;
use tg_ui::window_chrome::Os;
use tg_ui::{
    TextColor, TextInput, ThemeExt, app_dialog, button, checkbox, hsla, link, list_combo_box,
    tab_strip, text,
};
use tg_ui_logic::keymap::Platform;

use crate::io;
use crate::shell::Shell;

/// Views the open dialog keeps between frames.
#[derive(Default)]
pub struct DialogViews {
    pub connection: Option<Entity<ConnectionDialogView>>,
    pub group: Option<Entity<TextInput>>,
    import_scanning: bool,
    /// "Collapse All" was chosen: the tree closes everything on its next draw.
    pub collapse_all: bool,
}

/// Builds what the dialog now open needs, and drops what a closed one had.
pub fn prepare(shell: &mut Shell, window: &mut Window, cx: &mut Context<Shell>) {
    match &shell.model.dialog {
        Some(Dialog::Connection(d)) => {
            if shell.dialogs.connection.is_none() {
                let weak = cx.entity().downgrade();
                let (form, editing) = (d.form.clone(), d.editing);
                let view = cx.new(|cx| ConnectionDialogView::new(weak, &form, cx));
                if editing {
                    load_secrets(shell, &form.id, view.clone(), cx);
                }
                let focus = view.read(cx).inputs.name.read(cx).focus_handle(cx);
                window.focus(&focus);
                shell.dialogs.connection = Some(view);
            }
        }
        _ => shell.dialogs.connection = None,
    }
    match &shell.model.dialog {
        Some(Dialog::Group(request)) => {
            if shell.dialogs.group.is_none() {
                let initial = match &request.renaming {
                    Some(name) => name.clone(),
                    None => request
                        .ids
                        .first()
                        .and_then(|id| shell.model.connections.by_id(id))
                        .map(|c| c.group.clone())
                        .unwrap_or_default(),
                };
                let input = cx.new(|cx| {
                    TextInput::field(&initial, cx).placeholder("e.g. a project or client")
                });
                window.focus(&input.read(cx).focus_handle(cx));
                shell.dialogs.group = Some(input);
            }
        }
        _ => shell.dialogs.group = None,
    }
    match &shell.model.dialog {
        Some(Dialog::Import(state)) if state.scanning && !shell.dialogs.import_scanning => {
            shell.dialogs.import_scanning = true;
            let home = tg_core::paths::user_home();
            scan_imports(
                shell,
                move |home_env| tg_data::imports::discover(&home, home_env),
                cx,
            );
        }
        Some(Dialog::Import(_)) => {}
        _ => shell.dialogs.import_scanning = false,
    }
}

fn env_map() -> std::collections::HashMap<String, String> {
    std::env::vars().collect()
}

fn scan_imports(
    _shell: &mut Shell,
    sources: impl FnOnce(
        &std::collections::HashMap<String, String>,
    ) -> Vec<tg_data::imports::ImportSource>
    + Send
    + 'static,
    cx: &mut Context<Shell>,
) {
    let handle = io::handle(cx);
    cx.spawn(async move |this, cx| {
        let read = io::blocking(&handle, move || {
            let env = env_map();
            let home = tg_core::paths::user_home();
            imports::scan(sources(&env), &home, &env)
        })
        .await;
        this.update(cx, |s, cx| {
            let profiles = s.model.connections.profiles.clone();
            if let Some(Dialog::Import(state)) = &mut s.model.dialog {
                state.append(read, &profiles);
            }
            s.dialogs.import_scanning = false;
            cx.notify();
        })
        .ok();
    })
    .detach();
}

/// Reads the stored password and SSH secret into the editor's fields, off the UI thread (the keychain may prompt).
fn load_secrets(
    shell: &Shell,
    id: &str,
    view: Entity<ConnectionDialogView>,
    cx: &mut Context<Shell>,
) {
    let (store, id, handle) = (shell.store.clone(), id.to_owned(), io::handle(cx));
    cx.spawn(async move |_, cx| {
        let (password, ssh) = io::blocking(&handle, move || {
            let store = store.lock().unwrap();
            (store.password(&id), store.ssh_secret(&id))
        })
        .await;
        view.update(cx, |v, cx| {
            if let Some(p) = password {
                set_text(&v.inputs.password, &p, cx);
            }
            if let Some(s) = ssh {
                set_text(&v.inputs.ssh_secret, &s, cx);
            }
        })
        .ok();
    })
    .detach();
}

fn set_text(input: &Entity<TextInput>, text: &str, cx: &mut App) {
    input.update(cx, |i, cx| i.update_editor(cx, |e| e.set_text(text)));
}

/// The dialog in front, on a scrim over the whole window.
pub fn render(
    shell: &mut Shell,
    window: &mut Window,
    cx: &mut Context<Shell>,
) -> Option<AnyElement> {
    let t = cx.theme().clone();
    let entity = cx.entity();
    let close = {
        let e = entity.clone();
        move |window: &mut Window, cx: &mut App| {
            e.update(cx, |s, cx| {
                s.model.dialog = None;
                s.after_dialog_change(window, cx);
            })
        }
    };
    let (title, width, height, content): (String, f32, f32, AnyElement) = match &shell.model.dialog
    {
        None => return None,
        Some(Dialog::Connection(d)) => (
            if d.editing {
                "Edit Connection"
            } else {
                "New Connection"
            }
            .to_owned(),
            640.0,
            600.0,
            shell.dialogs.connection.clone()?.into_any_element(),
        ),
        Some(Dialog::DeleteConnection(id)) => {
            let name = shell
                .model
                .connections
                .by_id(id)
                .map(|c| c.name.clone())
                .unwrap_or_default();
            let (id, e) = (id.clone(), entity.clone());
            let cancel = close.clone();
            let body = div()
                .size_full()
                .flex()
                .flex_col()
                .p(t.dp(16.0))
                .gap(t.dp(16.0))
                .child(text(format!(
                    "Delete “{name}”? Its saved password will be removed from the keychain too."
                )))
                .child(buttons(vec![
                    button("delete.cancel", "Cancel")
                        .outlined()
                        .on_click(move |w, cx| cancel(w, cx))
                        .into_any_element(),
                    button("delete.ok", "Delete")
                        .on_click(move |window, cx| {
                            e.update(cx, |s, cx| {
                                let effects = s.model.delete_connection(&id);
                                s.after_dialog_change(window, cx);
                                s.run(effects, cx);
                            })
                        })
                        .into_any_element(),
                ]));
            (
                "Delete Connection".to_owned(),
                420.0,
                170.0,
                body.into_any_element(),
            )
        }
        Some(Dialog::Group(request)) => {
            let input = shell.dialogs.group.clone()?;
            let request = request.clone();
            let renaming = request.renaming.is_some();
            let groups = shell.model.connections.groups();
            let (e, field, cancel) = (entity.clone(), input.clone(), close.clone());
            let body = div()
                .size_full()
                .flex()
                .flex_col()
                .p(t.dp(16.0))
                .gap(t.dp(12.0))
                .child(row(&t, "Group", input.into_any_element()))
                .when(!groups.is_empty() && !renaming, |d| {
                    d.child(text(format!("Existing: {}", groups.join(", "))).color(TextColor::Info))
                })
                .child(buttons(vec![
                    button("group.cancel", "Cancel")
                        .outlined()
                        .on_click(move |w, cx| cancel(w, cx))
                        .into_any_element(),
                    button("group.ok", if renaming { "Rename" } else { "Move" })
                        .on_click(move |window, cx| {
                            let name = field.read(cx).text().to_owned();
                            e.update(cx, |s, cx| {
                                let effects = s.model.submit_group(&request, &name);
                                s.after_dialog_change(window, cx);
                                s.run(effects, cx);
                            })
                        })
                        .into_any_element(),
                ]));
            (
                if renaming {
                    "Rename Group"
                } else {
                    "Move to Group"
                }
                .to_owned(),
                440.0,
                200.0,
                body.into_any_element(),
            )
        }
        Some(Dialog::Import(state)) => {
            let body = import_body(state, &t, entity.clone(), close.clone());
            ("Import Connections".to_owned(), 780.0, 580.0, body)
        }
        Some(Dialog::Shortcuts) => (
            "Keyboard Shortcuts".to_owned(),
            520.0,
            600.0,
            shortcuts_body(shell, &t),
        ),
    };
    let _ = window;
    let regions = shell.regions.clone();
    Some(
        div()
            .id("dialog.scrim")
            .absolute()
            .inset_0()
            .bg(hsla(t.theme.colors.islands.scrim))
            .flex()
            .items_center()
            .justify_center()
            .occlude()
            .child(
                div()
                    .w(t.dp(width))
                    .h(t.dp(height))
                    .rounded(t.dp(14.0))
                    .overflow_hidden()
                    .shadow_lg()
                    .child(
                        app_dialog(Os::current(), title, &regions, close)
                            .bare()
                            .child(content),
                    ),
            )
            .into_any_element(),
    )
}

fn buttons(children: Vec<AnyElement>) -> impl IntoElement {
    div()
        .flex()
        .justify_end()
        .gap(gpui::px(8.0))
        .children(children)
}

fn row(t: &tg_ui::theme::ActiveTheme, label: &str, content: AnyElement) -> gpui::Div {
    div()
        .flex()
        .items_center()
        .gap(t.dp(8.0))
        .child(
            div()
                .flex_none()
                .w(t.dp(96.0))
                .child(text(label.to_owned())),
        )
        .child(div().flex_1().min_w_0().child(content))
}

fn import_body(
    state: &ImportState,
    t: &tg_ui::theme::ActiveTheme,
    entity: Entity<Shell>,
    close: impl Fn(&mut Window, &mut App) + Clone + 'static,
) -> AnyElement {
    let mut list = div().flex().flex_col().gap(t.dp(2.0)).p(t.dp(8.0));
    if state.groups.is_empty() {
        list = list.child(
            div().p(t.dp(24.0)).child(
                text(if state.scanning {
                    "Looking for connections…"
                } else {
                    "No other database tools found on this computer. Add a file — a Connections.plist, DBeaver's data-sources.json, a .idea/dataSources.xml, .pgpass."
                })
                .color(TextColor::Info),
            ),
        );
    }
    for (g, group) in state.groups.iter().enumerate() {
        let all = group.rows.iter().all(|r| r.selected);
        let e = entity.clone();
        list = list.child(
            checkbox(
                format!("import.group.{g}"),
                format!(
                    "{} — {}",
                    group.source.format.label(),
                    group.source.path.display()
                ),
            )
            .checked(all)
            .on_toggle(move |_, _, cx| {
                e.update(cx, |s, cx| {
                    if let Some(Dialog::Import(st)) = &mut s.model.dialog {
                        st.toggle_group(g);
                    }
                    cx.notify();
                })
            }),
        );
        for (r, item) in group.rows.iter().enumerate() {
            let e = entity.clone();
            let c = &item.candidate.config;
            let mut note = vec![c.db_type.display_name.to_owned()];
            if !c.host.is_empty() {
                note.push(tg_core::connection::string::format_host(&c.host, c.port));
            }
            if let Some(name) = &item.already_saved {
                note.push(format!("already saved as {name}"));
            } else if item.duplicate {
                note.push("also found above".into());
            } else if item.needs_password() {
                note.push("no password".into());
            }
            list = list.child(
                div()
                    .flex()
                    .items_center()
                    .gap(t.dp(8.0))
                    .pl(t.dp(24.0))
                    .child(
                        checkbox(format!("import.row.{g}.{r}"), c.name.clone())
                            .checked(item.selected)
                            .on_toggle(move |_, _, cx| {
                                e.update(cx, |s, cx| {
                                    if let Some(Dialog::Import(st)) = &mut s.model.dialog {
                                        st.toggle(g, r);
                                    }
                                    cx.notify();
                                })
                            }),
                    )
                    .child(text(note.join(" · ")).color(TextColor::Info)),
            );
        }
        for skipped in &group.skipped {
            list = list.child(
                div()
                    .pl(t.dp(24.0))
                    .child(text(skipped.clone()).color(TextColor::Warning)),
            );
        }
    }
    let count = state.selected().len();
    let (e_keep, e_add, e_import) = (entity.clone(), entity.clone(), entity);
    let keep = state.keep_groups;
    let busy = state.scanning || state.saving;
    div()
        .size_full()
        .flex()
        .flex_col()
        .child(
            div()
                .flex()
                .items_center()
                .gap(t.dp(12.0))
                .px(t.dp(16.0))
                .py(t.dp(12.0))
                .child(
                    div().flex_1().child(
                        text("From DBeaver, Connections.plist, IDE data sources, .pgpass, pg_service.conf and .env files. Nothing changes in those tools.")
                            .color(TextColor::Info),
                    ),
                )
                .child(button("import.add", "Add File…").outlined().enabled(!busy).on_click(move |_, cx| {
                    let picked = cx.prompt_for_paths(PathPromptOptions {
                        files: true,
                        directories: true,
                        multiple: false,
                        prompt: Some("Add".into()),
                    });
                    let e = e_add.clone();
                    cx.spawn(async move |cx| {
                        let Ok(Ok(Some(paths))) = picked.await else {
                            return;
                        };
                        let Some(path) = paths.into_iter().next() else {
                            return;
                        };
                        e.update(cx, |s, cx| {
                            let source = match &mut s.model.dialog {
                                Some(Dialog::Import(st)) => st.source_for(&path),
                                _ => None,
                            };
                            if let Some(source) = source {
                                s.dialogs.import_scanning = true;
                                scan_imports(s, move |_| vec![source], cx);
                            }
                            cx.notify();
                        })
                        .ok();
                    })
                    .detach();
                })),
        )
        .child(
            div()
                .id("import.list")
                .flex_1()
                .min_h_0()
                .overflow_y_scroll()
                .child(list),
        )
        .child(
            div()
                .flex()
                .flex_col()
                .gap(t.dp(8.0))
                .px(t.dp(16.0))
                .py(t.dp(12.0))
                .children(state.message.clone().map(|m| text(m).color(TextColor::Warning)))
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(t.dp(8.0))
                        .child(
                            div().flex_1().child(
                                checkbox("import.keep", "Keep folders as groups").checked(keep).on_toggle(move |v, _, cx| {
                                    e_keep.update(cx, |s, cx| {
                                        if let Some(Dialog::Import(st)) = &mut s.model.dialog {
                                            st.keep_groups = v;
                                        }
                                        cx.notify();
                                    })
                                }),
                            ),
                        )
                        .child(button("import.cancel", "Cancel").outlined().on_click(move |w, cx| close(w, cx)))
                        .child(
                            button(
                                "import.ok",
                                if count == 1 { "Import 1 Connection".to_owned() } else { format!("Import {count} Connections") },
                            )
                            .enabled(count > 0 && !busy)
                            .on_click(move |window, cx| e_import.update(cx, |s, cx| save_imported(s, window, cx))),
                        ),
                ),
        )
        .into_any_element()
}

fn save_imported(shell: &mut Shell, _window: &mut Window, cx: &mut Context<Shell>) {
    let Some(Dialog::Import(state)) = &mut shell.model.dialog else {
        return;
    };
    let items = state.chosen();
    if items.is_empty() {
        return;
    }
    state.saving = true;
    let (store, handle) = (shell.store.clone(), io::handle(cx));
    cx.spawn(async move |this, cx| {
        let summary = ImportState::summary(&items);
        let saved = io::blocking(&handle, move || {
            let store = store.lock().unwrap();
            let mut last = store.load();
            for (config, password, ssh) in &items {
                last = store.save(config, password.as_deref(), ssh.as_deref());
            }
            last
        })
        .await;
        this.update(cx, |s, cx| {
            match saved {
                Ok(list) => {
                    s.model.connections.profiles = list;
                    s.profiles_rev += 1;
                    s.model.status = summary;
                    s.model.dialog = None;
                }
                Err(e) => {
                    if let Some(Dialog::Import(st)) = &mut s.model.dialog {
                        st.saving = false;
                        st.message = Some(format!("Import failed: {e}"));
                    }
                }
            }
            cx.notify();
        })
        .ok();
    })
    .detach();
}

fn shortcuts_body(shell: &Shell, t: &tg_ui::theme::ActiveTheme) -> AnyElement {
    let platform = Platform::current();
    let mut list = div().flex().flex_col().p(t.dp(12.0)).gap(t.dp(2.0));
    for (id, _) in tg_ui_logic::keymap::defaults(platform) {
        let label = shell
            .keymap
            .binding(id)
            .map(|s| s.label(platform))
            .unwrap_or_default();
        list = list.child(
            div()
                .flex()
                .h(t.dp(24.0))
                .items_center()
                .child(div().flex_1().child(text(split_words(id))))
                .child(text(label).color(TextColor::Info)),
        );
    }
    div()
        .id("shortcuts")
        .size_full()
        .overflow_y_scroll()
        .child(list)
        .into_any_element()
}

/// `NewConsole` → `New console`.
fn split_words(id: &str) -> String {
    let mut out = String::new();
    for (i, c) in id.chars().enumerate() {
        if c.is_uppercase() && i > 0 {
            out.push(' ');
            out.extend(c.to_lowercase());
        } else {
            out.push(c);
        }
    }
    out
}

/// The connection editor's text fields.
pub struct Inputs {
    connection_string: Entity<TextInput>,
    name: Entity<TextInput>,
    host: Entity<TextInput>,
    port: Entity<TextInput>,
    database: Entity<TextInput>,
    user: Entity<TextInput>,
    password: Entity<TextInput>,
    url: Entity<TextInput>,
    properties: Entity<TextInput>,
    group: Entity<TextInput>,
    ssh_host: Entity<TextInput>,
    ssh_port: Entity<TextInput>,
    ssh_user: Entity<TextInput>,
    ssh_key: Entity<TextInput>,
    ssh_secret: Entity<TextInput>,
    delimiter: Entity<TextInput>,
    charset: Entity<TextInput>,
}

impl Inputs {
    fn new(form: &ConnectionForm, cx: &mut App) -> Self {
        let field = |text: &str, placeholder: &str, cx: &mut App| {
            let placeholder = placeholder.to_owned();
            cx.new(|cx| TextInput::field(text, cx).placeholder(placeholder))
        };
        let secret = |text: &str, placeholder: &str, cx: &mut App| {
            let placeholder = placeholder.to_owned();
            cx.new(|cx| {
                TextInput::field(text, cx)
                    .placeholder(placeholder)
                    .password()
            })
        };
        Self {
            connection_string: field(
                "",
                "postgres://user:pass@host/db, mongodb+srv://…, jdbc:…, path.db, data.csv",
                cx,
            ),
            name: field(&form.name, "Optional", cx),
            host: field(&form.host, "host, or host1:port1,host2:port2", cx),
            port: field(&form.port, "", cx),
            database: field(&form.database, "", cx),
            user: field(&form.user, "", cx),
            password: secret(&form.password, "Saved in the system keychain", cx),
            url: field(&form.url, "", cx),
            properties: cx.new(|cx| {
                TextInput::area(&form.properties, cx).placeholder("key=value, one per line")
            }),
            group: field(&form.group, "e.g. a project or client", cx),
            ssh_host: field(&form.ssh_host, "bastion.example.com", cx),
            ssh_port: field(&form.ssh_port, "", cx),
            ssh_user: field(&form.ssh_user, "", cx),
            ssh_key: field(&form.ssh_key_path, "Default keys in ~/.ssh", cx),
            ssh_secret: secret(&form.ssh_secret, "", cx),
            delimiter: field(
                &form.delimiter.map(String::from).unwrap_or_default(),
                "Detected",
                cx,
            ),
            charset: field(form.charset.as_deref().unwrap_or(""), "Detected", cx),
        }
    }

    /// Copies the fields into the form.
    fn pull(&self, form: &mut ConnectionForm, cx: &App) {
        let get = |i: &Entity<TextInput>| i.read(cx).text().to_owned();
        form.connection_string = get(&self.connection_string);
        form.name = get(&self.name);
        form.host = get(&self.host);
        form.port = get(&self.port).trim().to_owned();
        form.database = get(&self.database);
        form.user = get(&self.user);
        form.password = get(&self.password);
        form.url = get(&self.url);
        form.properties = get(&self.properties);
        form.group = get(&self.group);
        form.ssh_host = get(&self.ssh_host);
        form.ssh_port = get(&self.ssh_port).trim().to_owned();
        form.ssh_user = get(&self.ssh_user);
        form.ssh_key_path = get(&self.ssh_key);
        form.ssh_secret = get(&self.ssh_secret);
        form.delimiter = get(&self.delimiter).chars().next();
        let charset = get(&self.charset);
        form.charset = (!charset.trim().is_empty()).then(|| charset.trim().to_owned());
    }

    /// Shows the form's values in the fields (after parsing a connection string).
    fn push(&self, form: &ConnectionForm, cx: &mut App) {
        for (input, value) in [
            (&self.host, &form.host),
            (&self.port, &form.port),
            (&self.database, &form.database),
            (&self.user, &form.user),
            (&self.password, &form.password),
            (&self.url, &form.url),
            (&self.properties, &form.properties),
        ] {
            if input.read(cx).text() != value {
                set_text(input, value, cx);
            }
        }
    }
}

/// The connection editor (`ConnectionDialogContent`). Its form lives in the shell's model; this view keeps the text
/// fields and copies them in before testing or saving.
pub struct ConnectionDialogView {
    shell: WeakEntity<Shell>,
    inputs: Inputs,
    _observe: Option<gpui::Subscription>,
}

impl ConnectionDialogView {
    fn new(shell: WeakEntity<Shell>, form: &ConnectionForm, cx: &mut Context<Self>) -> Self {
        let observe = shell
            .upgrade()
            .map(|s| cx.observe(&s, |_, _, cx| cx.notify()));
        Self {
            inputs: Inputs::new(form, cx),
            shell,
            _observe: observe,
        }
    }

    /// Runs `f` on the open dialog's state, then redraws.
    fn with_dialog(&self, cx: &mut App, f: impl FnOnce(&mut ConnectionDialog, &Inputs, &mut App)) {
        let Some(shell) = self.shell.upgrade() else {
            return;
        };
        shell.update(cx, |s, cx| {
            if let Some(d) = s.model.connection_dialog() {
                f(d, &self.inputs, cx);
            }
            cx.notify();
        });
    }

    fn parse(&mut self, cx: &mut Context<Self>) {
        let inputs = &self.inputs;
        let Some(shell) = self.shell.upgrade() else {
            return;
        };
        shell.update(cx, |s, cx| {
            if let Some(d) = s.model.connection_dialog() {
                inputs.pull(&mut d.form, cx);
                d.message = match d.form.parse_connection_string() {
                    Ok(warnings) if warnings.is_empty() => None,
                    Ok(warnings) => Some(Message::info(warnings.join(" "))),
                    Err(e) => Some(Message::error(e)),
                };
                let form = d.form.clone();
                inputs.push(&form, cx);
            }
            cx.notify();
        });
    }

    fn submit(&mut self, test: bool, cx: &mut Context<Self>) {
        let Some(shell) = self.shell.upgrade() else {
            return;
        };
        let inputs = &self.inputs;
        let prepared = shell.update(cx, |s, cx| {
            let (paths, store) = (s.paths.clone(), s.store.clone());
            let d = s.model.connection_dialog()?;
            inputs.pull(&mut d.form, cx);
            d.form.move_url_password();
            let form = d.form.clone();
            inputs.push(&form, cx);
            if let Some(error) = d.form.validate() {
                d.message = Some(Message::error(error));
                cx.notify();
                return None;
            }
            d.busy = true;
            d.message = Some(Message::info(if test {
                "Connecting…"
            } else {
                "Saving…"
            }));
            cx.notify();
            Some((form, d.editing, paths, store))
        });
        let Some((form, editing, paths, store)) = prepared else {
            return;
        };
        let handle = io::handle(cx);
        let config = form.to_config();
        let weak = self.shell.clone();
        if test {
            let secrets = Secrets {
                password: Some(form.password.clone()).filter(|p| !p.is_empty()),
                ssh: Some(form.ssh_secret.clone()).filter(|p| !p.is_empty()),
            };
            cx.spawn(async move |_, cx| {
                let outcome = io::spawn(&handle, async move {
                    registry::test(&config, &secrets, &paths).await
                })
                .await;
                weak.update(cx, |s, cx| {
                    if let Some(d) = s.model.connection_dialog() {
                        d.busy = false;
                        d.message = Some(match outcome {
                            Ok(o) => match (o.suggested_type, o.server) {
                                (Some(t), _) => Message {
                                    text: format!(
                                        "Connection succeeded. This server is {}.",
                                        t.display_name
                                    ),
                                    error: false,
                                    use_type: Some(t),
                                },
                                (None, Some(server)) => {
                                    Message::info(format!("Connected to {server}."))
                                }
                                (None, None) => Message::info("Connection succeeded."),
                            },
                            Err(e) => Message::error(e),
                        });
                    }
                    cx.notify();
                })
                .ok();
            })
            .detach();
        } else {
            let (password, ssh) = (form.password.clone(), form.ssh_secret_to_store().to_owned());
            let id = config.id.clone();
            cx.spawn(async move |_, cx| {
                let saved = io::blocking(&handle, move || {
                    store
                        .lock()
                        .unwrap()
                        .save(&config, Some(&password), Some(&ssh))
                })
                .await;
                weak.update(cx, |s, cx| {
                    match saved {
                        Ok(list) => {
                            s.profiles_rev += 1;
                            let effects = s.model.saved_connection(list, &id, editing);
                            s.dialogs.connection = None;
                            s.run(effects, cx);
                        }
                        Err(e) => {
                            if let Some(d) = s.model.connection_dialog() {
                                d.busy = false;
                                d.message = Some(Message::error(e.to_string()));
                            }
                        }
                    }
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
    }

    fn browse(&mut self, target: Browse, cx: &mut Context<Self>) {
        let picked = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some("Choose".into()),
        });
        cx.spawn(async move |this, cx| {
            let Ok(Ok(Some(paths))) = picked.await else {
                return;
            };
            let Some(path) = paths.into_iter().next() else {
                return;
            };
            let path = path.to_string_lossy().into_owned();
            this.update(cx, |v, cx| match target {
                Browse::Database => {
                    set_text(&v.inputs.database, &path, cx);
                    let p = path.clone();
                    v.with_dialog(cx, move |d, _, _| d.form.use_data_file(&p));
                }
                Browse::Key => set_text(&v.inputs.ssh_key, &path, cx),
            })
            .ok();
        })
        .detach();
    }
}

#[derive(Clone, Copy)]
enum Browse {
    Database,
    Key,
}

impl Render for ConnectionDialogView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        let Some(shell) = self.shell.upgrade() else {
            return div().into_any_element();
        };
        let Some(Dialog::Connection(d)) = shell.read(cx).model.dialog.clone() else {
            return div().into_any_element();
        };
        let form = &d.form;
        let me = cx.entity();
        let tabs = form.tabs();
        let tab = if tabs.contains(&d.tab) {
            d.tab
        } else {
            FormTab::General
        };
        let labels = tabs
            .iter()
            .map(|t| match t {
                FormTab::General => "General".into(),
                FormTab::Ssh if form.use_ssh => "SSH ✓".into(),
                FormTab::Ssh => "SSH".into(),
                FormTab::Options => "Options".into(),
            })
            .collect();
        let selected = tabs.iter().position(|x| *x == tab).unwrap_or(0);
        let strip = {
            let (me, tabs) = (me.clone(), tabs.clone());
            tab_strip("connection.tabs", labels, selected).on_select(move |i, _, cx| {
                let tab = tabs[i];
                me.update(cx, |v, cx| v.with_dialog(cx, |d, _, _| d.tab = tab));
            })
        };
        let i = &self.inputs;
        let field = |e: &Entity<TextInput>| e.clone().into_any_element();
        let mut body = div().flex().flex_col().gap(t.dp(10.0)).p(t.dp(16.0));
        match tab {
            FormTab::General => {
                let me_parse = me.clone();
                body = body.child(row(
                    &t,
                    "Connection string",
                    div()
                        .flex()
                        .gap(t.dp(8.0))
                        .child(div().flex_1().child(field(&i.connection_string)))
                        .child(
                            button("connection.parse", "Parse")
                                .outlined()
                                .on_click(move |_, cx| me_parse.update(cx, |v, cx| v.parse(cx))),
                        )
                        .into_any_element(),
                ));
                body = body.child(row(&t, "Name", field(&i.name)));
                let mut types: Vec<&'static DatabaseType> = connectable_types();
                if dropped(&form.db_type) {
                    types.push(
                        DatabaseType::ENTRIES
                            .iter()
                            .find(|t| **t == form.db_type)
                            .unwrap(),
                    );
                }
                let type_index = types.iter().position(|t| **t == form.db_type).unwrap_or(0);
                let me_type = me.clone();
                let names = types.iter().map(|t| t.display_name.into()).collect();
                body = body.child(row(
                    &t,
                    "Type",
                    list_combo_box("connection.type", names, type_index)
                        .width(240.0)
                        .on_change(move |i, _, cx| {
                            let chosen = *types[i];
                            me_type.update(cx, |v, cx| {
                                v.with_dialog(cx, |d, _, _| d.form.change_type(&chosen))
                            });
                        })
                        .into_any_element(),
                ));
                if form.data_file() {
                    let me_b = me.clone();
                    body = body.child(row(
                        &t,
                        "File",
                        browse_row(
                            &t,
                            "connection.browse-file",
                            field(&i.database),
                            move |cx| me_b.update(cx, |v, cx| v.browse(Browse::Database, cx)),
                        ),
                    ));
                } else if form.is_server() {
                    let url_label = if form.document() {
                        "Use a connection string"
                    } else {
                        "Connect with a URL"
                    };
                    let me_url = me.clone();
                    if !form.url_only() {
                        body = body.child(row(
                            &t,
                            "",
                            checkbox("connection.use-url", url_label)
                                .checked(form.use_url)
                                .on_toggle(move |v, _, cx| {
                                    me_url.update(cx, |view, cx| {
                                        view.with_dialog(cx, |d, _, _| d.form.use_url = v)
                                    })
                                })
                                .into_any_element(),
                        ));
                    }
                    if form.use_url {
                        body = body.child(row(&t, "URL", field(&i.url)));
                    } else {
                        body = body.child(row(
                            &t,
                            "Host",
                            div()
                                .flex()
                                .items_center()
                                .gap(t.dp(8.0))
                                .child(div().flex_1().child(field(&i.host)))
                                .child(text("Port"))
                                .child(div().w(t.dp(80.0)).child(field(&i.port)))
                                .into_any_element(),
                        ));
                        if form.relational() || form.document() {
                            body = body.child(row(&t, "Database", field(&i.database)));
                        }
                    }
                    body = body.child(row(&t, "User", field(&i.user)));
                    body = body.child(row(&t, "Password", field(&i.password)));
                } else {
                    let me_b = me.clone();
                    body = body.child(row(
                        &t,
                        "Database",
                        browse_row(
                            &t,
                            "connection.browse-file",
                            field(&i.database),
                            move |cx| me_b.update(cx, |v, cx| v.browse(Browse::Database, cx)),
                        ),
                    ));
                }
                if let Some(port) = form
                    .db_type
                    .default_port
                    .filter(|_| form.is_server() && !form.use_url)
                {
                    body = body.child(row(
                        &t,
                        "",
                        text(format!("Default port {port}"))
                            .color(TextColor::Info)
                            .into_any_element(),
                    ));
                }
            }
            FormTab::Ssh => {
                let me_ssh = me.clone();
                body = body.child(
                    checkbox("connection.use-ssh", "Connect through an SSH tunnel")
                        .checked(form.use_ssh)
                        .on_toggle(move |v, _, cx| {
                            me_ssh.update(cx, |view, cx| {
                                view.with_dialog(cx, |d, _, _| d.form.use_ssh = v)
                            })
                        }),
                );
                body = body.child(row(
                    &t,
                    "Host",
                    div()
                        .flex()
                        .items_center()
                        .gap(t.dp(8.0))
                        .child(div().flex_1().child(field(&i.ssh_host)))
                        .child(text("Port"))
                        .child(div().w(t.dp(80.0)).child(field(&i.ssh_port)))
                        .into_any_element(),
                ));
                body = body.child(row(&t, "User", field(&i.ssh_user)));
                let me_auth = me.clone();
                let auth = if form.ssh_auth == SshAuth::PrivateKey {
                    0
                } else {
                    1
                };
                body = body.child(row(
                    &t,
                    "Auth",
                    list_combo_box(
                        "connection.ssh-auth",
                        vec!["Private key".into(), "Password".into()],
                        auth,
                    )
                    .width(200.0)
                    .on_change(move |i, _, cx| {
                        me_auth.update(cx, |v, cx| {
                            v.with_dialog(cx, |d, _, _| {
                                d.form.ssh_auth = if i == 0 {
                                    SshAuth::PrivateKey
                                } else {
                                    SshAuth::Password
                                }
                            })
                        })
                    })
                    .into_any_element(),
                ));
                if form.ssh_auth == SshAuth::PrivateKey {
                    let me_b = me.clone();
                    body = body.child(row(
                        &t,
                        "Private key",
                        browse_row(&t, "connection.browse-key", field(&i.ssh_key), move |cx| {
                            me_b.update(cx, |v, cx| v.browse(Browse::Key, cx))
                        }),
                    ));
                    body = body.child(row(&t, "Passphrase", field(&i.ssh_secret)));
                } else {
                    body = body.child(row(&t, "Password", field(&i.ssh_secret)));
                }
                body = body.child(
                    text("Host keys are pinned in ~/.tablegrip/known_hosts the first time; a changed key is refused.")
                        .color(TextColor::Info),
                );
            }
            FormTab::Options => {
                let envs = [
                    Environment::None,
                    Environment::Local,
                    Environment::Development,
                    Environment::Staging,
                    Environment::Production,
                ];
                let me_env = me.clone();
                body = body.child(row(
                    &t,
                    "Environment",
                    list_combo_box(
                        "connection.environment",
                        envs.iter().map(|e| e.label().into()).collect(),
                        envs.iter()
                            .position(|e| *e == form.environment)
                            .unwrap_or(0),
                    )
                    .width(200.0)
                    .on_change(move |i, _, cx| {
                        me_env.update(cx, |v, cx| {
                            v.with_dialog(cx, |d, _, _| d.form.environment = envs[i])
                        })
                    })
                    .into_any_element(),
                ));
                body = body.child(
                    text("Colours the connection everywhere it appears, so production stands out.")
                        .color(TextColor::Info),
                );
                body = body.child(row(&t, "Group", field(&i.group)));
                let (me_ro, me_cw) = (me.clone(), me.clone());
                body = body.child(
                    checkbox("connection.read-only", "Read-only")
                        .checked(form.read_only)
                        .on_toggle(move |v, _, cx| {
                            me_ro.update(cx, |view, cx| {
                                view.with_dialog(cx, |d, _, _| d.form.read_only = v)
                            })
                        }),
                );
                body = body.child(
                    checkbox("connection.confirm", "Confirm every write")
                        .checked(form.confirm_writes)
                        .on_toggle(move |v, _, cx| {
                            me_cw.update(cx, |view, cx| {
                                view.with_dialog(cx, |d, _, _| d.form.confirm_writes = v)
                            })
                        }),
                );
                body = body.child(
                    text("Dangerous statements (DELETE/UPDATE without WHERE, DROP, TRUNCATE) always ask.").color(TextColor::Info),
                );
                if form.data_file() {
                    let me_h = me.clone();
                    body = body.child(row(
                        &t,
                        "Delimiter",
                        div()
                            .w(t.dp(80.0))
                            .child(field(&i.delimiter))
                            .into_any_element(),
                    ));
                    body = body.child(row(
                        &t,
                        "Encoding",
                        div()
                            .w(t.dp(160.0))
                            .child(field(&i.charset))
                            .into_any_element(),
                    ));
                    body = body.child(
                        checkbox("connection.header", "First row is the header")
                            .checked(form.has_header)
                            .on_toggle(move |v, _, cx| {
                                me_h.update(cx, |view, cx| {
                                    view.with_dialog(cx, |d, _, _| d.form.has_header = v)
                                })
                            }),
                    );
                } else {
                    body = body
                        .child(text("Driver properties").color(TextColor::Info))
                        .child(div().h(t.dp(110.0)).child(field(&i.properties)));
                }
            }
        }

        let (me_test, me_cancel, me_save) = (me.clone(), me.clone(), me.clone());
        let message = d.message.clone().map(|m| {
            let mut line =
                div()
                    .flex()
                    .items_center()
                    .gap(t.dp(8.0))
                    .child(text(m.text.clone()).color(if m.error {
                        TextColor::Error
                    } else {
                        TextColor::Info
                    }));
            if let Some(found) = m.use_type {
                let me_use = me.clone();
                line = line.child(
                    link("connection.use-type", format!("Use {}", found.display_name)).on_click(
                        move |_, cx| {
                            me_use.update(cx, |v, cx| {
                                v.with_dialog(cx, |d, _, _| {
                                    d.form.change_type(&found);
                                    d.message = None;
                                })
                            })
                        },
                    ),
                );
            }
            line
        });
        div()
            .size_full()
            .flex()
            .flex_col()
            .child(strip)
            .child(tg_ui::divider())
            .child(
                div()
                    .id("connection.body")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .child(body),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(t.dp(8.0))
                    .px(t.dp(16.0))
                    .py(t.dp(12.0))
                    .children(message)
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(t.dp(8.0))
                            .child(
                                button("connection.test", "Test Connection")
                                    .outlined()
                                    .enabled(!d.busy)
                                    .on_click(move |_, cx| {
                                        me_test.update(cx, |v, cx| v.submit(true, cx))
                                    }),
                            )
                            .child(div().flex_1())
                            .child(button("connection.cancel", "Cancel").outlined().on_click(
                                move |window, cx| {
                                    let shell = me_cancel.read(cx).shell.clone();
                                    shell
                                        .update(cx, |s, cx| {
                                            s.model.dialog = None;
                                            s.after_dialog_change(window, cx);
                                        })
                                        .ok();
                                },
                            ))
                            .child(button("connection.save", "Save").enabled(!d.busy).on_click(
                                move |_, cx| me_save.update(cx, |v, cx| v.submit(false, cx)),
                            )),
                    ),
            )
            .into_any_element()
    }
}

fn browse_row(
    t: &tg_ui::theme::ActiveTheme,
    id: &'static str,
    field: AnyElement,
    on_browse: impl Fn(&mut App) + 'static,
) -> AnyElement {
    div()
        .flex()
        .gap(t.dp(8.0))
        .child(div().flex_1().child(field))
        .child(
            button(id, "Browse…")
                .outlined()
                .on_click(move |_, cx| on_browse(cx)),
        )
        .into_any_element()
}
