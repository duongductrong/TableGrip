use serde_json::{Map, Value};
use tg_ui_logic::{Input, PopupList};

use super::Subject;
use crate::step::Step;

pub struct PopupListSubject(PopupList);

impl PopupListSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let rows = setup.get("rows").and_then(Value::as_u64).unwrap_or(4) as usize;
        PopupListSubject(PopupList::new(rows))
    }
}

fn row(tag: &str) -> Result<usize, String> {
    tag.strip_prefix("row:")
        .and_then(|n| n.parse().ok())
        .ok_or(format!("unknown row {tag}"))
}

impl Subject for PopupListSubject {
    fn input(&mut self, input: &Input) -> bool {
        self.0.handle(input);
        false
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Click(Some(tag)) => self.0.click(row(tag)?),
            Step::Hover(Some(tag)) => self.0.hover(Some(row(tag)?)),
            Step::Unhover => self.0.hover(None),
            other => return Err(format!("unsupported step {other:?}")),
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        let index = |i: Option<usize>| Value::from(i.map_or(-1, |i| i as i64));
        match key {
            "highlighted" => Some(Value::from(self.0.highlighted())),
            "hovered" => Some(index(self.0.hovered())),
            "picked" => Some(index(self.0.picked())),
            "painted" => Some(Value::from(
                (0..self.0.rows)
                    .filter(|&i| self.0.painted(i))
                    .map(|i| i.to_string())
                    .collect::<Vec<_>>()
                    .join(","),
            )),
            _ => None,
        }
    }
}
