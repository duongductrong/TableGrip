//! `tg-imgdiff --reference DIR --actual DIR [--out report.html] [--delta-e 2] [--max-diff 0.5] [--text-px 3]`
//!
//! For every `<actual>/<theme>/<name>.png`, compares it with `<reference>/<theme>/<name>.png` (regions from the
//! `.json` beside it), writes `<name>.diff.png` next to the capture and an HTML page showing reference, capture and
//! diff side by side. Exits with 1 when any specimen is outside the thresholds. `ui-kit/scripts/imgdiff.sh` runs it.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use tg_imgdiff::{Comparison, Meta, Thresholds, compare, load};

struct Row {
    theme: String,
    name: String,
    reference: PathBuf,
    actual: PathBuf,
    diff: PathBuf,
    result: Result<Comparison, String>,
}

fn main() {
    let mut reference = None;
    let mut actual = None;
    let mut out = None;
    let mut th = Thresholds::default();
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        let mut value = || it.next().unwrap_or_else(|| panic!("{arg} needs a value"));
        match arg.as_str() {
            "--reference" => reference = Some(PathBuf::from(value())),
            "--actual" => actual = Some(PathBuf::from(value())),
            "--out" => out = Some(PathBuf::from(value())),
            "--delta-e" => th.delta_e = value().parse().unwrap(),
            "--max-diff" => th.max_diff_percent = value().parse().unwrap(),
            "--text-px" => th.text_px = value().parse().unwrap(),
            "--text-delta-e" => th.text_delta_e = value().parse().unwrap(),
            other => panic!("unknown argument {other}; see the doc comment in main.rs"),
        }
    }
    let reference = reference.expect("--reference DIR");
    let actual = actual.expect("--actual DIR");
    let out = out.unwrap_or_else(|| actual.join("report.html"));

    let mut rows = Vec::new();
    for theme_dir in sorted_dirs(&actual) {
        let theme = theme_dir
            .file_name()
            .unwrap()
            .to_string_lossy()
            .into_owned();
        for png in sorted_pngs(&theme_dir) {
            let name = png.file_stem().unwrap().to_string_lossy().into_owned();
            let ref_png = reference.join(&theme).join(format!("{name}.png"));
            let ref_json = ref_png.with_extension("json");
            let diff = theme_dir.join(format!("{name}.diff.png"));
            let result = (|| {
                let r = load(&ref_png)?;
                let a = load(&png)?;
                let meta: Meta = match std::fs::read_to_string(&ref_json) {
                    Ok(text) => serde_json::from_str(&text).map_err(|e| e.to_string())?,
                    Err(_) => Meta::default(),
                };
                let c = compare(&r, &a, &meta, &th);
                c.diff_image.save(&diff).map_err(|e| e.to_string())?;
                Ok(c)
            })();
            rows.push(Row {
                theme: theme.clone(),
                name,
                reference: ref_png,
                actual: png,
                diff,
                result,
            });
        }
    }
    assert!(!rows.is_empty(), "no captures under {}", actual.display());

    let mut failed = 0;
    for row in &rows {
        let line = match &row.result {
            Ok(c) if c.passed => format!("ok    {}/{}  {}", row.theme, row.name, summary(c)),
            Ok(c) => {
                failed += 1;
                format!("FAIL  {}/{}  {}", row.theme, row.name, summary(c))
            }
            Err(e) => {
                failed += 1;
                format!("FAIL  {}/{}  {e}", row.theme, row.name)
            }
        };
        println!("{line}");
        if let Ok(c) = &row.result {
            for r in c.regions.iter().filter(|r| !r.passed) {
                println!("        {} {}: {}", r.kind, r.tag, r.detail);
            }
        }
    }
    std::fs::write(&out, report(&rows, &out, &th)).unwrap();
    println!(
        "{} of {} specimens within thresholds; report: {}",
        rows.len() - failed,
        rows.len(),
        out.display()
    );
    if failed > 0 {
        std::process::exit(1);
    }
}

fn summary(c: &Comparison) -> String {
    if let Some(m) = &c.size_mismatch {
        return m.clone();
    }
    let text_ok = c.regions.iter().filter(|r| r.passed).count();
    format!(
        "{:.3}% pixels differ (max ΔE {:.1}), regions {}/{}",
        c.diff_percent,
        c.max_delta_e,
        text_ok,
        c.regions.len()
    )
}

fn sorted_dirs(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(dir)
        .unwrap_or_else(|e| panic!("{}: {e}", dir.display()))
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.is_dir())
        .collect();
    v.sort();
    v
}

fn sorted_pngs(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(dir)
        .unwrap()
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            let name = p.file_name().unwrap().to_string_lossy();
            name.ends_with(".png") && !name.ends_with(".diff.png") && !name.ends_with(".raw.png")
        })
        .collect();
    v.sort();
    v
}

/// [path] relative to the report's folder, for `<img src>`.
fn rel(path: &Path, report: &Path) -> String {
    let base = report.parent().unwrap().canonicalize().unwrap();
    let path = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());
    let (mut b, mut p) = (base.components().peekable(), path.components().peekable());
    while let (Some(x), Some(y)) = (b.peek(), p.peek()) {
        if x != y {
            break;
        }
        b.next();
        p.next();
    }
    let ups = b.count();
    let mut out: Vec<String> = std::iter::repeat_n("..".to_owned(), ups).collect();
    out.extend(p.map(|c| c.as_os_str().to_string_lossy().into_owned()));
    out.join("/")
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn report(rows: &[Row], out: &Path, th: &Thresholds) -> String {
    let mut html = String::from(
        "<!doctype html><meta charset=utf-8><title>UI kit image diff</title><style>\
         body{font:13px -apple-system,system-ui,sans-serif;margin:24px;background:#f7f8fa;color:#1e1f22}\
         h2{margin:28px 0 8px;font-size:15px}.ok{color:#2e7d32}.fail{color:#c62828}\
         .row{display:flex;gap:12px;flex-wrap:wrap;align-items:flex-start}\
         figure{margin:0}figcaption{color:#6c707e;margin-bottom:4px}\
         img{image-rendering:pixelated;border:1px solid #d0d3d9;max-width:100%;width:calc(var(--w)*1px)}\
         table{border-collapse:collapse;margin-top:8px}td{padding:2px 10px 2px 0;vertical-align:top}\
         </style>",
    );
    let failed = rows
        .iter()
        .filter(|r| !matches!(&r.result, Ok(c) if c.passed))
        .count();
    let _ = write!(
        html,
        "<h1>UI kit image diff</h1><p>{} of {} specimens within thresholds. A plain pixel differs above ΔE₀₀ {}; a \
         specimen fails above {}% differing pixels. Text regions compare ink: box within {} px, colour within ΔE \
         {}. In the diff, red marks differing pixels, blue outlines text regions.</p>",
        rows.len() - failed,
        rows.len(),
        th.delta_e,
        th.max_diff_percent,
        th.text_px,
        th.text_delta_e
    );
    for row in rows {
        let (class, status) = match &row.result {
            Ok(c) if c.passed => ("ok", format!("ok — {}", summary(c))),
            Ok(c) => ("fail", format!("FAIL — {}", summary(c))),
            Err(e) => ("fail", format!("FAIL — {}", esc(e))),
        };
        let width = row
            .result
            .as_ref()
            .map(|c| c.diff_image.width() / 2)
            .unwrap_or(320);
        let _ = write!(
            html,
            "<h2>{} / {} <span class={class}>{status}</span></h2><div class=row style='--w:{width}'>",
            esc(&row.theme),
            esc(&row.name)
        );
        for (label, path) in [
            ("Kotlin reference", &row.reference),
            ("Rust capture", &row.actual),
            ("Diff", &row.diff),
        ] {
            let _ = write!(
                html,
                "<figure><figcaption>{label}</figcaption><img src='{}'></figure>",
                esc(&rel(path, out))
            );
        }
        html.push_str("</div>");
        if let Ok(c) = &row.result
            && !c.regions.is_empty()
        {
            html.push_str("<table>");
            for r in &c.regions {
                let _ = write!(
                    html,
                    "<tr><td class={}>{}</td><td>{}</td><td>{}</td></tr>",
                    if r.passed { "ok" } else { "fail" },
                    if r.passed { "ok" } else { "FAIL" },
                    esc(&r.tag),
                    esc(&r.detail)
                );
            }
            html.push_str("</table>");
        }
    }
    html
}
