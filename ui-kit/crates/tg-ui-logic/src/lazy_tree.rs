use std::collections::HashSet;
use std::hash::Hash;

use crate::input::{Key, Modifiers};

/// A node of the tree the app hands over. `node` is true for anything that can open, even while its children are
/// still being loaded (the explorer shows a "loading" child meanwhile); false for a leaf.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Item<K> {
    pub key: K,
    pub node: bool,
    pub children: Vec<Item<K>>,
}

impl<K> Item<K> {
    pub fn leaf(key: K) -> Self {
        Item {
            key,
            node: false,
            children: Vec::new(),
        }
    }

    pub fn node(key: K, children: Vec<Item<K>>) -> Self {
        Item {
            key,
            node: true,
            children,
        }
    }
}

fn find<'a, K: Eq>(items: &'a [Item<K>], key: &K) -> Option<&'a Item<K>> {
    items.iter().find_map(|item| {
        if &item.key == key {
            Some(item)
        } else {
            find(&item.children, key)
        }
    })
}

fn forget<K: Eq + Hash>(item: &Item<K>, open: &mut HashSet<K>) {
    for child in &item.children {
        open.remove(&child.key);
        forget(child, open);
    }
}

/// A row of the flattened, visible tree.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row<K> {
    pub key: K,
    pub depth: usize,
    pub node: bool,
    pub open: bool,
}

/// What the tree asks the app to do; loading children is the app's job, started by [`TreeEvent::Opened`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TreeEvent<K> {
    Opened(K),
    Closed(K),
    /// A double click (the explorer opens the node's tab).
    Activated(K),
}

/// Selection and open state of a lazily loaded tree, as the explorer's LazyTree behaves.
///
/// Verified against the Kotlin app:
/// - closing a node forgets which of its descendants were open;
/// - a click selects only that row; a double click does the same, flips the node open or closed, and activates it;
/// - Down and Up move one row (clamped), Home and End go to the ends, PageDown and PageUp move a viewport of rows;
/// - Right opens a closed node, and on an open node selects its first child; Left closes an open node and otherwise
///   selects the row above (not the parent), doing nothing on the first row;
/// - Shift with a movement key adds every row between the old and the new active row to the selection, and never
///   drops any; without Shift the selection collapses to the new row. Ctrl/Cmd+A selects every visible row and leaves
///   the active row where it was;
/// - focus with nothing selected selects the first row; Enter, Space, Escape and typing do nothing.
#[derive(Clone, Debug)]
pub struct LazyTree<K> {
    open: HashSet<K>,
    selected: HashSet<K>,
    active: Option<K>,
    /// Rows a PageDown or PageUp moves: the viewport's.
    pub page: usize,
}

impl<K: Clone + Eq + Hash> LazyTree<K> {
    pub fn new(page: usize) -> Self {
        LazyTree {
            open: HashSet::new(),
            selected: HashSet::new(),
            active: None,
            page: page.max(1),
        }
    }

    pub fn is_open(&self, key: &K) -> bool {
        self.open.contains(key)
    }

    pub fn is_selected(&self, key: &K) -> bool {
        self.selected.contains(key)
    }

    pub fn active(&self) -> Option<&K> {
        self.active.as_ref()
    }

    pub fn selected(&self) -> impl Iterator<Item = &K> {
        self.selected.iter()
    }

    pub fn open_keys(&self) -> impl Iterator<Item = &K> {
        self.open.iter()
    }

    /// Opens nodes from outside (the app expands the way to a search match); returns the keys that were closed.
    pub fn open_nodes(&mut self, keys: impl IntoIterator<Item = K>) -> Vec<K> {
        keys.into_iter()
            .filter(|k| self.open.insert(k.clone()))
            .collect()
    }

    /// "Collapse All".
    pub fn close_all(&mut self) {
        self.open.clear();
    }

    /// The visible rows, depth first, with only open nodes' children.
    pub fn rows(&self, items: &[Item<K>]) -> Vec<Row<K>> {
        fn walk<K: Clone + Eq + Hash>(
            tree: &LazyTree<K>,
            items: &[Item<K>],
            depth: usize,
            out: &mut Vec<Row<K>>,
        ) {
            for item in items {
                let open = item.node && tree.open.contains(&item.key);
                out.push(Row {
                    key: item.key.clone(),
                    depth,
                    node: item.node,
                    open,
                });
                if open {
                    walk(tree, &item.children, depth + 1, out);
                }
            }
        }
        let mut out = Vec::new();
        walk(self, items, 0, &mut out);
        out
    }

    fn select_only(&mut self, key: &K) {
        self.selected.clear();
        self.selected.insert(key.clone());
        self.active = Some(key.clone());
    }

    /// The tree gained focus: with nothing selected the first row is.
    pub fn focus(&mut self, items: &[Item<K>]) {
        if self.selected.is_empty()
            && let Some(first) = self.rows(items).first()
        {
            let key = first.key.clone();
            self.select_only(&key);
        }
    }

    pub fn click(&mut self, key: &K) {
        self.select_only(key);
    }

    pub fn double_click(&mut self, items: &[Item<K>], key: &K) -> Vec<TreeEvent<K>> {
        self.select_only(key);
        let mut events = Vec::new();
        if let Some(row) = self.rows(items).into_iter().find(|r| &r.key == key)
            && row.node
        {
            events.push(self.toggle(items, key));
        }
        events.push(TreeEvent::Activated(key.clone()));
        events
    }

    /// Flips a node. Closing forgets what was open inside it, as the Kotlin tree's open set does.
    fn toggle(&mut self, items: &[Item<K>], key: &K) -> TreeEvent<K> {
        if self.open.remove(key) {
            if let Some(item) = find(items, key) {
                forget(item, &mut self.open);
            }
            TreeEvent::Closed(key.clone())
        } else {
            self.open.insert(key.clone());
            TreeEvent::Opened(key.clone())
        }
    }

    /// A key press while the tree has focus; true when the tree used it.
    pub fn key(
        &mut self,
        items: &[Item<K>],
        key: Key,
        mods: Modifiers,
        events: &mut Vec<TreeEvent<K>>,
    ) -> bool {
        self.focus(items);
        let rows = self.rows(items);
        if rows.is_empty() {
            return false;
        }
        let at = self
            .active
            .as_ref()
            .and_then(|a| rows.iter().position(|r| &r.key == a))
            .unwrap_or(0);
        let last = rows.len() - 1;
        let target = match key {
            Key::Letter('A') if mods.primary && !mods.shift && !mods.alt => {
                self.selected = rows.iter().map(|r| r.key.clone()).collect();
                return true;
            }
            _ if mods.primary || mods.alt => return false,
            Key::Down => (at + 1).min(last),
            Key::Up => at.saturating_sub(1),
            Key::Home => 0,
            Key::End => last,
            Key::PageDown => (at + self.page).min(last),
            Key::PageUp => at.saturating_sub(self.page),
            Key::Right => {
                let row = &rows[at];
                if row.node && !row.open {
                    events.push(self.toggle(items, &row.key.clone()));
                } else if row.node && at < last && rows[at + 1].depth > row.depth {
                    self.select_only(&rows[at + 1].key.clone());
                }
                return true;
            }
            Key::Left => {
                let row = &rows[at];
                if row.node && row.open {
                    events.push(self.toggle(items, &row.key.clone()));
                } else if at > 0 {
                    self.select_only(&rows[at - 1].key.clone());
                }
                return true;
            }
            _ => return false,
        };
        if mods.shift {
            let (from, to) = (at.min(target), at.max(target));
            for row in &rows[from..=to] {
                self.selected.insert(row.key.clone());
            }
            self.active = Some(rows[target].key.clone());
        } else {
            self.select_only(&rows[target].key.clone());
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tree() -> Vec<Item<&'static str>> {
        vec![
            Item::node(
                "a",
                vec![Item::leaf("a1"), Item::node("a2", vec![Item::leaf("a2x")])],
            ),
            Item::node("b", vec![Item::leaf("b1")]),
            Item::leaf("c"),
        ]
    }

    fn press(t: &mut LazyTree<&'static str>, items: &[Item<&'static str>], key: Key, shift: bool) {
        let mods = Modifiers {
            shift,
            ..Modifiers::default()
        };
        t.key(items, key, mods, &mut Vec::new());
    }

    fn keys(t: &LazyTree<&'static str>) -> Vec<&'static str> {
        let mut v: Vec<_> = t.selected().copied().collect();
        v.sort();
        v
    }

    #[test]
    fn rows_list_only_open_children_with_their_depth() {
        let items = tree();
        let mut t = LazyTree::new(5);
        assert_eq!(t.rows(&items).len(), 3);
        t.open_nodes(["a", "a2"]);
        let rows = t.rows(&items);
        let shown: Vec<_> = rows.iter().map(|r| (r.key, r.depth)).collect();
        assert_eq!(
            shown,
            [
                ("a", 0),
                ("a1", 1),
                ("a2", 1),
                ("a2x", 2),
                ("b", 0),
                ("c", 0)
            ]
        );
    }

    #[test]
    fn closing_a_parent_forgets_what_was_open_inside() {
        let items = tree();
        let mut t = LazyTree::new(5);
        t.open_nodes(["a", "a2"]);
        t.click(&"a");
        press(&mut t, &items, Key::Left, false);
        assert_eq!(t.rows(&items).len(), 3);
        press(&mut t, &items, Key::Right, false);
        assert_eq!(t.rows(&items).len(), 5);
        assert!(!t.is_open(&"a2"));
    }

    #[test]
    fn right_opens_then_enters_and_left_closes_then_goes_up() {
        let items = tree();
        let mut t = LazyTree::new(5);
        t.click(&"a");
        let mut events = Vec::new();
        t.key(&items, Key::Right, Modifiers::default(), &mut events);
        assert_eq!(events, [TreeEvent::Opened("a")]);
        t.key(&items, Key::Right, Modifiers::default(), &mut events);
        assert_eq!(t.active(), Some(&"a1"));
        t.key(&items, Key::Down, Modifiers::default(), &mut events);
        t.key(&items, Key::Left, Modifiers::default(), &mut events);
        // a2 is closed: Left goes to the row above, a1, not to the parent.
        assert_eq!(t.active(), Some(&"a1"));
    }

    #[test]
    fn shift_adds_the_range_and_never_removes() {
        let items = tree();
        let mut t = LazyTree::new(5);
        t.click(&"a");
        press(&mut t, &items, Key::Down, true);
        press(&mut t, &items, Key::Down, true);
        press(&mut t, &items, Key::Up, true);
        assert_eq!(keys(&t), ["a", "b", "c"]);
        // The active row is b now, so a plain Up lands on a alone.
        press(&mut t, &items, Key::Up, false);
        assert_eq!(keys(&t), ["a"]);
    }

    #[test]
    fn select_all_keeps_the_active_row() {
        let items = tree();
        let mut t = LazyTree::new(5);
        t.click(&"a");
        let mods = Modifiers {
            primary: true,
            ..Modifiers::default()
        };
        t.key(&items, Key::Letter('A'), mods, &mut Vec::new());
        assert_eq!(keys(&t), ["a", "b", "c"]);
        press(&mut t, &items, Key::Down, false);
        assert_eq!(keys(&t), ["b"]);
    }

    #[test]
    fn a_double_click_flips_a_node_and_activates() {
        let items = tree();
        let mut t = LazyTree::new(5);
        assert_eq!(
            t.double_click(&items, &"a"),
            [TreeEvent::Opened("a"), TreeEvent::Activated("a")]
        );
        assert_eq!(
            t.double_click(&items, &"a"),
            [TreeEvent::Closed("a"), TreeEvent::Activated("a")]
        );
        assert_eq!(t.double_click(&items, &"c"), [TreeEvent::Activated("c")]);
    }

    #[test]
    fn focus_selects_the_first_row_only_when_nothing_is() {
        let items = tree();
        let mut t = LazyTree::new(5);
        t.focus(&items);
        assert_eq!(keys(&t), ["a"]);
        t.click(&"c");
        t.focus(&items);
        assert_eq!(keys(&t), ["c"]);
    }

    #[test]
    fn pages_move_a_viewport_and_clamp() {
        let items: Vec<_> = (0..12)
            .map(|i| Item::leaf(Box::leak(format!("r{i}").into_boxed_str()) as &'static str))
            .collect();
        let mut t = LazyTree::new(5);
        t.click(&"r0");
        press(&mut t, &items, Key::PageDown, false);
        assert_eq!(t.active(), Some(&"r5"));
        press(&mut t, &items, Key::PageDown, false);
        press(&mut t, &items, Key::PageDown, false);
        assert_eq!(t.active(), Some(&"r11"));
        press(&mut t, &items, Key::PageUp, false);
        assert_eq!(t.active(), Some(&"r6"));
    }
}
