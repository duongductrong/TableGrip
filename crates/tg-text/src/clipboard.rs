/// Where cut and copy put text and paste takes it from. The rendering layer wraps the system clipboard; tests use
/// [`MemoryClipboard`].
pub trait Clipboard {
    fn read_text(&mut self) -> Option<String>;
    fn write_text(&mut self, text: String);
}

/// A clipboard that lives in memory.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MemoryClipboard(pub Option<String>);

impl Clipboard for MemoryClipboard {
    fn read_text(&mut self) -> Option<String> {
        self.0.clone()
    }

    fn write_text(&mut self, text: String) {
        self.0 = Some(text);
    }
}
