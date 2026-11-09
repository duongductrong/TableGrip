//! TableGrip's UI components on GPUI. Colours, fonts and metrics come from `tg-theme` (resolved exactly as the Kotlin
//! app resolves them), behaviour from `tg-ui-logic`; this crate only draws and wires input.
//!
//! Sizes are in dp and scale with the UI zoom (`ActiveTheme::dp`), as Compose's density does in the Kotlin app.
//!
//! Phase 1 foundations: [island], [divider], [text], [link], [icon], plus the theme global and zoom.

pub mod divider;
pub mod fonts;
pub mod icon;
pub mod island;
pub mod link;
pub mod text;
pub mod theme;

pub use divider::{Divider, divider, vertical_divider};
pub use icon::{Assets, Icon, icon};
pub use island::{Island, island};
pub use link::{Link, LinkLook, link};
pub use text::{Text, TextColor, text};
pub use tg_theme::zoom;
pub use theme::{ActiveTheme, ThemeExt, hsla, set_theme};
