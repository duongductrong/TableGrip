use serde_json::{Map, Value};
use tg_ui_logic::{Input, Link};

use super::{Subject, flag};
use crate::step::Step;

pub struct LinkSubject {
    link: Link,
    clicks: u32,
}

impl LinkSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        LinkSubject {
            link: Link {
                enabled: flag(setup, "enabled", true),
                hovered: false,
            },
            clicks: 0,
        }
    }
}

impl Subject for LinkSubject {
    fn input(&mut self, input: &Input) -> bool {
        let r = self.link.handle(input);
        self.clicks += r.activated as u32;
        r.focus
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::DoubleClick(None) => {
                for _ in 0..2 {
                    let r = self.link.handle(&Input::Click);
                    self.clicks += r.activated as u32;
                }
                Ok(())
            }
            Step::Hover(None) | Step::Unhover => {
                self.link.hover(matches!(step, Step::Hover(_)));
                Ok(())
            }
            Step::Wait(_) => Ok(()),
            other => Err(format!("unsupported step {other:?}")),
        }
    }

    fn observe(&self, key: &str) -> Option<Value> {
        (key == "clicks").then(|| Value::from(self.clicks))
    }
}
