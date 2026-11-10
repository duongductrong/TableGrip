use serde_json::{Map, Value};
use tg_ui_logic::{Input, Scrollbar, TrackClick};

use super::Subject;
use crate::step::Step;

/// The Kotlin subject's geometry: a 200 dp viewport and track, content of `content` dp.
const VIEWPORT: f64 = 200.0;

pub struct ScrollbarSubject(Scrollbar);

impl ScrollbarSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let content = setup
            .get("content")
            .and_then(Value::as_f64)
            .unwrap_or(1000.0);
        let behavior = match setup.get("behavior").and_then(Value::as_str) {
            Some("jump") => TrackClick::JumpToSpot,
            _ => TrackClick::Page,
        };
        let mut bar = Scrollbar::new(VIEWPORT, content, VIEWPORT, behavior);
        if let Some(percent) = setup.get("scroll").and_then(Value::as_f64) {
            bar.set_offset(bar.max() * percent / 100.0);
        }
        ScrollbarSubject(bar)
    }
}

impl Subject for ScrollbarSubject {
    fn input(&mut self, _input: &Input) -> bool {
        false
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Hover(Some(tag)) if tag == "track" => self.0.pointer_enter(),
            Step::Unhover => self.0.pointer_leave(),
            // A click on the track tag lands at the middle of the track.
            Step::Click(Some(tag)) if tag == "track" => self.0.click_track(self.0.track / 2.0),
            other => return Err(format!("unsupported step {other:?}")),
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "scroll" => Some(Value::from((self.0.progress() * 100.0).round() as i64)),
            "max" => Some(Value::from(self.0.max() as i64)),
            _ => None,
        }
    }
}
