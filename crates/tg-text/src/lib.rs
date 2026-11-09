//! Text editing for TableGrip's TextField and TextArea, without any drawing or framework.
//!
//! [`Editor`] holds a UTF-8 buffer, a caret and selection on grapheme boundaries, undo history and the input-method
//! composition (marked text). The rendering layer feeds it keys, typed text and input-method calls, and draws what it
//! reports. The behaviour is held to the Kotlin app through `conformance/scenarios/text-field.json` and
//! `text-area.json`; what scenarios can't express (platform word movement, IME) is covered by this crate's tests.
//!
//! Offsets are UTF-8 byte offsets into [`Editor::text`]. Input-method APIs that count UTF-16 code units (macOS,
//! Windows) convert with [`Editor::offset_from_utf16`] and [`Editor::offset_to_utf16`].

mod clipboard;
mod editor;
mod history;
mod ime;
mod segment;

pub use clipboard::{Clipboard, MemoryClipboard};
pub use editor::{Editor, KeyResult, Options, Platform, Selection};
pub use segment::grapheme_count;
