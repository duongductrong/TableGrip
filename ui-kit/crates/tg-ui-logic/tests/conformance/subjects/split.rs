use serde_json::{Map, Value};
use tg_ui_logic::{Input, Split, SplitKind};

use super::Subject;

pub struct SplitSubject {
    split: Split,
    total: f64,
}

impl SplitSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let num =
            |name: &str, default: f64| setup.get(name).and_then(Value::as_f64).unwrap_or(default);
        let total = num("width", 800.0);
        let (first_min, second_min) = (num("firstMin", 0.0), num("secondMin", 0.0));
        let mut split = match setup.get("restore").and_then(Value::as_str) {
            Some("sidebar") => Split::restored(
                SplitKind::Sidebar,
                num("saved", -1.0),
                first_min,
                second_min,
            ),
            Some("agent") => {
                Split::restored(SplitKind::Agent, num("saved", -1.0), first_min, second_min)
            }
            _ => Split::new(num("ratio", 0.5), first_min, second_min),
        };
        split.layout(total);
        SplitSubject { split, total }
    }
}

impl Subject for SplitSubject {
    fn input(&mut self, _input: &Input) -> bool {
        false
    }

    fn observe(&self, key: &str) -> Option<Value> {
        let rounded = |v: f64| Value::from(v.round() as i64);
        match key {
            "first" => Some(rounded(self.split.first_width(self.total))),
            "second" => Some(rounded(self.split.second_width(self.total))),
            "ratio" => Some(rounded(
                self.split.first_width(self.total) * 100.0 / self.total,
            )),
            _ => None,
        }
    }
}
