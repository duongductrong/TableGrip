//! A small XML tree for the importers' files (IDE data sources, property lists): elements, attributes and text, no
//! DTDs or external entities.

use quick_xml::events::Event;

#[derive(Clone, Debug, Default)]
pub struct Element {
    pub name: String,
    pub attributes: Vec<(String, String)>,
    pub children: Vec<Node>,
}

#[derive(Clone, Debug)]
pub enum Node {
    Element(Element),
    Text(String),
}

impl Element {
    pub fn attribute(&self, name: &str) -> String {
        self.attributes
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    }

    pub fn elements(&self) -> impl Iterator<Item = &Element> {
        self.children.iter().filter_map(|n| match n {
            Node::Element(e) => Some(e),
            Node::Text(_) => None,
        })
    }

    /// Every descendant named `tag`, in document order (`getElementsByTagName`).
    pub fn descendants<'a>(&'a self, tag: &str, out: &mut Vec<&'a Element>) {
        for e in self.elements() {
            if e.name == tag {
                out.push(e);
            }
            e.descendants(tag, out);
        }
    }

    pub fn all(&self, tag: &str) -> Vec<&Element> {
        let mut out = Vec::new();
        self.descendants(tag, &mut out);
        out
    }

    /// All the text inside, as `textContent`.
    pub fn text(&self) -> String {
        let mut out = String::new();
        for n in &self.children {
            match n {
                Node::Text(t) => out.push_str(t),
                Node::Element(e) => out.push_str(&e.text()),
            }
        }
        out
    }

    /// A direct child's trimmed text.
    pub fn child(&self, tag: &str) -> Option<String> {
        self.elements()
            .find(|e| e.name == tag)
            .map(|e| e.text().trim().to_owned())
    }
}

/// The document element of `text`.
pub fn parse(text: &str) -> Result<Element, String> {
    let mut reader = quick_xml::Reader::from_str(text);
    reader.config_mut().trim_text(false);
    let mut stack: Vec<Element> = vec![Element::default()];
    loop {
        match reader.read_event().map_err(|e| e.to_string())? {
            Event::Start(e) => stack.push(element(&e)),
            Event::Empty(e) => {
                let el = element(&e);
                stack.last_mut().unwrap().children.push(Node::Element(el));
            }
            Event::End(_) => {
                let done = stack.pop().ok_or("Unbalanced XML")?;
                stack
                    .last_mut()
                    .ok_or("Unbalanced XML")?
                    .children
                    .push(Node::Element(done));
            }
            Event::Text(t) => {
                let t = t.decode().map_err(|e| e.to_string())?.into_owned();
                stack.last_mut().unwrap().children.push(Node::Text(t));
            }
            Event::CData(t) => {
                let t = String::from_utf8_lossy(&t).into_owned();
                stack.last_mut().unwrap().children.push(Node::Text(t));
            }
            Event::GeneralRef(r) => {
                let name = String::from_utf8_lossy(&r).into_owned();
                let t = match r.resolve_char_ref().ok().flatten() {
                    Some(c) => c.to_string(),
                    None => quick_xml::escape::resolve_predefined_entity(&name)
                        .unwrap_or("")
                        .to_owned(),
                };
                stack.last_mut().unwrap().children.push(Node::Text(t));
            }
            Event::Eof => break,
            _ => {}
        }
    }
    let root = stack.pop().ok_or("Empty XML")?;
    root.elements()
        .next()
        .cloned()
        .ok_or_else(|| "No document element".to_owned())
}

fn element(e: &quick_xml::events::BytesStart) -> Element {
    Element {
        name: String::from_utf8_lossy(e.name().as_ref()).into_owned(),
        attributes: e
            .attributes()
            .flatten()
            .map(|a| {
                let raw = String::from_utf8_lossy(&a.value).into_owned();
                let value =
                    quick_xml::escape::unescape(&raw).map_or(raw.clone(), |v| v.into_owned());
                (String::from_utf8_lossy(a.key.as_ref()).into_owned(), value)
            })
            .collect(),
        children: Vec::new(),
    }
}
