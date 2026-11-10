//! `~/.ssh/config` (`imports/SshHosts.kt`), so a tunnel can be set up by picking a host the user already has, the way
//! `ssh alias` would reach it. Follows `Include`; wildcard patterns (`Host *`) only supply defaults to the hosts below
//! them.

use super::expand_home;
use regex::Regex;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// A `Host` entry of OpenSSH's client configuration, resolved to what an SSH tunnel needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SshHost {
    pub alias: String,
    pub host_name: String,
    pub port: Option<i32>,
    pub user: Option<String>,
    pub identity_file: Option<String>,
}

struct Block {
    patterns: Vec<String>,
    options: HashMap<String, String>,
}

pub fn read(home: &Path) -> Vec<SshHost> {
    let config = home.join(".ssh/config");
    if !config.is_file() {
        return Vec::new();
    }
    let mut blocks = Vec::new();
    collect(&config, home, &mut blocks, 0);
    let wild = |p: &str| p.contains('*') || p.contains('?');
    let wildcards: Vec<&Block> = blocks
        .iter()
        .filter(|b| b.patterns.iter().any(|p| wild(p)))
        .collect();
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for block in &blocks {
        for alias in block
            .patterns
            .iter()
            .filter(|p| !wild(p) && !p.starts_with('!'))
        {
            if !seen.insert(alias.clone()) {
                continue;
            }
            // ssh uses the first value it sees for each option: the host's own block, then matching wildcards.
            let option = |name: &str| {
                block.options.get(name).cloned().or_else(|| {
                    wildcards
                        .iter()
                        .find(|w| w.patterns.iter().any(|p| matches(p, alias)))
                        .and_then(|w| w.options.get(name).cloned())
                })
            };
            out.push(SshHost {
                alias: alias.clone(),
                host_name: option("hostname")
                    .map(|h| h.replace("%h", alias))
                    .unwrap_or_else(|| alias.clone()),
                port: option("port").and_then(|p| p.parse().ok()),
                user: option("user"),
                identity_file: option("identityfile")
                    .map(|f| expand_home(f.trim_matches('"'), home)),
            });
        }
    }
    out
}

fn collect(file: &Path, home: &Path, blocks: &mut Vec<Block>, depth: usize) {
    if depth > 8 {
        return;
    }
    let text = std::fs::read_to_string(file).unwrap_or_default();
    let separator = Regex::new(r"[\s=]+").unwrap();
    let mut current: Option<usize> = None;
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut parts = separator.splitn(line, 2);
        let name = parts.next().unwrap_or("").to_lowercase();
        let value = parts.next().unwrap_or("").trim().to_owned();
        match name.as_str() {
            "host" => {
                blocks.push(Block {
                    patterns: value
                        .split_whitespace()
                        .map(|p| p.trim_matches('"').to_owned())
                        .collect(),
                    options: HashMap::new(),
                });
                current = Some(blocks.len() - 1);
            }
            // A Match block's conditions can't be evaluated here: ignore its options.
            "match" => current = None,
            "include" => {
                for pattern in value.split_whitespace() {
                    let expanded = expand_home(pattern, home);
                    let path = if Path::new(&expanded).is_absolute() {
                        PathBuf::from(expanded)
                    } else {
                        home.join(".ssh").join(expanded)
                    };
                    for p in expand(&path) {
                        collect(&p, home, blocks, depth + 1);
                    }
                }
                // Options after an Include still belong to the block before it.
            }
            _ => {
                if let Some(i) = current {
                    blocks[i].options.entry(name).or_insert(value);
                }
            }
        }
    }
}

/// A path that may contain `*` or `?` in its last segment.
fn expand(path: &Path) -> Vec<PathBuf> {
    let text = path.to_string_lossy();
    if !text.contains('*') && !text.contains('?') {
        return if path.is_file() {
            vec![path.to_owned()]
        } else {
            vec![]
        };
    }
    let (Some(dir), Some(pattern)) = (path.parent(), path.file_name()) else {
        return vec![];
    };
    let pattern = pattern.to_string_lossy().into_owned();
    let mut out: Vec<PathBuf> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            p.is_file()
                && p.file_name()
                    .is_some_and(|n| matches(&pattern, &n.to_string_lossy()))
        })
        .collect();
    out.sort();
    out
}

fn matches(pattern: &str, host: &str) -> bool {
    let expression: Vec<String> = pattern
        .split('*')
        .map(|part| {
            part.split('?')
                .map(regex::escape)
                .collect::<Vec<_>>()
                .join(".")
        })
        .collect();
    Regex::new(&format!("^{}$", expression.join(".*"))).is_ok_and(|r| r.is_match(host))
}
