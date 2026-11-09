//! Behaviour of TableGrip's UI components, without any drawing or framework.
//!
//! Every decision a control makes about input (what a click, a key or typed text does, who has focus) lives here as a
//! plain state machine, so it can be tested quickly and exactly, and the rendering layer stays thin. The expected
//! behaviour comes from `ui-kit/conformance/scenarios`, which the Kotlin app passes today.

pub mod button;
pub mod checkbox;
pub mod focus;
pub mod input;

pub use button::Button;
pub use checkbox::Checkbox;
pub use focus::FocusOrder;
pub use input::{Input, Key, Modifiers};
