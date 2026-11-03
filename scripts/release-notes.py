#!/usr/bin/env python3
"""Writes TableGrip's release notes from the Conventional Commits since the previous release.

  release-notes.py github --tag v1.4.0 --repo owner/name --build 412 [--dist DIR] [--windows-signed]
  release-notes.py in-app NOTES.md

`github` prints the body of the GitHub release: what changed (Breaking Changes, Features, Bug Fixes, Maintenance),
the contributors, then the downloads and how to verify them, folded away. `in-app` prints the part an update
dialog shows: everything above the end marker except Maintenance and Contributors, without links. The maintainer
may edit the draft before publishing; whatever stays above the marker is what users read.

A stable release is compared with the previous stable one, so it lists everything its betas did; a beta with the
release just before it. Python 3.9+, standard library only.
"""

import argparse
import hashlib
import pathlib
import re
import subprocess
import sys

END = "<!-- end of release notes -->"
SECTIONS = (
    ("breaking", "⚠️ Breaking Changes"),
    ("feat", "✨ Features"),
    ("fix", "🐛 Bug Fixes"),
    ("other", "🧰 Maintenance"),
)
# Shown on GitHub only: users updating the app don't need them.
GITHUB_ONLY = ("Maintenance", "Contributors")
SUBJECT = re.compile(r"^(?P<type>[a-z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?:\s*(?P<text>.+)$")
# Commits the release pipeline makes itself.
NOISE = re.compile(r"^chore: (update appcast|bump version|release v)")
PLATFORMS = (
    ("macos-arm64.dmg", "macOS, Apple silicon"),
    ("macos-x64.dmg", "macOS, Intel"),
    ("windows-x64.msi", "Windows x64"),
    ("linux-x64.deb", "Linux x64"),
)


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def previous_tag(tag):
    """The release before [tag]: the previous stable one for a stable release, the previous one of any kind for a beta."""
    exclude = [] if "-" in tag else ["--exclude", "v*-*"]
    try:
        return git("describe", "--tags", "--abbrev=0", "--match", "v*", *exclude, f"{tag}^").strip()
    except subprocess.CalledProcessError:
        return None  # The first release.


def commits(since, until):
    log = git("log", "--no-merges", "--format=%h%x1f%an%x1f%s%x1f%b%x1e", f"{since}..{until}" if since else until)
    for record in log.split("\x1e"):
        if record.strip():
            sha, author, subject, body = record.strip("\n").split("\x1f")
            yield sha, author, subject.strip(), body


def classify(subject, body):
    """(section, text) for a commit, or None for one release notes leave out."""
    match = SUBJECT.match(subject)
    if not match or NOISE.match(subject):
        return None
    text = match["text"].rstrip(".")
    text = text[0].upper() + text[1:]
    if match["scope"]:
        text = f"**{match['scope']}:** {text}"
    if match["bang"] or re.search(r"^BREAKING[ -]CHANGE:", body, re.MULTILINE):
        return "breaking", text
    if match["type"] in ("feat", "fix"):
        return match["type"], text
    return "other", f"{match['type']}: {text}"


def github(args):
    since = previous_tag(args.tag)
    grouped = {key: [] for key, _ in SECTIONS}
    authors = set()
    for sha, author, subject, body in commits(since, args.until or args.tag):
        entry = classify(subject, body)
        if entry is None:
            continue
        if "[bot]" not in author:
            authors.add(author)
        section, text = entry
        # GitHub links "#12" and a bare commit hash by itself.
        grouped[section].append(f"- {text} ({sha})")

    out = []
    for key, title in SECTIONS:
        if grouped[key]:
            out += [f"### {title}", *grouped[key], ""]
    if not any(grouped.values()):
        out += ["### Changes", "- Maintenance release.", ""]
    if authors:
        out += ["### Contributors", ", ".join(sorted(authors, key=str.lower)), ""]
    base = f"https://github.com/{args.repo}"
    out.append(f"**Full Changelog**: {base}/compare/{since}...{args.tag}" if since else f"**Full Changelog**: {base}/commits/{args.tag}")
    out += ["", END, "", "<details>", "<summary>Downloads and verification</summary>", ""]

    out += ["| Platform | File | SHA-256 |", "|---|---|---|"]
    files = sorted(pathlib.Path(args.dist).iterdir()) if args.dist else []
    for suffix, platform in PLATFORMS:
        for path in files:
            if path.name.endswith(suffix):
                out.append(f"| {platform} | `{path.name}` | `{hashlib.sha256(path.read_bytes()).hexdigest()}` |")
    out += [
        "",
        "- macOS: signed with a Developer ID and notarized by Apple.",
        "- Windows: Authenticode-signed." if args.windows_signed else
        "- Windows: not Authenticode-signed yet, so SmartScreen warns on the first install. Updates from inside the app"
        " are still checked against TableGrip's Ed25519 key.",
        f"- Build {args.build}. Check a file with `gh attestation verify <file> --repo {args.repo}`.",
        "",
        "</details>",
    ]
    print("\n".join(out))


def in_app(args):
    notes = pathlib.Path(args.notes).read_text().split(END)[0]
    out, keep = [], True
    for line in notes.splitlines():
        if line.startswith("### "):
            # By name, so a heading edited in the draft (its emoji dropped, say) still matches.
            keep = not any(name in line for name in GITHUB_ONLY)
        elif line.startswith("**Full Changelog**"):
            keep = False
        if keep:
            # Commit hashes and PR numbers mean nothing in an update dialog.
            out.append(re.sub(r" \((?:#\d+|[0-9a-f]{7,40})\)", "", line))
    print("\n".join(out).strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    github_parser = commands.add_parser("github", help="the GitHub release body")
    github_parser.add_argument("--tag", required=True)
    github_parser.add_argument("--repo", required=True, help="owner/name")
    github_parser.add_argument("--build", required=True, type=int)
    github_parser.add_argument("--dist", help="folder with the release's archives, for their checksums")
    github_parser.add_argument("--windows-signed", action="store_true")
    github_parser.add_argument("--until", help="list commits up to this revision instead of the tag (previews)")
    github_parser.set_defaults(run=github)
    in_app_parser = commands.add_parser("in-app", help="the part of a release body update dialogs show")
    in_app_parser.add_argument("notes")
    in_app_parser.set_defaults(run=in_app)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
