use crate::keymap::{Code, KeyEvent, Keymap, Platform};

/// Zoom of the whole interface, in the steps browsers and VS Code use; stored in settings as a percentage.
pub const DEFAULT: u32 = 100;
pub const LEVELS: [u32; 13] = [50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300];

/// The level closest to `percent`, for values typed into settings by hand; a tie goes to the lower level.
pub fn snap(percent: i64) -> u32 {
    *LEVELS
        .iter()
        .min_by_key(|&&level| (i64::from(level) - percent).abs())
        .expect("levels")
}

/// The next level above (for `direction > 0`) or below `percent`, stopping at the ends. `percent` need not be a level.
pub fn step(percent: i64, direction: i32) -> u32 {
    if direction > 0 {
        LEVELS
            .iter()
            .copied()
            .find(|&l| i64::from(l) > percent)
            .unwrap_or(LEVELS[LEVELS.len() - 1])
    } else {
        LEVELS
            .iter()
            .copied()
            .rev()
            .find(|&l| i64::from(l) < percent)
            .unwrap_or(LEVELS[0])
    }
}

/// The status bar's zoom control shows only away from the default, or while its menu is open.
pub fn control_shown(percent: i64, menu_open: bool) -> bool {
    snap(percent) != DEFAULT || menu_open
}

/// What a zoom shortcut asks for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ZoomCommand {
    In,
    Out,
    Reset,
}

impl ZoomCommand {
    /// The stored percentage after this command from `percent`.
    pub fn apply(self, percent: i64) -> u32 {
        match self {
            ZoomCommand::In => step(percent, 1),
            ZoomCommand::Out => step(percent, -1),
            ZoomCommand::Reset => DEFAULT,
        }
    }
}

/// The zoom command `event` is, from any window: the keymap's Zoom in (and its alternative), Zoom out and Reset zoom,
/// plus the keypad's + and − with the primary modifier (Shift allowed, as the keypad's + often needs it).
pub fn command_for(event: &KeyEvent, keymap: &Keymap) -> Option<ZoomCommand> {
    let platform = keymap.platform();
    let primary_only = |code: Code| {
        event.down
            && event.code == code
            && !event.alt
            && match platform {
                Platform::Mac => event.meta && !event.ctrl,
                Platform::Other => event.ctrl && !event.meta,
            }
    };
    let matches = |id: &str| keymap.matches(id, event);
    if matches("ZoomIn") || matches("ZoomInAlt") || primary_only(Code::NumPadAdd) {
        Some(ZoomCommand::In)
    } else if matches("ZoomOut") || primary_only(Code::NumPadSubtract) {
        Some(ZoomCommand::Out)
    } else if matches("ResetZoom") {
        Some(ZoomCommand::Reset)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keymap::Preset;

    #[test]
    fn steps_walk_the_levels_and_stop_at_the_ends() {
        assert_eq!(step(100, 1), 110);
        assert_eq!(step(110, 1), 125);
        assert_eq!(step(100, -1), 90);
        assert_eq!(step(300, 1), 300);
        assert_eq!(step(50, -1), 50);
    }

    #[test]
    fn steps_from_a_value_between_levels_go_to_the_neighbours() {
        assert_eq!(step(87, 1), 90);
        assert_eq!(step(87, -1), 80);
        assert_eq!(step(160, 1), 175);
        assert_eq!(step(160, -1), 150);
    }

    #[test]
    fn snapping_picks_the_nearest_level_and_the_lower_on_a_tie() {
        assert_eq!(snap(87), 90);
        assert_eq!(snap(85), 80);
        assert_eq!(snap(1000), 300);
        assert_eq!(snap(0), 50);
        assert_eq!(snap(-5), 50);
        assert_eq!(snap(102), 100);
    }

    #[test]
    fn the_control_hides_at_the_default_unless_its_menu_is_open() {
        assert!(!control_shown(100, false));
        assert!(!control_shown(102, false));
        assert!(control_shown(100, true));
        assert!(control_shown(110, false));
    }

    #[test]
    fn the_shortcuts_are_recognised_with_exact_modifiers() {
        use crate::Modifiers;
        for platform in [Platform::Mac, Platform::Other] {
            let keymap = Keymap::new(platform, Preset::Default);
            let primary = Modifiers {
                primary: true,
                ..Modifiers::default()
            };
            let shifted = Modifiers {
                shift: true,
                ..primary
            };
            let ev = |code, mods| KeyEvent::new(code, mods, platform);
            assert_eq!(
                command_for(&ev(Code::Equals, primary), &keymap),
                Some(ZoomCommand::In)
            );
            assert_eq!(
                command_for(&ev(Code::Equals, shifted), &keymap),
                Some(ZoomCommand::In)
            );
            assert_eq!(
                command_for(&ev(Code::Minus, primary), &keymap),
                Some(ZoomCommand::Out)
            );
            assert_eq!(command_for(&ev(Code::Minus, shifted), &keymap), None);
            assert_eq!(
                command_for(&ev(Code::Digit(0), primary), &keymap),
                Some(ZoomCommand::Reset)
            );
            assert_eq!(
                command_for(&ev(Code::Digit(0), Modifiers::default()), &keymap),
                None
            );
            assert_eq!(
                command_for(&ev(Code::NumPadAdd, primary), &keymap),
                Some(ZoomCommand::In)
            );
            assert_eq!(
                command_for(&ev(Code::NumPadAdd, shifted), &keymap),
                Some(ZoomCommand::In)
            );
            assert_eq!(
                command_for(&ev(Code::NumPadSubtract, primary), &keymap),
                Some(ZoomCommand::Out)
            );
            assert_eq!(
                command_for(&ev(Code::NumPadAdd, Modifiers::default()), &keymap),
                None
            );
        }
    }

    #[test]
    fn the_physical_control_key_on_the_mac_is_not_the_primary_modifier() {
        let keymap = Keymap::new(Platform::Mac, Preset::Default);
        let mut event = KeyEvent::new(Code::NumPadAdd, crate::Modifiers::default(), Platform::Mac);
        event.ctrl = true;
        assert_eq!(command_for(&event, &keymap), None);
    }
}
