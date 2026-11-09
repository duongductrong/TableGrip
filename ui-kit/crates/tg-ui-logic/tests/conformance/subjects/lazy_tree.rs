use std::collections::HashSet;

use serde_json::{Map, Value};
use tg_ui_logic::{Input, Item, LazyTree, TreeEvent};

use super::{Subject, flag};
use crate::step::Step;

/// Rows the Kotlin subject shows when it has `many` leaves: its tree is 120 dp tall and a row 24 dp.
const PAGE: usize = 5;

/// Tab stops the Kotlin tree has after the one focus lands on: three presses leave it, Shift+Tab leaves at once.
const FORWARD_STOPS: u8 = 2;

pub struct LazyTreeSubject {
    items: Vec<Item<String>>,
    tree: LazyTree<String>,
    lazy: bool,
    loaded: HashSet<String>,
    activated: String,
    tab_stop: u8,
}

fn leaf(id: &str) -> Item<String> {
    Item::leaf(id.to_owned())
}

fn node(id: &str, children: Vec<Item<String>>) -> Item<String> {
    Item::node(id.to_owned(), children)
}

impl LazyTreeSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let many = setup.get("many").and_then(Value::as_u64).unwrap_or(0);
        let mut subject = LazyTreeSubject {
            items: Vec::new(),
            tree: LazyTree::new(PAGE),
            lazy: flag(setup, "lazy", false),
            loaded: HashSet::new(),
            activated: String::new(),
            tab_stop: 0,
        };
        subject.items = vec![
            node("a", vec![leaf("a1"), node("a2", vec![leaf("a2x")])]),
            node("b", subject.b_children()),
            leaf("c"),
        ];
        subject
            .items
            .extend((0..many).map(|i| leaf(&format!("r{i}"))));
        subject
    }

    /// `b` has a placeholder child until a lazy tree has loaded it, as the explorer's does.
    fn b_children(&self) -> Vec<Item<String>> {
        if self.lazy && !self.loaded.contains("b") {
            vec![leaf("loading")]
        } else {
            vec![leaf("b1"), leaf("b2")]
        }
    }

    /// The app's reaction to an opened node: load its children and rebuild the tree.
    fn apply(&mut self, events: Vec<TreeEvent<String>>) {
        for event in events {
            match event {
                TreeEvent::Opened(key) if self.lazy && key == "b" => {
                    self.loaded.insert(key);
                    self.items[1] = node("b", self.b_children());
                }
                TreeEvent::Activated(key) => self.activated = key,
                _ => {}
            }
        }
    }

    fn row_id(tag: &str) -> Option<&str> {
        tag.strip_prefix("row:")
    }
}

impl Subject for LazyTreeSubject {
    fn input(&mut self, input: &Input) -> bool {
        self.tab_stop = 0;
        match input {
            Input::Key(key, mods) => {
                let mut events = Vec::new();
                self.tree.key(&self.items, *key, *mods, &mut events);
                self.apply(events);
                false
            }
            _ => false,
        }
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Click(Some(tag)) if Self::row_id(tag).is_some() => {
                self.tab_stop = 0;
                self.tree.click(&Self::row_id(tag).unwrap().to_owned());
            }
            Step::DoubleClick(Some(tag)) if Self::row_id(tag).is_some() => {
                self.tab_stop = 0;
                let events = self
                    .tree
                    .double_click(&self.items, &Self::row_id(tag).unwrap().to_owned());
                self.apply(events);
            }
            other => return Err(format!("unsupported step {other:?}")),
        }
        Ok(())
    }

    fn pointer_focuses(&self, step: &Step) -> bool {
        matches!(step, Step::Click(Some(_)) | Step::DoubleClick(Some(_)))
    }

    fn on_focus(&mut self) {
        self.tree.focus(&self.items);
    }

    fn tab(&mut self, backwards: bool) -> bool {
        self.tree.focus(&self.items);
        if backwards || self.tab_stop >= FORWARD_STOPS {
            self.tab_stop = 0;
            return false;
        }
        self.tab_stop += 1;
        true
    }

    fn observe(&self, key: &str) -> Option<Value> {
        let join = |mut v: Vec<String>| {
            v.sort();
            Value::from(v.join(","))
        };
        match key {
            "open" => Some(join(self.tree.open_keys().cloned().collect())),
            "selected" => Some(join(self.tree.selected().cloned().collect())),
            "rows" => Some(Value::from(
                self.tree
                    .rows(&self.items)
                    .into_iter()
                    .map(|r| r.key)
                    .collect::<Vec<_>>()
                    .join(","),
            )),
            "activated" => Some(Value::from(self.activated.clone())),
            "loaded" => Some(join(self.loaded.iter().cloned().collect())),
            _ => None,
        }
    }
}
