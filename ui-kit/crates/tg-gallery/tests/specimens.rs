//! Every Rust specimen has a Kotlin twin of the same size in every theme's reference renders, and draws headless.

use std::path::Path;

use gpui::{Context, IntoElement, Render, TestAppContext, Window};
use tg_gallery::{Specimen, all, theme_named};

fn reference() -> &'static Path {
    Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../conformance/reference"
    ))
}

#[test]
fn every_specimen_has_a_kotlin_twin_of_the_same_size() {
    let specimens = all();
    assert!(specimens.len() >= 5);
    for theme in std::fs::read_dir(reference()).unwrap() {
        let theme = theme.unwrap().path();
        for s in &specimens {
            let json = theme.join(format!("{}.json", s.name));
            let meta: serde_json::Value = serde_json::from_str(
                &std::fs::read_to_string(&json)
                    .unwrap_or_else(|e| panic!("{}: {e}", json.display())),
            )
            .unwrap();
            let density = meta["density"].as_f64().unwrap();
            assert_eq!(
                (
                    meta["width"].as_f64().unwrap() / density,
                    meta["height"].as_f64().unwrap() / density
                ),
                (s.width as f64, s.height as f64),
                "{} in {}",
                s.name,
                theme.display()
            );
            assert!(theme.join(format!("{}.png", s.name)).is_file());
        }
    }
}

#[test]
fn every_built_in_theme_with_a_reference_resolves() {
    for theme in std::fs::read_dir(reference()).unwrap() {
        let json = theme.unwrap().path().join("island.json");
        let meta: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(json).unwrap()).unwrap();
        let name = meta["theme"].as_str().unwrap();
        assert!(theme_named(name).is_some(), "{name}");
    }
}

struct One(Specimen);

impl Render for One {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        (self.0.render)(window, cx)
    }
}

#[gpui::test]
fn every_specimen_draws(cx: &mut TestAppContext) {
    cx.update(|cx| tg_ui::set_theme(cx, theme_named("One Dark").unwrap()));
    for specimen in all() {
        let (_, cx) = cx.add_window_view(move |_, _| One(specimen));
        cx.run_until_parked();
    }
}
