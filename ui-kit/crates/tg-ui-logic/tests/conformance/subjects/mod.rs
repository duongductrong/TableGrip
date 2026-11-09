//! One harness per component. To add one: a new file here, its `mod` line, and its arm in [`make`].

mod button;
mod checkbox;
mod link;

use serde_json::{Map, Value};
use tg_ui_logic::Input;

use crate::step::Step;

pub trait Subject {
    /// Input the shared harness routes to the subject; true when the subject asks for focus.
    fn input(&mut self, input: &Input) -> bool;

    /// A step with no generic meaning (hover, waits, clicks on a part). Fails for steps the subject doesn't support,
    /// so a scenario can't pass by being ignored.
    fn step(&mut self, step: &Step) -> Result<(), String> {
        Err(format!("unsupported step {step:?}"))
    }

    /// The value of an `expect` key, or None when the subject can't report it.
    fn observe(&self, key: &str) -> Option<Value>;
}

/// None when this crate has no state machine for the component yet: its scenarios are pending.
pub fn make(component: &str, setup: &Map<String, Value>) -> Option<Box<dyn Subject>> {
    match component {
        "button" => Some(Box::new(button::ButtonSubject::new(setup))),
        "checkbox" => Some(Box::new(checkbox::CheckboxSubject::new(setup))),
        "link" => Some(Box::new(link::LinkSubject::new(setup))),
        _ => None,
    }
}

pub fn flag(setup: &Map<String, Value>, name: &str, default: bool) -> bool {
    setup.get(name).and_then(Value::as_bool).unwrap_or(default)
}
