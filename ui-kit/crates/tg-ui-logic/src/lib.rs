//! Behaviour of TableGrip's UI components, without any drawing or framework.
//!
//! Every decision a control makes about input (what a click, a key or typed text does, who has focus) lives here as a
//! plain state machine, so it can be tested quickly and exactly, and the rendering layer stays thin. The expected
//! behaviour comes from `ui-kit/conformance/scenarios`, which the Kotlin app passes today.

pub mod button;
pub mod checkbox;
pub mod focus;
pub mod icon_button;
pub mod input;
pub mod lazy_tree;
pub mod link;
pub mod list_combo;
pub mod popup_list;
pub mod popup_menu;
pub mod radio;
pub mod scrollbar;
pub mod split;
pub mod tab_drag;
pub mod tab_strip;
pub mod tooltip;
pub mod tri_state;
pub mod virtual_list;

pub use button::Button;
pub use checkbox::Checkbox;
pub use focus::FocusOrder;
pub use icon_button::IconButton;
pub use input::{Input, Key, Modifiers};
pub use lazy_tree::{Item, LazyTree, Row, TreeEvent};
pub use link::Link;
pub use list_combo::ListComboBox;
pub use popup_list::PopupList;
pub use popup_menu::PopupMenu;
pub use radio::RadioGroup;
pub use scrollbar::{Scrollbar, TrackClick};
pub use split::{Split, SplitKind};
pub use tab_drag::{TabAction, TabRow};
pub use tab_strip::TabStrip;
pub use tooltip::Tooltip;
pub use tri_state::{ToggleState, TriStateCheckbox};
