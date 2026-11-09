//! How a key press travels through nested handlers: in through every layer's preview handler (parents first), then out
//! through every layer's bubble handler (children first), until one consumes it.
//!
//! Layer 0 is the outermost (the window), the last layer the innermost (the focused control). A handler that takes the
//! key stops it: the layers after it, in either direction, never see it. This is the order the Kotlin app relies on
//! when it lets the window's preview handler win over a focused text field (tab switching, zoom) while its bubble
//! handler (everything else) only sees keys the focused control declined.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    /// On the way in: parents before children.
    Preview,
    /// On the way out: children before parents.
    Bubble,
}

/// A handler a key reached.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Visit {
    pub layer: usize,
    pub phase: Phase,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Dispatch {
    /// Every handler the key reached, in order, including the one that consumed it.
    pub trace: Vec<Visit>,
    pub consumed_by: Option<Visit>,
}

impl Dispatch {
    pub fn consumed(&self) -> bool {
        self.consumed_by.is_some()
    }
}

/// Sends a key through `layers` layers; `handle(layer, phase)` returns true when that handler consumes it.
pub fn dispatch(layers: usize, mut handle: impl FnMut(usize, Phase) -> bool) -> Dispatch {
    let order = (0..layers)
        .map(|l| (l, Phase::Preview))
        .chain((0..layers).rev().map(|l| (l, Phase::Bubble)));
    let mut trace = Vec::new();
    for (layer, phase) in order {
        let visit = Visit { layer, phase };
        trace.push(visit);
        if handle(layer, phase) {
            return Dispatch {
                trace,
                consumed_by: Some(visit),
            };
        }
    }
    Dispatch {
        trace,
        consumed_by: None,
    }
}

impl std::fmt::Display for Visit {
    /// `<layer>.<phase>`, the form scenarios use.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let phase = match self.phase {
            Phase::Preview => "preview",
            Phase::Bubble => "bubble",
        };
        write!(f, "{}.{phase}", self.layer)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(layers: usize, takes: Option<(usize, Phase)>) -> Dispatch {
        dispatch(layers, |l, p| Some((l, p)) == takes)
    }

    fn names(d: &Dispatch) -> Vec<String> {
        d.trace.iter().map(ToString::to_string).collect()
    }

    #[test]
    fn an_unhandled_key_goes_in_then_out() {
        let d = run(3, None);
        assert_eq!(
            names(&d),
            [
                "0.preview",
                "1.preview",
                "2.preview",
                "2.bubble",
                "1.bubble",
                "0.bubble"
            ]
        );
        assert!(!d.consumed());
    }

    #[test]
    fn the_first_taker_stops_the_key() {
        let d = run(3, Some((1, Phase::Preview)));
        assert_eq!(names(&d), ["0.preview", "1.preview"]);
        assert_eq!(d.consumed_by.unwrap().layer, 1);
    }

    #[test]
    fn a_parents_preview_beats_a_childs_bubble() {
        let d = dispatch(2, |l, p| {
            (l, p) == (0, Phase::Preview) || (l, p) == (1, Phase::Bubble)
        });
        assert_eq!(names(&d), ["0.preview"]);
    }

    #[test]
    fn bubbling_reaches_the_parents_only_if_the_child_declined() {
        let d = run(2, Some((0, Phase::Bubble)));
        assert_eq!(
            names(&d),
            ["0.preview", "1.preview", "1.bubble", "0.bubble"]
        );
        let d = dispatch(2, |_, p| p == Phase::Bubble);
        assert_eq!(d.consumed_by.unwrap().layer, 1);
    }

    #[test]
    fn no_layers_reach_no_one() {
        assert!(run(0, None).trace.is_empty());
    }
}
