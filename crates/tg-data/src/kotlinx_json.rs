//! What `Json.parseToJsonElement` accepts (kotlinx.serialization 1.11, default configuration), without building the
//! tree. It is stricter than nothing and looser than JSON: a value that isn't a string, object or array is read as a
//! run of characters up to the next structural one, whatever it holds (`[abc]`, `{"a":1;}` and `[~]` parse), and
//! after an array's `]` a further value is read into the same array (`[1,2]x]` parses).

/// Token classes, as kotlinx.serialization sorts characters.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Tc {
    Other,
    Str,
    Esc,
    Ws,
    Comma,
    Colon,
    BeginObj,
    EndObj,
    BeginList,
    EndList,
    Eof,
    Invalid,
}

fn class(c: char) -> Tc {
    let code = c as u32;
    if code >= 0x7e {
        return Tc::Other;
    }
    match c {
        '\t' | '\n' | '\r' | ' ' => Tc::Ws,
        ',' => Tc::Comma,
        ':' => Tc::Colon,
        '{' => Tc::BeginObj,
        '}' => Tc::EndObj,
        '[' => Tc::BeginList,
        ']' => Tc::EndList,
        '"' => Tc::Str,
        '\\' => Tc::Esc,
        _ if code <= 0x20 => Tc::Invalid,
        _ => Tc::Other,
    }
}

fn is_ws(c: char) -> bool {
    matches!(c, ' ' | '\n' | '\r' | '\t')
}

/// Nesting that Kotlin would overflow its stack on (arrays) or switch to an explicit stack for (objects); here it is
/// simply refused.
const MAX_DEPTH: usize = 2000;

struct Lexer {
    s: Vec<char>,
    pos: usize,
    depth: usize,
}

type Parse = Result<(), ()>;

impl Lexer {
    fn skip_ws(&mut self) {
        while self.pos < self.s.len() && is_ws(self.s[self.pos]) {
            self.pos += 1;
        }
    }

    fn peek(&mut self) -> Tc {
        self.skip_ws();
        self.s.get(self.pos).map_or(Tc::Eof, |&c| class(c))
    }

    fn consume(&mut self) -> Tc {
        self.skip_ws();
        match self.s.get(self.pos) {
            Some(&c) => {
                self.pos += 1;
                class(c)
            }
            None => Tc::Eof,
        }
    }

    fn expect(&mut self, token: Tc) -> Parse {
        if self.consume() == token {
            Ok(())
        } else {
            Err(())
        }
    }

    fn can_consume_value(&mut self) -> bool {
        self.skip_ws();
        self.s
            .get(self.pos)
            .is_some_and(|c| !matches!(c, '}' | ']' | ':' | ','))
    }

    /// A quoted string (the only kind of key and quoted value there is when not lenient): escapes are checked, raw
    /// control characters are not.
    fn string(&mut self) -> Parse {
        self.skip_ws();
        if self.s.get(self.pos) != Some(&'"') {
            return Err(());
        }
        self.pos += 1;
        loop {
            match self.s.get(self.pos).copied().ok_or(())? {
                '"' => {
                    self.pos += 1;
                    return Ok(());
                }
                '\\' => {
                    let escape = *self.s.get(self.pos + 1).ok_or(())?;
                    self.pos += 2;
                    if escape == 'u' {
                        if self.pos + 4 >= self.s.len() {
                            return Err(());
                        }
                        if !self.s[self.pos..self.pos + 4]
                            .iter()
                            .all(char::is_ascii_hexdigit)
                        {
                            return Err(());
                        }
                        self.pos += 4;
                    } else if !matches!(escape, 'b' | 't' | 'n' | 'f' | 'r' | '/' | '"' | '\\') {
                        return Err(());
                    }
                }
                _ => self.pos += 1,
            }
        }
    }

    /// An unquoted value: every character up to the next one that isn't an ordinary one.
    fn literal(&mut self) {
        while self.s.get(self.pos).is_some_and(|&c| class(c) == Tc::Other) {
            self.pos += 1;
        }
    }

    fn object(&mut self) -> Parse {
        let mut last = self.consume();
        if last != Tc::BeginObj || self.peek() == Tc::Comma {
            return Err(());
        }
        while self.can_consume_value() {
            self.string()?;
            self.expect(Tc::Colon)?;
            self.read()?;
            last = self.consume();
            match last {
                Tc::Comma => {}
                Tc::EndObj => break,
                _ => return Err(()),
            }
        }
        match last {
            Tc::BeginObj => self.expect(Tc::EndObj),
            Tc::Comma => Err(()),
            _ => Ok(()),
        }
    }

    fn array(&mut self) -> Parse {
        let mut last = self.consume();
        if self.peek() == Tc::Comma {
            return Err(());
        }
        while self.can_consume_value() {
            self.read()?;
            last = self.consume();
            if last != Tc::Comma && last != Tc::EndList {
                return Err(());
            }
        }
        match last {
            Tc::BeginList => self.expect(Tc::EndList),
            Tc::Comma => Err(()),
            _ => Ok(()),
        }
    }

    fn read(&mut self) -> Parse {
        match self.peek() {
            Tc::Str => self.string(),
            Tc::Other => {
                self.literal();
                Ok(())
            }
            token @ (Tc::BeginObj | Tc::BeginList) => {
                self.depth += 1;
                if self.depth > MAX_DEPTH {
                    return Err(());
                }
                let result = if token == Tc::BeginObj {
                    self.object()
                } else {
                    self.array()
                };
                self.depth -= 1;
                result
            }
            _ => Err(()),
        }
    }
}

/// Whether `Json.parseToJsonElement(text)` returns rather than throws.
pub fn parses(text: &str) -> bool {
    let mut lexer = Lexer {
        s: text.chars().collect(),
        pos: 0,
        depth: 0,
    };
    lexer.read().is_ok() && lexer.consume() == Tc::Eof
}
