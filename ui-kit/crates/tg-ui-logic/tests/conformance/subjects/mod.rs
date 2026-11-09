//! One harness per component. To add one: a new file here, its `mod` line, and its arm in [`make`].

mod button;
mod checkbox;
mod dialog;
mod focus_order;
mod icon_button;
mod key_dispatch;
mod keymap;
mod lazy_tree;
mod link;
mod list_combo_box;
mod popup_list;
mod popup_menu;
mod radio_button;
mod scrollbar;
mod split;
mod tab_strip;
mod text;
mod tooltip;
mod tri_state_checkbox;
mod zoom;

use serde_json::{Map, Value};
use tg_ui_logic::{Input, Key, Modifiers};

use crate::step::Step;

pub trait Subject {
    /// Input the shared harness routes to the subject; true when the subject asks for focus.
    fn input(&mut self, input: &Input) -> bool;

    /// A step with no generic meaning (hover, waits, clicks on a part). Fails for steps the subject doesn't support,
    /// so a scenario can't pass by being ignored.
    fn step(&mut self, step: &Step) -> Result<(), String> {
        Err(format!("unsupported step {step:?}"))
    }

    /// Whether a pointer step the subject handled in [`Subject::step`] (a double-click, a click on a part) also gives
    /// it focus, as a plain click does through [`Subject::input`].
    fn pointer_focuses(&self, _step: &Step) -> bool {
        false
    }

    /// Keys the subject takes before focus traversal while it has focus, such as Tab in a text area.
    fn claims_key(&self, _key: Key, _mods: Modifiers) -> bool {
        false
    }

    /// Tab or Shift+Tab ([`backwards`]) inside a subject with several stops of its own (radio rows): true when focus
    /// moved to another of its stops, false to leave the subject and let the window's focus order move on.
    fn tab(&mut self, _backwards: bool) -> bool {
        false
    }

    /// Focus has just moved onto the subject (by `focus`, Tab or a pointer step), as a control's focus listener hears.
    fn on_focus(&mut self) {}

    /// The value of an `expect` key, or None when the subject can't report it.
    fn observe(&self, key: &str) -> Option<Value>;
}

/// None when this crate has no state machine for the component yet: its scenarios are pending.
pub fn make(component: &str, setup: &Map<String, Value>) -> Option<Box<dyn Subject>> {
    match component {
        "button" => Some(Box::new(button::ButtonSubject::new(setup))),
        "checkbox" => Some(Box::new(checkbox::CheckboxSubject::new(setup))),
        "link" => Some(Box::new(link::LinkSubject::new(setup))),
        "dialog" => Some(Box::new(dialog::DialogSubject::new(setup))),
        "focus-order" => Some(Box::new(focus_order::FocusOrderSubject::new(setup))),
        "icon-button" => Some(Box::new(icon_button::IconButtonSubject::new(setup))),
        "key-dispatch" => Some(Box::new(key_dispatch::KeyDispatchSubject::new(setup))),
        "keymap" => Some(Box::new(keymap::KeymapSubject::new(setup))),
        "lazy-tree" => Some(Box::new(lazy_tree::LazyTreeSubject::new(setup))),
        "list-combo-box" => Some(Box::new(list_combo_box::ListComboBoxSubject::new(setup))),
        "popup-list" => Some(Box::new(popup_list::PopupListSubject::new(setup))),
        "popup-menu" => Some(Box::new(popup_menu::PopupMenuSubject::new(setup))),
        "radio-button" => Some(Box::new(radio_button::RadioButtonSubject::new(setup))),
        "scrollbar" => Some(Box::new(scrollbar::ScrollbarSubject::new(setup))),
        "split" => Some(Box::new(split::SplitSubject::new(setup))),
        "tab-strip" => Some(Box::new(tab_strip::TabStripSubject::new(setup))),
        "tooltip" => Some(Box::new(tooltip::TooltipSubject::new(setup))),
        "tri-state-checkbox" => Some(Box::new(tri_state_checkbox::TriStateCheckboxSubject::new(
            setup,
        ))),
        "zoom" => Some(Box::new(zoom::ZoomSubject::new(setup))),
        "text-area" | "text-field" => Some(Box::new(text::TextSubject::new(component, setup))),
        _ => None,
    }
}

pub fn flag(setup: &Map<String, Value>, name: &str, default: bool) -> bool {
    setup.get(name).and_then(Value::as_bool).unwrap_or(default)
}
