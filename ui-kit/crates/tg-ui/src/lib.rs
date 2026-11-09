//! TableGrip's UI components on GPUI. Colours, fonts and metrics come from `tg-theme` (resolved exactly as the Kotlin
//! app resolves them), behaviour from `tg-ui-logic`; this crate only draws and wires input.
//!
//! Sizes are in dp and scale with the UI zoom (`ActiveTheme::dp`), as Compose's density does in the Kotlin app.
//!
//! Phase 1 foundations: [island], [divider], [text], [link], [icon], plus the theme global and zoom.
//! Phase 2 controls: [button], [checkbox] (and its tri-state header), [radio], [icon_button], with the focus [ring]
//! they draw as an overlay outside their bounds.
//! Phase 4 popups: [popup_menu], [list_combo_box], [popup_list] and the [scrollbar] with its [scroll_area], on the
//! shared [popup] surface.
//! Phase 5 layout: the [tab_strip], the workspace's pill [workspace_tabs] (drag to reorder), the [split] layout whose
//! 6dp gap between islands is the handle, and the virtualised [tree].

pub mod button;
pub mod checkbox;
pub mod divider;
pub mod fonts;
pub mod icon;
pub mod icon_button;
mod interact;
pub mod island;
pub mod link;
pub mod list_combo;
pub mod look;
mod marks;
pub mod palette;
pub mod popup;
pub mod popup_list;
pub mod popup_menu;
pub mod radio;
pub mod ring;
pub mod scrollbar;
pub mod split;
pub mod tab_strip;
pub mod text;
pub mod text_input;
pub mod theme;
pub mod tree;
pub mod workspace_tabs;

pub use button::{Button, button};
pub use checkbox::{Checkbox, checkbox};
pub use divider::{Divider, divider, vertical_divider};
pub use icon::{Assets, Icon, icon};
pub use icon_button::{IconButton, icon_button};
pub use island::{Island, island};
pub use link::{Link, LinkLook, link};
pub use list_combo::{ListComboBox, list_combo_box};
pub use look::Look;
pub use popup_list::{PopupList, popup_list};
pub use popup_menu::{MenuItem, PopupMenu, menu_item, popup_menu};
pub use radio::{RadioButton, radio_button};
pub use ring::{RingSpec, ring};
pub use scrollbar::{ScrollArea, Scrollbar, scroll_area, scrollbar};
pub use split::{SplitLayout, split_layout};
pub use tab_strip::{TabStrip, tab_strip};
pub use text::{Text, TextColor, text};
pub use text_input::{TextInput, TextInputLook};
pub use tg_theme::zoom;
pub use tg_ui_logic::ToggleState;
pub use theme::{ActiveTheme, ThemeExt, hsla, set_theme};
pub use tree::{Tree, tree};
pub use workspace_tabs::{WorkspaceTab, WorkspaceTabs, workspace_tabs};
