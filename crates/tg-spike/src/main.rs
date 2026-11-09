use gpui::{
    App, Application, Bounds, TitlebarOptions, WindowBounds, WindowOptions, point, prelude::*, px,
    size,
};
use tg_spike::{Spike, bind_keys};

fn main() {
    Application::new().run(|cx: &mut App| {
        bind_keys(cx);
        let bounds = Bounds::centered(None, size(px(560.), px(320.)), cx);
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                // Draw our own title bar on the frame colour, like the Kotlin app's decorated window.
                titlebar: Some(TitlebarOptions {
                    title: Some("TableGrip spike".into()),
                    appears_transparent: true,
                    traffic_light_position: Some(point(px(12.), px(14.))),
                }),
                ..Default::default()
            },
            |window, cx| {
                let view = cx.new(Spike::new);
                window.focus(&view.read(cx).checkbox_focus);
                view
            },
        )
        .unwrap();
        cx.activate(true);
    });
}
