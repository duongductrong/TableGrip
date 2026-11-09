//! The app's keyboard shortcuts (`Shortcuts.kt`) and how a key event is matched against them.
//!
//! Verified against the Kotlin app: a shortcut matches a key-down only, with *exactly* its modifiers (so primary+D and
//! primary+Shift+D are different shortcuts); `primary` is Cmd on macOS and Ctrl elsewhere, `ctrl` is the physical
//! Control key on macOS (the same as `primary` elsewhere); Enter also matches the keypad's Enter. One key chord may
//! belong to several actions (primary+F is Find and Focus filter): all of them match, in declaration order, and the
//! window decides which one acts. While Settings → Keymap records a key, the window's shortcuts stand aside.

use crate::input::{Key, Modifiers};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Platform {
    Mac,
    Other,
}

impl Platform {
    pub fn current() -> Platform {
        if cfg!(target_os = "macos") {
            Platform::Mac
        } else {
            Platform::Other
        }
    }
}

/// A physical key. `Key` covers what the scenarios press; the rest are the keys the shortcut table binds beyond it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Code {
    Key(Key),
    /// 0 to 9 on the main row.
    Digit(u8),
    Equals,
    Minus,
    Slash,
    Comma,
    Period,
    LeftBracket,
    RightBracket,
    NumPadEnter,
    NumPadAdd,
    NumPadSubtract,
    /// A modifier key on its own (still being held for a combination).
    Modifier,
}

impl From<Key> for Code {
    fn from(key: Key) -> Code {
        Code::Key(key)
    }
}

/// A key press as the window sees it: which key, and which physical modifier keys are down.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KeyEvent {
    pub code: Code,
    pub down: bool,
    pub ctrl: bool,
    pub meta: bool,
    pub shift: bool,
    pub alt: bool,
}

impl KeyEvent {
    /// A key-down with `mods`, whose `primary` is Cmd on macOS and Ctrl elsewhere.
    pub fn new(code: Code, mods: Modifiers, platform: Platform) -> KeyEvent {
        KeyEvent {
            code,
            down: true,
            ctrl: mods.primary && platform == Platform::Other,
            meta: mods.primary && platform == Platform::Mac,
            shift: mods.shift,
            alt: mods.alt,
        }
    }

    pub fn from_input(key: Key, mods: Modifiers, platform: Platform) -> KeyEvent {
        KeyEvent::new(Code::Key(key), mods, platform)
    }
}

/// A key combination. `primary` is Cmd on macOS and Ctrl elsewhere; `ctrl` is the physical Control key on macOS.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Shortcut {
    pub code: Code,
    pub primary: bool,
    pub shift: bool,
    pub alt: bool,
    pub ctrl: bool,
}

impl Shortcut {
    const fn new(code: Code) -> Shortcut {
        Shortcut {
            code,
            primary: false,
            shift: false,
            alt: false,
            ctrl: false,
        }
    }

    const fn primary(mut self) -> Self {
        self.primary = true;
        self
    }

    const fn shift(mut self) -> Self {
        self.shift = true;
        self
    }

    const fn alt(mut self) -> Self {
        self.alt = true;
        self
    }

    const fn ctrl(mut self) -> Self {
        self.ctrl = true;
        self
    }

    /// Whether `event` presses this shortcut on `platform`.
    pub fn matches(&self, event: &KeyEvent, platform: Platform) -> bool {
        if !event.down {
            return false;
        }
        let same_key = event.code == self.code
            || (self.code == Code::Key(Key::Enter) && event.code == Code::NumPadEnter);
        if !same_key {
            return false;
        }
        let (wants_ctrl, wants_meta) = match platform {
            Platform::Mac => (self.ctrl, self.primary),
            Platform::Other => (self.ctrl || self.primary, false),
        };
        event.ctrl == wants_ctrl
            && event.meta == wants_meta
            && event.shift == self.shift
            && event.alt == self.alt
    }

    /// The shortcut `event` presses, or None for a lone modifier key (recording waits for the combination).
    pub fn of(event: &KeyEvent, platform: Platform) -> Option<Shortcut> {
        if event.code == Code::Modifier {
            return None;
        }
        Some(Shortcut {
            code: event.code,
            primary: match platform {
                Platform::Mac => event.meta,
                Platform::Other => event.ctrl,
            },
            shift: event.shift,
            alt: event.alt,
            ctrl: platform == Platform::Mac && event.ctrl,
        })
    }

    /// How it reads in menus: `⌥⇧⌘L` on macOS, `Ctrl+Alt+Shift+L` elsewhere.
    pub fn label(&self, platform: Platform) -> String {
        let key = key_label(self.code, platform);
        match platform {
            Platform::Mac => {
                let mut s = String::new();
                if self.ctrl {
                    s.push('⌃');
                }
                if self.alt {
                    s.push('⌥');
                }
                if self.shift {
                    s.push('⇧');
                }
                if self.primary {
                    s.push('⌘');
                }
                s + &key
            }
            Platform::Other => {
                let mut parts = Vec::new();
                if self.ctrl || self.primary {
                    parts.push("Ctrl".to_owned());
                }
                if self.alt {
                    parts.push("Alt".to_owned());
                }
                if self.shift {
                    parts.push("Shift".to_owned());
                }
                parts.push(key);
                parts.join("+")
            }
        }
    }
}

fn key_label(code: Code, platform: Platform) -> String {
    let mac = platform == Platform::Mac;
    match code {
        Code::Key(Key::Enter) => if mac { "⏎" } else { "Enter" }.into(),
        Code::Key(Key::Backspace) => if mac { "⌫" } else { "Backspace" }.into(),
        Code::Key(Key::Delete) => if mac { "⌦" } else { "Delete" }.into(),
        Code::Key(Key::Up) => "↑".into(),
        Code::Key(Key::Down) => "↓".into(),
        Code::Key(Key::Left) => "←".into(),
        Code::Key(Key::Right) => "→".into(),
        Code::Key(Key::Tab) => "Tab".into(),
        Code::Key(Key::Space) => "Space".into(),
        Code::Key(Key::PageUp) => "PgUp".into(),
        Code::Key(Key::PageDown) => "PgDn".into(),
        Code::Key(Key::Home) => "Home".into(),
        Code::Key(Key::End) => "End".into(),
        Code::Key(Key::Escape) => "Esc".into(),
        Code::Key(Key::F2) => "F2".into(),
        Code::Key(Key::Letter(c)) => c.to_string(),
        Code::Digit(d) => d.to_string(),
        Code::Equals => "=".into(),
        Code::Minus => "-".into(),
        Code::Slash => "/".into(),
        Code::Comma => ",".into(),
        Code::Period => ".".into(),
        Code::LeftBracket => "[".into(),
        Code::RightBracket => "]".into(),
        Code::NumPadEnter => "Enter".into(),
        Code::NumPadAdd => "+".into(),
        Code::NumPadSubtract => "-".into(),
        Code::Modifier => String::new(),
    }
}

/// A ready-made keymap, as changes over TableGrip's own.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Preset {
    Default,
    IdeClassic,
    VsCode,
}

impl Preset {
    /// By its stored name; unknown names are the default.
    pub fn of(name: &str) -> Preset {
        match name {
            "IdeClassic" => Preset::IdeClassic,
            "VsCode" => Preset::VsCode,
            _ => Preset::Default,
        }
    }

    fn bindings(self, platform: Platform) -> Vec<(&'static str, Shortcut)> {
        let mac = platform == Platform::Mac;
        match self {
            Preset::Default => Vec::new(),
            Preset::IdeClassic => vec![
                ("Cancel", sc(Code::Key(Key::F2)).primary()),
                ("GoToAnything", letter('O').primary()),
            ],
            Preset::VsCode => vec![
                (
                    "Agent",
                    if mac {
                        letter('I').primary().ctrl()
                    } else {
                        letter('I').primary().alt()
                    },
                ),
                (
                    "NextTab",
                    if mac {
                        sc(Code::Key(Key::Right)).primary().alt()
                    } else {
                        sc(Code::Key(Key::PageDown)).primary()
                    },
                ),
                (
                    "PreviousTab",
                    if mac {
                        sc(Code::Key(Key::Left)).primary().alt()
                    } else {
                        sc(Code::Key(Key::PageUp)).primary()
                    },
                ),
                ("Duplicate", sc(Code::Key(Key::Down)).alt().shift()),
                ("DeleteLine", letter('K').primary().shift()),
                ("MoveLineUp", sc(Code::Key(Key::Up)).alt()),
                ("MoveLineDown", sc(Code::Key(Key::Down)).alt()),
                ("Format", letter('F').alt().shift()),
                (
                    "Replace",
                    if mac {
                        letter('F').primary().alt()
                    } else {
                        letter('H').primary()
                    },
                ),
            ],
        }
    }
}

const fn sc(code: Code) -> Shortcut {
    Shortcut::new(code)
}

const fn letter(c: char) -> Shortcut {
    Shortcut::new(Code::Key(Key::Letter(c)))
}

const fn digit(d: u8) -> Shortcut {
    Shortcut::new(Code::Digit(d))
}

/// Every rebindable action and its default shortcut on `platform`, in the order the Kotlin app declares them (which is
/// the order several actions on one chord are reported in).
pub fn defaults(platform: Platform) -> Vec<(&'static str, Shortcut)> {
    let mac = platform == Platform::Mac;
    let enter = Code::Key(Key::Enter);
    let backspace = Code::Key(Key::Backspace);
    let up = Code::Key(Key::Up);
    let down = Code::Key(Key::Down);
    let tab = Code::Key(Key::Tab);
    let by_platform = |mac_choice: Shortcut, other: Shortcut| if mac { mac_choice } else { other };
    vec![
        // General
        ("NewConsole", letter('T').primary()),
        ("CloseTab", letter('W').primary()),
        (
            "NextTab",
            by_platform(
                sc(Code::RightBracket).primary().shift(),
                sc(Code::Key(Key::PageDown)).primary(),
            ),
        ),
        (
            "PreviousTab",
            by_platform(
                sc(Code::LeftBracket).primary().shift(),
                sc(Code::Key(Key::PageUp)).primary(),
            ),
        ),
        ("NextTabAlt", sc(tab).ctrl()),
        ("PreviousTabAlt", sc(tab).ctrl().shift()),
        ("Explorer", by_platform(digit(1).primary(), digit(1).alt())),
        ("History", by_platform(digit(2).primary(), digit(2).alt())),
        ("Agent", by_platform(digit(3).primary(), digit(3).alt())),
        ("Queries", by_platform(digit(4).primary(), digit(4).alt())),
        ("GoToAnything", letter('P').primary()),
        ("Activity", letter('A').primary().alt()),
        (
            "Settings",
            by_platform(sc(Code::Comma).primary(), letter('S').primary().alt()),
        ),
        ("ShowShortcuts", sc(Code::Slash).primary().shift()),
        ("AddToChat", letter('L').primary()),
        ("ToggleSidebar", letter('B').primary()),
        ("ZoomIn", sc(Code::Equals).primary()),
        ("ZoomInAlt", sc(Code::Equals).primary().shift()),
        ("ZoomOut", sc(Code::Minus).primary()),
        ("ResetZoom", digit(0).primary()),
        // Console
        ("RunStatement", sc(enter).primary()),
        ("RunScript", sc(enter).primary().shift()),
        (
            "Cancel",
            by_platform(sc(Code::Period).primary(), sc(Code::Key(Key::F2)).primary()),
        ),
        ("Explain", letter('E').primary().shift()),
        ("Save", letter('S').primary()),
        ("SaveAs", letter('S').primary().shift()),
        // SQL editor
        ("Completion", sc(Code::Key(Key::Space)).ctrl()),
        ("Comment", sc(Code::Slash).primary()),
        ("Duplicate", letter('D').primary()),
        (
            "DeleteLine",
            by_platform(sc(backspace).primary(), letter('Y').primary()),
        ),
        ("MoveLineUp", sc(up).alt().shift()),
        ("MoveLineDown", sc(down).alt().shift()),
        ("Format", letter('L').primary().alt()),
        ("Find", letter('F').primary()),
        ("Replace", letter('R').primary()),
        ("Indent", sc(tab)),
        ("Outdent", sc(tab).shift()),
        // Data grid
        ("Copy", letter('C').primary()),
        ("CopyWithHeader", letter('C').primary().shift()),
        ("SelectAll", letter('A').primary()),
        ("EditCell", sc(Code::Key(Key::F2))),
        ("ExpandEditor", sc(enter).shift()),
        ("SetNull", letter('N').primary().alt()),
        ("AddRow", letter('N').primary()),
        ("DeleteRows", sc(backspace).primary()),
        ("Undo", letter('Z').primary()),
        ("Redo", letter('Z').primary().shift()),
        ("RedoAlt", letter('Y').primary()),
        ("RevertRows", letter('Z').primary().alt()),
        ("Submit", sc(enter).primary()),
        ("Reload", letter('R').primary()),
        ("GoToReferenced", letter('B').primary()),
        // Table filter
        ("FocusFilter", letter('F').primary()),
        ("FilterByValue", letter('F').primary().alt()),
        ("ExcludeValue", letter('F').primary().alt().shift()),
        ("ToggleFilterMode", letter('F').primary().shift()),
        ("ClearFilter", sc(backspace).primary().shift()),
        // Filter builder
        ("ApplyFilter", sc(enter).primary()),
        ("AddCondition", letter('N').primary()),
        ("AddConditionGroup", letter('N').primary().shift()),
        ("AddSqlCondition", letter('N').primary().alt()),
        ("ChooseColumn", letter('C').primary().alt()),
        ("ChooseOperator", letter('O').primary().alt()),
        ("ToggleCombinator", letter('A').primary().shift()),
        ("MatchCase", letter('C').alt()),
        ("PreviousCondition", sc(up)),
        ("NextCondition", sc(down)),
    ]
}

/// The current bindings: the defaults, then a preset's changes, then the user's own.
#[derive(Clone, Debug)]
pub struct Keymap {
    platform: Platform,
    bindings: Vec<(&'static str, Shortcut)>,
    /// Settings → Keymap is recording a key: the window's shortcuts stand aside.
    pub recording: bool,
}

impl Keymap {
    pub fn new(platform: Platform, preset: Preset) -> Keymap {
        let mut bindings = defaults(platform);
        for (id, shortcut) in preset.bindings(platform) {
            if let Some(slot) = bindings.iter_mut().find(|(i, _)| *i == id) {
                slot.1 = shortcut;
            }
        }
        Keymap {
            platform,
            bindings,
            recording: false,
        }
    }

    pub fn platform(&self) -> Platform {
        self.platform
    }

    /// The user's own binding for action `id`; unknown ids are ignored.
    pub fn set(&mut self, id: &str, shortcut: Shortcut) {
        if let Some(slot) = self.bindings.iter_mut().find(|(i, _)| *i == id) {
            slot.1 = shortcut;
        }
    }

    pub fn binding(&self, id: &str) -> Option<Shortcut> {
        self.bindings
            .iter()
            .find(|(i, _)| *i == id)
            .map(|(_, s)| *s)
    }

    /// Whether `event` presses action `id`'s current shortcut.
    pub fn matches(&self, id: &str, event: &KeyEvent) -> bool {
        self.binding(id)
            .is_some_and(|s| s.matches(event, self.platform))
    }

    /// Every action `event` presses, in declaration order, ignoring `recording`.
    pub fn matching(&self, event: &KeyEvent) -> Vec<&'static str> {
        self.bindings
            .iter()
            .filter(|(_, s)| s.matches(event, self.platform))
            .map(|(id, _)| *id)
            .collect()
    }

    /// What the window may run for `event`: nothing while a key is being recorded.
    pub fn window_matching(&self, event: &KeyEvent) -> Vec<&'static str> {
        if self.recording {
            Vec::new()
        } else {
            self.matching(event)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(code: Code, mods: Modifiers, platform: Platform) -> KeyEvent {
        KeyEvent::new(code, mods, platform)
    }

    fn mods(primary: bool, shift: bool, alt: bool) -> Modifiers {
        Modifiers {
            primary,
            shift,
            alt,
        }
    }

    #[test]
    fn there_are_sixty_seven_actions_with_unique_ids() {
        for platform in [Platform::Mac, Platform::Other] {
            let table = defaults(platform);
            assert_eq!(table.len(), 67);
            let mut ids: Vec<_> = table.iter().map(|(id, _)| *id).collect();
            ids.sort();
            ids.dedup();
            assert_eq!(ids.len(), 67);
        }
    }

    #[test]
    fn modifiers_must_match_exactly() {
        for platform in [Platform::Mac, Platform::Other] {
            let map = Keymap::new(platform, Preset::Default);
            let t = Code::Key(Key::Letter('T'));
            assert_eq!(
                map.matching(&ev(t, mods(true, false, false), platform)),
                ["NewConsole"]
            );
            assert!(
                map.matching(&ev(t, mods(false, false, false), platform))
                    .is_empty()
            );
            assert!(
                map.matching(&ev(t, mods(true, true, false), platform))
                    .is_empty()
            );
            assert!(
                map.matching(&ev(t, mods(true, false, true), platform))
                    .is_empty()
            );
        }
    }

    #[test]
    fn primary_is_cmd_on_the_mac_and_ctrl_elsewhere() {
        let t = Code::Key(Key::Letter('T'));
        let map = Keymap::new(Platform::Mac, Preset::Default);
        let mut e = ev(t, Modifiers::default(), Platform::Mac);
        e.ctrl = true;
        assert!(map.matching(&e).is_empty());
        e.ctrl = false;
        e.meta = true;
        assert_eq!(map.matching(&e), ["NewConsole"]);
        let map = Keymap::new(Platform::Other, Preset::Default);
        let mut e = ev(t, Modifiers::default(), Platform::Other);
        e.meta = true;
        assert!(map.matching(&e).is_empty());
        e.meta = false;
        e.ctrl = true;
        assert_eq!(map.matching(&e), ["NewConsole"]);
    }

    #[test]
    fn the_physical_control_key_on_the_mac_binds_ctrl_shortcuts() {
        let map = Keymap::new(Platform::Mac, Preset::Default);
        let mut e = ev(Code::Key(Key::Tab), Modifiers::default(), Platform::Mac);
        e.ctrl = true;
        assert_eq!(map.matching(&e), ["NextTabAlt"]);
        e.shift = true;
        assert_eq!(map.matching(&e), ["PreviousTabAlt"]);
        let mut e = ev(Code::Key(Key::Space), Modifiers::default(), Platform::Mac);
        e.ctrl = true;
        assert_eq!(map.matching(&e), ["Completion"]);
    }

    #[test]
    fn the_keypad_enter_is_enter() {
        for platform in [Platform::Mac, Platform::Other] {
            let map = Keymap::new(platform, Preset::Default);
            let p = mods(true, false, false);
            assert_eq!(
                map.matching(&ev(Code::NumPadEnter, p, platform)),
                ["RunStatement", "Submit", "ApplyFilter"]
            );
            assert_eq!(
                map.matching(&ev(Code::NumPadEnter, mods(false, true, false), platform)),
                ["ExpandEditor"]
            );
        }
    }

    #[test]
    fn a_key_up_matches_nothing() {
        let map = Keymap::new(Platform::Mac, Preset::Default);
        let mut e = ev(
            Code::Key(Key::Letter('T')),
            mods(true, false, false),
            Platform::Mac,
        );
        e.down = false;
        assert!(map.matching(&e).is_empty());
    }

    #[test]
    fn one_chord_can_belong_to_several_actions_in_declaration_order() {
        let map = Keymap::new(Platform::Mac, Preset::Default);
        let e = ev(
            Code::Key(Key::Letter('F')),
            mods(true, false, false),
            Platform::Mac,
        );
        assert_eq!(map.matching(&e), ["Find", "FocusFilter"]);
    }

    #[test]
    fn recording_suspends_the_window() {
        let mut map = Keymap::new(Platform::Mac, Preset::Default);
        let e = ev(
            Code::Key(Key::Letter('T')),
            mods(true, false, false),
            Platform::Mac,
        );
        assert_eq!(map.window_matching(&e), ["NewConsole"]);
        map.recording = true;
        assert!(map.window_matching(&e).is_empty());
        // The recorder itself still sees every key.
        assert_eq!(map.matching(&e), ["NewConsole"]);
    }

    #[test]
    fn presets_rebind_only_what_they_name() {
        let ide = Keymap::new(Platform::Mac, Preset::IdeClassic);
        let p = |c| {
            ev(
                Code::Key(Key::Letter(c)),
                mods(true, false, false),
                Platform::Mac,
            )
        };
        assert_eq!(ide.matching(&p('O')), ["GoToAnything"]);
        assert!(ide.matching(&p('P')).is_empty());
        let vs = Keymap::new(Platform::Other, Preset::VsCode);
        let e = ev(
            Code::Key(Key::Letter('F')),
            mods(false, true, true),
            Platform::Other,
        );
        assert_eq!(vs.matching(&e), ["Format"]);
        assert_eq!(Preset::of("nonsense"), Preset::Default);
    }

    #[test]
    fn a_users_binding_replaces_the_default() {
        let mut map = Keymap::new(Platform::Mac, Preset::Default);
        map.set("NewConsole", letter('J').primary());
        let t = ev(
            Code::Key(Key::Letter('T')),
            mods(true, false, false),
            Platform::Mac,
        );
        assert!(map.matching(&t).is_empty());
        let j = ev(
            Code::Key(Key::Letter('J')),
            mods(true, false, false),
            Platform::Mac,
        );
        assert_eq!(map.matching(&j), ["NewConsole"]);
    }

    #[test]
    fn labels_follow_the_platform() {
        let s = letter('L').primary().alt().shift();
        assert_eq!(s.label(Platform::Mac), "⌥⇧⌘L");
        assert_eq!(s.label(Platform::Other), "Ctrl+Alt+Shift+L");
        assert_eq!(
            sc(Code::Key(Key::Enter)).primary().label(Platform::Mac),
            "⌘⏎"
        );
    }

    #[test]
    fn recording_ignores_a_lone_modifier() {
        let mut e = ev(Code::Modifier, Modifiers::default(), Platform::Mac);
        e.meta = true;
        assert_eq!(Shortcut::of(&e, Platform::Mac), None);
        let e = ev(
            Code::Key(Key::Letter('K')),
            mods(true, true, false),
            Platform::Mac,
        );
        let s = Shortcut::of(&e, Platform::Mac).unwrap();
        assert_eq!(s, letter('K').primary().shift());
    }
}
