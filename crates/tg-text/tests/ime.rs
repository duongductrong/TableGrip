//! Vietnamese typing through the composition model, driven the way an OS input method drives a text field.
//!
//! [`Vietnamese`] is a small Telex/VNI engine standing in for the OS input method: for every key it works out the
//! word so far and sends it as marked text, then commits it on space. [`Rewriting`] is the other kind of input method
//! (common on Windows and with third-party macOS ones): it never marks, it rewrites the word's end in place.

use tg_text::{Editor, MemoryClipboard, Options, Platform};
use tg_ui_logic::{Key, Modifiers};

const TONED: [&str; 12] = [
    "aàáảãạ",
    "ăằắẳẵặ",
    "âầấẩẫậ",
    "eèéẻẽẹ",
    "êềếểễệ",
    "iìíỉĩị",
    "oòóỏõọ",
    "ôồốổỗộ",
    "ơờớởỡợ",
    "uùúủũụ",
    "ưừứửữự",
    "yỳýỷỹỵ",
];

#[derive(Clone, Copy, PartialEq)]
enum Method {
    Telex,
    Vni,
}

/// Tones in [`TONED`] order: 0 none, 1 huyền, 2 sắc, 3 hỏi, 4 ngã, 5 nặng.
fn tone_key(method: Method, key: char) -> Option<usize> {
    match (method, key) {
        (Method::Telex, 'z') | (Method::Vni, '0') => Some(0),
        (Method::Telex, 'f') | (Method::Vni, '2') => Some(1),
        (Method::Telex, 's') | (Method::Vni, '1') => Some(2),
        (Method::Telex, 'r') | (Method::Vni, '3') => Some(3),
        (Method::Telex, 'x') | (Method::Vni, '4') => Some(4),
        (Method::Telex, 'j') | (Method::Vni, '5') => Some(5),
        _ => None,
    }
}

fn is_vowel(c: char) -> bool {
    "aăâeêioôơuưy".contains(c.to_lowercase().next().unwrap())
}

fn with_case(c: char, upper: bool) -> char {
    if upper {
        c.to_uppercase().next().unwrap()
    } else {
        c
    }
}

/// One syllable being typed: its letters (without tone) and its tone.
#[derive(Default)]
struct Syllable {
    letters: Vec<char>,
    tone: usize,
}

impl Syllable {
    fn key(&mut self, method: Method, key: char) {
        let lower = key.to_lowercase().next().unwrap();
        if let Some(tone) = tone_key(method, lower)
            && self.letters.iter().any(|&c| is_vowel(c))
        {
            self.tone = tone;
            return;
        }
        // Diacritic keys rewrite the nearest letter they apply to; otherwise they're plain letters.
        let rewrite: &[(char, char)] = match (method, lower) {
            (Method::Telex, 'a') if self.last_is('a') => &[('a', 'â')],
            (Method::Telex, 'e') if self.last_is('e') => &[('e', 'ê')],
            (Method::Telex, 'o') if self.last_is('o') => &[('o', 'ô')],
            (Method::Telex, 'd') if self.last_is('d') => &[('d', 'đ')],
            (Method::Telex, 'w') if self.has('a') && !self.has('o') && !self.has('u') => {
                &[('a', 'ă')]
            }
            (Method::Telex, 'w') | (Method::Vni, '7') => &[('o', 'ơ'), ('u', 'ư')],
            (Method::Vni, '6') => &[('a', 'â'), ('e', 'ê'), ('o', 'ô')],
            (Method::Vni, '8') => &[('a', 'ă')],
            (Method::Vni, '9') => &[('d', 'đ')],
            _ => &[],
        };
        let mut changed = false;
        for i in (0..self.letters.len()).rev() {
            let c = self.letters[i];
            let upper = c.is_uppercase();
            if let Some(&(_, to)) = rewrite
                .iter()
                .find(|(from, _)| *from == c.to_lowercase().next().unwrap())
            {
                self.letters[i] = with_case(to, upper);
                changed = true;
                // `uo` + w gives `ươ`: the horn goes on both.
                if to == 'ơ' && i > 0 && self.letters[i - 1].to_lowercase().next() == Some('u') {
                    let upper = self.letters[i - 1].is_uppercase();
                    self.letters[i - 1] = with_case('ư', upper);
                }
                break;
            }
        }
        if !changed {
            self.letters.push(key);
        }
    }

    fn last_is(&self, c: char) -> bool {
        self.letters
            .iter()
            .rev()
            .find(|&&l| is_vowel(l) || l.eq_ignore_ascii_case(&'d'))
            .map(|l| l.to_lowercase().next())
            == Some(Some(c))
    }

    fn has(&self, c: char) -> bool {
        self.letters
            .iter()
            .any(|l| l.to_lowercase().next() == Some(c))
    }

    /// The syllable with its tone on the vowel Vietnamese spelling puts it on.
    fn render(&self) -> String {
        let mut letters = self.letters.clone();
        let lower: Vec<char> = letters
            .iter()
            .map(|c| c.to_lowercase().next().unwrap())
            .collect();
        // The vowel cluster, leaving out the u of `qu` and the i of `gi` when another vowel follows.
        let mut vowels: Vec<usize> = (0..letters.len()).filter(|&i| is_vowel(lower[i])).collect();
        if vowels.len() > 1 {
            let first = vowels[0];
            if first > 0
                && ((lower[first - 1] == 'q' && lower[first] == 'u')
                    || (lower[first - 1] == 'g' && lower[first] == 'i'))
            {
                vowels.remove(0);
            }
        }
        if self.tone != 0 && !vowels.is_empty() {
            let marked = vowels
                .iter()
                .rev()
                .copied()
                .find(|&i| "ăâêôơư".contains(lower[i]));
            let ends_in_consonant = vowels.last().is_some_and(|&i| i + 1 < letters.len());
            let at = match marked {
                Some(i) => i,
                None if vowels.len() == 1 || ends_in_consonant => *vowels.last().unwrap(),
                None if vowels.len() == 2 => vowels[0],
                None => vowels[1],
            };
            let base = lower[at];
            let row = TONED.iter().find(|row| row.starts_with(base)).unwrap();
            let toned = row.chars().nth(self.tone).unwrap();
            letters[at] = with_case(toned, letters[at].is_uppercase());
        }
        letters.into_iter().collect()
    }
}

/// An input method that marks the word being typed and commits it on space.
struct Vietnamese {
    method: Method,
    syllable: Syllable,
}

impl Vietnamese {
    fn new(method: Method) -> Self {
        Vietnamese {
            method,
            syllable: Syllable::default(),
        }
    }

    fn key(&mut self, editor: &mut Editor, key: char) {
        if key == ' ' {
            editor.unmark_text();
            editor.replace_text_in_range(None, " ");
            self.syllable = Syllable::default();
            return;
        }
        self.syllable.key(self.method, key);
        editor.replace_and_mark_text_in_range(None, &self.syllable.render(), None);
    }

    fn type_keys(&mut self, editor: &mut Editor, keys: &str) {
        for key in keys.chars() {
            self.key(editor, key);
        }
    }
}

/// An input method that writes each key straight into the text and rewrites the word's end as it changes.
struct Rewriting {
    method: Method,
    syllable: Syllable,
    shown: String,
}

impl Rewriting {
    fn type_keys(&mut self, editor: &mut Editor, keys: &str) {
        for key in keys.chars() {
            if key == ' ' {
                editor.replace_text_in_range(None, " ");
                self.syllable = Syllable::default();
                self.shown.clear();
                continue;
            }
            self.syllable.key(self.method, key);
            let word = self.syllable.render();
            // Replace only what changed: the common prefix stays.
            let common: usize = self
                .shown
                .chars()
                .zip(word.chars())
                .take_while(|(a, b)| a == b)
                .map(|(a, _)| a.len_utf8())
                .sum();
            let caret = editor.selection().head;
            let start = caret - (self.shown.len() - common);
            editor.replace_text_in_range(Some(start..caret), &word[common..]);
            self.shown = word;
        }
    }
}

fn field(text: &str) -> Editor {
    Editor::new(text, Options::default())
}

#[test]
fn telex_types_vietnamese() {
    let mut editor = field("");
    Vietnamese::new(Method::Telex).type_keys(&mut editor, "tieengs Vieetj");
    assert_eq!(
        editor.marked_range(),
        Some("tiếng ".len().."tiếng Việt".len())
    );
    editor.unmark_text();
    assert_eq!(editor.text(), "tiếng Việt");
    assert_eq!(editor.marked_range(), None);
    assert_eq!(editor.selection().head, editor.text().len());
}

#[test]
fn vni_types_vietnamese() {
    let mut editor = field("");
    Vietnamese::new(Method::Vni).type_keys(&mut editor, "tie6ng1 Vie6t5 ");
    assert_eq!(editor.text(), "tiếng Việt ");
    assert!(!editor.is_composing());
}

#[test]
fn telex_spells_common_words() {
    for (keys, word) in [
        ("ddaay", "đây"),
        ("nguwowif", "người"),
        ("khoong", "không"),
        ("chuwax", "chữa"),
        ("quyeenf", "quyền"),
        ("giuwax", "giữa"),
        ("hoaf", "hòa"),
        ("VIEETJ", "VIỆT"),
        ("Nam", "Nam"),
    ] {
        let mut editor = field("");
        Vietnamese::new(Method::Telex).type_keys(&mut editor, keys);
        editor.unmark_text();
        assert_eq!(editor.text(), word, "{keys}");
    }
}

#[test]
fn vni_spells_common_words() {
    for (keys, word) in [
        ("d9a6y", "đây"),
        ("ngu7o7i2", "người"),
        ("kho6ng", "không"),
        ("a8n", "ăn"),
    ] {
        let mut editor = field("");
        Vietnamese::new(Method::Vni).type_keys(&mut editor, keys);
        editor.unmark_text();
        assert_eq!(editor.text(), word, "{keys}");
    }
}

#[test]
fn each_key_replaces_the_marked_word() {
    let mut editor = field("");
    let mut ime = Vietnamese::new(Method::Telex);
    let mut seen = Vec::new();
    for key in "tieengs".chars() {
        ime.key(&mut editor, key);
        assert_eq!(editor.marked_range(), Some(0..editor.text().len()));
        assert_eq!(editor.selection().head, editor.text().len());
        seen.push(editor.text().to_owned());
    }
    assert_eq!(seen, ["t", "ti", "tie", "tiê", "tiên", "tiêng", "tiếng"]);
    // Nothing is in the undo history until the word is committed.
    assert!(!editor.can_undo());
    ime.key(&mut editor, ' ');
    assert_eq!(editor.text(), "tiếng ");
    assert!(editor.can_undo());
}

#[test]
fn composing_in_the_middle_of_text() {
    let mut editor = field("Xin  bạn");
    editor.place_caret(4, false);
    let mut ime = Vietnamese::new(Method::Telex);
    ime.type_keys(&mut editor, "chaof");
    assert_eq!(editor.text(), "Xin chào bạn");
    assert_eq!(editor.marked_range(), Some(4.."Xin chào".len()));
    ime.key(&mut editor, ' ');
    assert_eq!(editor.text(), "Xin chào  bạn");
    assert_eq!(editor.selection().head, "Xin chào ".len());
}

#[test]
fn composing_replaces_the_selection_and_cancel_restores_it() {
    let mut editor = field("users");
    editor.select_all();
    Vietnamese::new(Method::Telex).type_keys(&mut editor, "Vieet");
    assert_eq!(editor.text(), "Viêt");
    assert_eq!(editor.marked_range(), Some(0.."Viêt".len()));
    editor.cancel_composition();
    assert_eq!(editor.text(), "users");
    assert_eq!(editor.selected_text(), "users");
    assert!(!editor.can_undo());
}

#[test]
fn a_committed_composition_over_a_selection_undoes_in_one_step() {
    let mut editor = field("users");
    editor.select_all();
    let mut ime = Vietnamese::new(Method::Telex);
    ime.type_keys(&mut editor, "Vieetj ");
    assert_eq!(editor.text(), "Việt ");
    editor.undo();
    assert_eq!(editor.text(), "Việt");
    editor.undo();
    assert_eq!(editor.text(), "users");
    assert_eq!(editor.selected_text(), "users");
}

#[test]
fn a_typed_sentence_undoes_at_once_and_redoes() {
    let mut editor = field("");
    Vietnamese::new(Method::Telex).type_keys(&mut editor, "tieengs Vieetj ");
    assert_eq!(editor.text(), "tiếng Việt ");
    assert!(editor.undo());
    assert_eq!(editor.text(), "");
    assert!(editor.redo());
    assert_eq!(editor.text(), "tiếng Việt ");
}

#[test]
fn moving_the_caret_commits_the_composition() {
    let mut editor = field("");
    let mut clipboard = MemoryClipboard::default();
    Vietnamese::new(Method::Telex).type_keys(&mut editor, "vieetj");
    editor.handle_key(Key::Left, Modifiers::default(), &mut clipboard);
    assert!(!editor.is_composing());
    assert_eq!(editor.text(), "việt");
    assert_eq!(editor.selection().head, "việ".len());
    // Typing on is plain typing now, not the old composition.
    editor.insert_text("x");
    assert_eq!(editor.text(), "việxt");
}

#[test]
fn empty_marked_text_ends_the_composition() {
    // Backspacing through a composition: the input method sends shorter marked text, then none.
    let mut editor = field("a ");
    editor.replace_and_mark_text_in_range(None, "v", None);
    editor.replace_and_mark_text_in_range(None, "vi", None);
    editor.replace_and_mark_text_in_range(None, "v", None);
    editor.replace_and_mark_text_in_range(None, "", None);
    assert_eq!(editor.text(), "a ");
    assert!(!editor.is_composing());
    assert!(!editor.can_undo());
}

#[test]
fn committing_with_insert_text_replaces_the_marked_text() {
    // Some input methods commit by inserting the final word over the marked text instead of unmarking it.
    let mut editor = field("");
    editor.replace_and_mark_text_in_range(None, "tiê", None);
    editor.replace_text_in_range(None, "tiếng");
    assert_eq!(editor.text(), "tiếng");
    assert!(!editor.is_composing());
    assert_eq!(editor.selection().head, "tiếng".len());
}

#[test]
fn marked_text_can_place_the_caret_inside_it() {
    let mut editor = field("");
    editor.replace_and_mark_text_in_range(None, "tiếng", Some(2..2));
    assert_eq!(editor.selection().head, 2);
    assert_eq!(editor.marked_range(), Some(0.."tiếng".len()));
}

#[test]
fn a_rewriting_input_method_types_the_same_and_undoes_by_word() {
    let mut editor = field("");
    let mut ime = Rewriting {
        method: Method::Telex,
        syllable: Syllable::default(),
        shown: String::new(),
    };
    ime.type_keys(&mut editor, "tieengs Vieetj");
    assert_eq!(editor.text(), "tiếng Việt");
    assert!(!editor.is_composing());
    editor.undo();
    assert_eq!(editor.text(), "");
}

#[test]
fn read_only_and_disabled_fields_take_no_composition() {
    for options in [
        Options {
            read_only: true,
            ..Options::default()
        },
        Options {
            enabled: false,
            ..Options::default()
        },
    ] {
        let mut editor = Editor::new("abc", options);
        assert!(!editor.replace_and_mark_text_in_range(None, "tiê", None));
        assert!(!editor.replace_text_in_range(None, "x"));
        assert_eq!(editor.text(), "abc");
    }
}

#[test]
fn utf16_offsets_round_trip() {
    let editor = Editor::new(
        "tiếng 👍🏽 Việt",
        Options {
            platform: Platform::Mac,
            ..Options::default()
        },
    );
    let text = editor.text();
    let thumb = text.find('👍').unwrap();
    assert_eq!(editor.offset_to_utf16(thumb), 6);
    assert_eq!(editor.offset_to_utf16(text.len()), 15);
    assert_eq!(editor.offset_from_utf16(6), thumb);
    // Inside a surrogate pair rounds down.
    assert_eq!(editor.offset_from_utf16(7), thumb);
    assert_eq!(
        editor.range_from_utf16(editor.range_to_utf16(0..thumb)),
        0..thumb
    );
    assert_eq!(editor.text_for_range(0.."tiếng".len()), Some("tiếng"));
    assert_eq!(editor.text_for_range(0..3), None);
}
