#!/usr/bin/env python3
"""Copies the IntelliJ icons TableGrip uses out of the pinned icons artifact into the Rust UI kit.

Only icons the app references (the `AllIconsKeys` in docs/plans/rust-ui-kit/inventory.md) are taken, and only files
whose own header says they are under the Apache 2.0 license (docs/plans/rust-ui-kit/icon-licence.md). Files are
copied byte for byte, so the header stays in each one. `manifest.json` records, per file, the key, the path in the
artifact, the artifact version and the SHA-256.

Keys name the classic icon path (`Actions.MenuSaveall` -> `actions/menu-saveall.svg`); the new-UI file Int UI draws is
found through the artifact's own `PlatformIconMappings.json`.

    python3 -I scripts/extract-icons.py            # finds the jar in the Gradle cache
    python3 -I scripts/extract-icons.py --jar PATH
"""

import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import sys
import zipfile

GROUP = "com.jetbrains.intellij.platform"
ARTIFACT = "icons"
VERSION = "262.10968.63"

# The AllIconsKeys app/src/main uses (inventory.md, "Icon / AllIconsKeys"). Left out on purpose:
# Toolwindows.ToolWindowAskAI (no Apache header; the app draws its own icons/agent*.svg instead) and any logo.
KEYS = """
General.ChevronDown General.Add General.Warning General.Error General.ChevronRight General.Filter General.Delete
General.Information General.Settings General.CloseSmall General.ArrowUp General.ArrowRight General.ArrowDown
General.Remove General.InspectionsOK General.Keyboard General.ChevronUp General.ChevronLeft General.ZoomIn
General.ZoomOut General.Reset General.Pin General.PinSelected General.InlineAdd General.HideToolWindow
General.FitContent General.ExpandComponent General.CollapseComponent General.ActualZoom
Actions.Search Actions.Refresh Actions.Copy Actions.Rollback Actions.Edit Actions.Close Actions.Commit Actions.Suspend
Actions.Preview Actions.Play_forward Actions.Play_first Actions.Play_back Actions.MenuSaveall Actions.Execute
Actions.Download Actions.Collapseall Actions.PreviewDetails Actions.MenuOpen Actions.Expandall Actions.Undo
Actions.Redo Actions.ShowCode Actions.ShowAsTree Actions.Show Actions.RunAll Actions.ReformatCode
Actions.PreviousOccurence Actions.NextOccurence Actions.Play_last Actions.PinTab Actions.ListFiles Actions.Lightning
Actions.Checked Actions.CheckOut
Nodes.DataColumn Nodes.DataTables Nodes.Console Nodes.Folder Nodes.DataSchema Nodes.FieldPK Nodes.Padlock
Nodes.Favorite Nodes.Bookmark
Vcs.History FileTypes.Text ToolbarDecorator.Import FileTypes.Diagram Diff.ArrowLeftRight Graph.Layout
ObjectBrowser.Sorted
""".split()

APACHE = re.compile(rb"^\s*<!--[^>]*Apache 2\.0 license")


def lower_first(s):
    return s[:1].lower() + s[1:]


def norm(path):
    """Case, '-' and '_' don't matter when matching a key to a file name."""
    return re.sub(r"[-_]", "", path.lower())


def find_jar(explicit):
    if explicit:
        return explicit
    pattern = os.path.expanduser(
        f"~/.gradle/caches/modules-2/files-2.1/{GROUP}/{ARTIFACT}/{VERSION}/*/{ARTIFACT}-{VERSION}.jar"
    )
    found = sorted(glob.glob(pattern))
    if not found:
        sys.exit(f"{ARTIFACT}-{VERSION}.jar not in the Gradle cache; build the app once (./gradlew :app:compileKotlin) or pass --jar")
    return found[0]


def flatten(mapping, prefix=""):
    """PlatformIconMappings.json as (new path, [classic paths])."""
    for name, value in mapping.items():
        path = f"{prefix}/{name}" if prefix else name
        if isinstance(value, dict):
            yield from flatten(value, path)
        else:
            yield path, value if isinstance(value, list) else [value]


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", help=f"path to {ARTIFACT}-{VERSION}.jar")
    parser.add_argument("--out", default=os.path.join(here, "..", "crates", "tg-ui", "assets", "icons", "intellij"))
    args = parser.parse_args()

    jar_path = find_jar(args.jar)
    with open(jar_path, "rb") as f:
        jar_sha = hashlib.sha256(f.read()).hexdigest()
    jar = zipfile.ZipFile(jar_path)
    names = [n for n in jar.namelist() if n.endswith(".svg")]
    by_norm = {}
    for n in names:
        by_norm.setdefault(norm(n), []).append(n)
    classic_to_new = {}
    for new, classics in flatten(json.loads(jar.read("PlatformIconMappings.json"))):
        for c in classics:
            classic_to_new.setdefault(c, []).append(new)

    out = os.path.abspath(args.out)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)

    entries, skipped = [], []
    for key in KEYS:
        group, name = key.split(".")
        classic_guess = f"{lower_first(group)}/{lower_first(name)}.svg"
        classic = [c for c in by_norm.get(norm(classic_guess), []) if not c.startswith("expui/")]
        classic = classic[0] if classic else classic_guess
        news = classic_to_new.get(classic, [])
        # Several new icons can map to one classic path; prefer the one in the key's own group.
        news.sort(key=lambda p: (not p.startswith(f"expui/{lower_first(group)}/"), p))
        if news:
            source = news[0]
        else:
            direct = by_norm.get(norm("expui/" + classic_guess), [])
            source = direct[0] if direct else classic
        if source not in names:
            sys.exit(f"{key}: no file for {source}")
        for variant in [source, source[:-4] + "_dark.svg"]:
            if variant not in names:
                continue
            data = jar.read(variant)
            if not APACHE.match(data):
                skipped.append(variant)
                continue
            rel = variant[len("expui/"):] if variant.startswith("expui/") else variant
            dest = os.path.join(out, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(data)
            entries.append({
                "key": key,
                "dark": variant.endswith("_dark.svg"),
                "file": rel,
                "source": variant,
                "sha256": hashlib.sha256(data).hexdigest(),
            })

    manifest = {
        "artifact": f"{GROUP}:{ARTIFACT}:{VERSION}",
        "artifactSha256": jar_sha,
        "license": "Apache-2.0 (header in each file)",
        "notice": "This software includes code from IntelliJ IDEA, Copyright (C) JetBrains s.r.o.",
        "generatedBy": "scripts/extract-icons.py",
        "icons": entries,
    }
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    keys = {e["key"] for e in entries}
    print(f"{len(entries)} files for {len(keys)}/{len(KEYS)} keys into {os.path.relpath(out)}")
    for s in skipped:
        print(f"skipped (no Apache 2.0 header): {s}")
    missing = [k for k in KEYS if k not in keys]
    if missing:
        sys.exit(f"keys without a usable file: {missing}")


if __name__ == "__main__":
    main()
