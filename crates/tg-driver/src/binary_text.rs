//! How binary cells read into a grid appear as text (`driver/BinaryText.kt`): `0x` and their hex digits. Values longer
//! than [`PREVIEW_BYTES`] keep only their start, followed by `… (N bytes)`, so a result of large blobs stays small.

pub const PREVIEW_BYTES: usize = 16 * 1024;

const DIGITS: &[u8; 16] = b"0123456789ABCDEF";

pub fn encode(bytes: &[u8]) -> String {
    encode_with_limit(bytes, PREVIEW_BYTES)
}

pub fn encode_with_limit(bytes: &[u8], limit: usize) -> String {
    let shown = bytes.len().min(limit);
    let mut out = String::with_capacity(2 + shown * 2 + 24);
    out.push_str("0x");
    for b in &bytes[..shown] {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 0x0F) as usize] as char);
    }
    if shown < bytes.len() {
        out.push_str(&format!("… ({} bytes)", bytes.len()));
    }
    out
}

/// What exports write in place of a binary value.
pub fn placeholder(len: usize) -> String {
    format!("<binary {len} bytes>")
}

/// The first bytes of a binary value of `total_size` bytes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Preview {
    pub bytes: Vec<u8>,
    pub total_size: usize,
}

impl Preview {
    pub fn complete(&self) -> bool {
        self.bytes.len() == self.total_size
    }
}

/// The bytes `text` shows and the value's full size; None if it isn't [`encode`]'s output.
pub fn decode(text: &str) -> Option<Preview> {
    let rest = text.strip_prefix("0x")?;
    let (hex, total) = match rest.find('…') {
        Some(i) => {
            let size = rest[i..].strip_prefix("… (")?.strip_suffix(" bytes)")?;
            if size.is_empty() || !size.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            (&rest[..i], Some(size.parse::<usize>().ok()?))
        }
        None => (rest, None),
    };
    if hex.len() % 2 != 0
        || !hex
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'A'..=b'F').contains(&b))
    {
        return None;
    }
    let bytes: Vec<u8> = (0..hex.len() / 2)
        .map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).unwrap())
        .collect();
    let total_size = total.unwrap_or(bytes.len());
    Some(Preview { bytes, total_size })
}

#[cfg(test)]
mod tests {
    use super::*;

    // BinaryTextTest.kt
    #[test]
    fn short_values_round_trip() {
        let bytes = [0x00, 0x7F, 0x80, 0xFF];
        let text = encode(&bytes);
        assert_eq!(text, "0x007F80FF");
        let preview = decode(&text).unwrap();
        assert_eq!(preview.bytes, bytes);
        assert!(preview.complete());
    }

    #[test]
    fn long_values_keep_their_start_and_size() {
        let bytes: Vec<u8> = (0..10).collect();
        let text = encode_with_limit(&bytes, 4);
        assert_eq!(text, "0x00010203… (10 bytes)");
        let preview = decode(&text).unwrap();
        assert_eq!(preview.bytes.len(), 4);
        assert_eq!(preview.total_size, 10);
        assert!(!preview.complete());
    }

    #[test]
    fn other_text_is_not_binary() {
        assert_eq!(decode("hello"), None);
        assert_eq!(decode("0x123"), None);
        assert_eq!(decode("0xab"), None);
        assert_eq!(
            decode("0x"),
            Some(Preview {
                bytes: vec![],
                total_size: 0
            })
        );
    }
}
