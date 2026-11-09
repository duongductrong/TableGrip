//! TableGrip's UI components on GPUI. Colours, fonts and metrics come from `tg-theme` (resolved exactly as the Kotlin
//! app resolves them), behaviour from `tg-ui-logic`; this crate only draws and wires input.
//!
//! Sizes are in dp and scale with the UI zoom (`ActiveTheme::dp`), as Compose's density does in the Kotlin app.
//!
//! Phase 1 foundations: [island], [divider], [text], [link], [icon], plus the theme global and zoom.
//! Phase 2 controls: [button], [checkbox] (and its tri-state header), [radio], [icon_button], with the focus [ring]
//! they draw as an overlay outside their bounds.

pub mod button;
pub mod checkbox;
pub mod divider;
pub mod fonts;
pub mod icon;
pub mod icon_button;
mod interact;
pub mod island;
pub mod link;
pub mod look;
mod marks;
pub mod palette;
pub mod radio;
pub mod ring;
pub mod text;
pub mod text_input;
pub mod theme;

pub use button::{Button, button};
pub use checkbox::{Checkbox, checkbox};
pub use divider::{Divider, divider, vertical_divider};
pub use icon::{Assets, Icon, icon};
pub use icon_button::{IconButton, icon_button};
pub use island::{Island, island};
pub use link::{Link, LinkLook, link};
pub use look::Look;
pub use radio::{RadioButton, radio_button};
pub use ring::{RingSpec, ring};
pub use text::{Text, TextColor, text};
pub use text_input::{TextInput, TextInputLook};
pub use tg_theme::zoom;
pub use tg_ui_logic::ToggleState;
pub use theme::{ActiveTheme, ThemeExt, hsla, set_theme};
