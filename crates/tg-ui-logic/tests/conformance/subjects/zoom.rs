use serde_json::{Map, Value};
use tg_ui_logic::Input;
use tg_ui_logic::zoom::{self, ZoomCommand};

use super::Subject;
use crate::step::Step;

pub struct ZoomSubject {
    zoom: i64,
}

impl ZoomSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        ZoomSubject {
            zoom: setup
                .get("zoom")
                .and_then(Value::as_i64)
                .unwrap_or(i64::from(zoom::DEFAULT)),
        }
    }
}

impl Subject for ZoomSubject {
    fn input(&mut self, _input: &Input) -> bool {
        false
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        let command = match step {
            Step::Click(Some(tag)) if tag == "in" => ZoomCommand::In,
            Step::Click(Some(tag)) if tag == "out" => ZoomCommand::Out,
            Step::Click(Some(tag)) if tag == "reset" => ZoomCommand::Reset,
            other => return Err(format!("unsupported step {other:?}")),
        };
        self.zoom = i64::from(command.apply(self.zoom));
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "zoom" => Some(Value::from(self.zoom)),
            "level" => Some(Value::from(zoom::snap(self.zoom))),
            "controlShown" => Some(Value::Bool(zoom::control_shown(self.zoom, false))),
            _ => None,
        }
    }
}
