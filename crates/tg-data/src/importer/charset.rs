//! Text encodings of data files (`java.nio.charset` in Kotlin), through encoding_rs. Names are the ones Java prints
//! for the common ones: `UTF-8`, `UTF-16LE`, `UTF-16BE`, `windows-1252`.

use std::io::Read;

pub type Charset = &'static encoding_rs::Encoding;

pub const UTF_8: Charset = encoding_rs::UTF_8;
pub const UTF_16LE: Charset = encoding_rs::UTF_16LE;
pub const UTF_16BE: Charset = encoding_rs::UTF_16BE;
pub const WINDOWS_1252: Charset = encoding_rs::WINDOWS_1252;

/// The encoding a name or alias stands for (`utf8`, `latin1`, `cp1252`…), or None.
pub fn for_name(name: &str) -> Option<Charset> {
    encoding_rs::Encoding::for_label(name.trim().as_bytes())
}

/// The charset a byte-order mark announces, and the mark's length.
pub fn bom_charset(head: &[u8]) -> Option<(Charset, usize)> {
    match head {
        [0xEF, 0xBB, 0xBF, ..] => Some((UTF_8, 3)),
        [0xFE, 0xFF, ..] => Some((UTF_16BE, 2)),
        [0xFF, 0xFE, ..] => Some((UTF_16LE, 2)),
        _ => None,
    }
}

/// Characters decoded from a byte stream a chunk at a time. Strict decoding (UTF-8 unless told otherwise) hands out
/// what decoded before bad bytes and fails only on the next read, so a parser's line count points at them.
pub struct CharReader {
    input: Box<dyn Read + Send>,
    decoder: encoding_rs::Decoder,
    strict: bool,
    bytes: Vec<u8>,
    chars: Vec<char>,
    position: usize,
    pending: Option<String>,
    done: bool,
    unread: Vec<char>,
}

impl CharReader {
    /// `strict`: fail on bytes the encoding can't decode instead of replacing them.
    pub fn new(input: Box<dyn Read + Send>, charset: Charset, strict: bool) -> Self {
        Self {
            input,
            decoder: charset.new_decoder_without_bom_handling(),
            strict,
            bytes: vec![0; 64 * 1024],
            chars: Vec::new(),
            position: 0,
            pending: None,
            done: false,
            unread: Vec::new(),
        }
    }

    /// Puts `c` back to be read next.
    pub fn unread(&mut self, c: char) {
        self.unread.push(c);
    }

    /// The next character, None at the end, or the decoding error once everything before it was read.
    pub fn next_char(&mut self) -> Result<Option<char>, String> {
        if let Some(c) = self.unread.pop() {
            return Ok(Some(c));
        }
        loop {
            if self.position < self.chars.len() {
                self.position += 1;
                return Ok(Some(self.chars[self.position - 1]));
            }
            if let Some(e) = &self.pending {
                return Err(e.clone());
            }
            if self.done {
                return Ok(None);
            }
            self.fill()?;
        }
    }

    fn fill(&mut self) -> Result<(), String> {
        let n = loop {
            match self.input.read(&mut self.bytes) {
                Ok(n) => break n,
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e.to_string()),
            }
        };
        let last = n == 0;
        let mut out = String::with_capacity(n * 2 + 16);
        let mut input = &self.bytes[..n];
        loop {
            out.reserve(input.len() * 2 + 16);
            if self.strict {
                let (result, read) = self
                    .decoder
                    .decode_to_string_without_replacement(input, &mut out, last);
                input = &input[read..];
                match result {
                    encoding_rs::DecoderResult::InputEmpty => break,
                    encoding_rs::DecoderResult::OutputFull => continue,
                    encoding_rs::DecoderResult::Malformed(..) => {
                        self.pending = Some(format!(
                            "Input length = 1 ({})",
                            self.decoder.encoding().name()
                        ));
                        break;
                    }
                }
            } else {
                let (result, read, _) = self.decoder.decode_to_string(input, &mut out, last);
                input = &input[read..];
                if result == encoding_rs::CoderResult::InputEmpty {
                    break;
                }
            }
        }
        self.chars = out.chars().collect();
        self.position = 0;
        self.done = last;
        Ok(())
    }
}

/// Encodes `text` as `charset`, or None when the charset can't hold one of its characters. encoding_rs doesn't
/// encode UTF-16, so that's done here.
pub fn encode(text: &str, charset: Charset) -> Option<Vec<u8>> {
    if charset == UTF_16LE {
        return Some(text.encode_utf16().flat_map(u16::to_le_bytes).collect());
    }
    if charset == UTF_16BE {
        return Some(text.encode_utf16().flat_map(u16::to_be_bytes).collect());
    }
    let (bytes, _, unmappable) = charset.encode(text);
    (!unmappable).then(|| bytes.into_owned())
}
