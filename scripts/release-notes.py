#!/usr/bin/env python3
"""Writes TableGrip's release notes from the Conventional Commits since the previous release.

  release-notes.py changelog --version 1.4.0 --repo owner/name [--until REV] [--file CHANGELOG.md]
  release-notes.py github --tag v1.4.0 --repo owner/name --build 412 [--dist DIR] [--windows-signed]
  release-notes.py in-app NOTES.md

`changelog` adds the version's entry to CHANGELOG.md: what changed (Breaking Changes, Features, Bug Fixes,
Maintenance) and the contributors. The release PR carries it, so the notes are reviewed and edited there. `github`
prints the body of the GitHub release: that entry, then the downloads and how to verify them, folded away. `in-app`
prints the part an update dialog shows: everything above the end marker except Maintenance and Contributors, without
links.

A stable release is compared with the previous stable one, so it lists everything its betas did; a beta with the
release just before it. Python 3.9+, standard library only.
"""

import argparse
import datetime
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
ENTRY = re.compile(r"^## \[(?P<version>[^\]]+)\]")
HEADER = """# Changelog

All notable changes to TableGrip are listed here, newest first. Each entry is written from the Conventional Commits
of its release and can be edited in the release PR before it is merged.
"""
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


def contributor(sha, author, repo):
    """A Markdown link to the GitHub account behind [sha], or the git author name when it can't be looked up."""
    try:
        login = subprocess.run(["gh", "api", f"repos/{repo}/commits/{sha}", "--jq", ".author.login // empty"],
                               check=True, capture_output=True, text=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        login = ""
    return f"[@{login}](https://github.com/{login})" if login else author


def previous_tag(version, rev):
    """The release before [version], looking back from [rev]: the previous stable one for a stable release, the
    previous one of any kind for a beta."""
    exclude = [] if "-" in version else ["--exclude", "v*-*"]
    try:
        return git("describe", "--tags", "--abbrev=0", "--match", "v*", *exclude, rev).strip()
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


def what_changed(version, until, repo):
    since = previous_tag(version, until)
    grouped = {key: [] for key, _ in SECTIONS}
    authors = {}  # git author name -> its contributor link, looked up once per author
    for sha, author, subject, body in commits(since, until):
        entry = classify(subject, body)
        if entry is None:
            continue
        if "[bot]" not in author and author not in authors:
            authors[author] = contributor(sha, author, repo)
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
        out += ["### Contributors", ", ".join(sorted(set(authors.values()), key=str.lower)), ""]
    base = f"https://github.com/{repo}"
    tag = f"v{version}"
    out.append(f"**Full Changelog**: {base}/compare/{since}...{tag}" if since else f"**Full Changelog**: {base}/commits/{tag}")
    return out


def split_entries(text):
    """(preamble, [(version, entry lines)]) of a Keep a Changelog file."""
    preamble, entries = [], []
    for line in text.splitlines():
        heading = ENTRY.match(line)
        if heading:
            entries.append((heading["version"], [line]))
        elif entries:
            entries[-1][1].append(line)
        else:
            preamble.append(line)
    return preamble, entries


def trimmed(lines):
    while lines and not lines[-1].strip():
        lines = lines[:-1]
    return lines


def changelog(args):
    version = args.version.removeprefix("v")
    path = pathlib.Path(args.file)
    preamble, entries = split_entries(path.read_text()) if path.exists() else (HEADER.splitlines(), [])
    date = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    entry = [f"## [{version}] - {date}", "", *what_changed(version, args.until, args.repo)]
    base = version.split("-")[0]
    # A stable release lists everything its betas did, so their own entries go. Betas of another base stay.
    kept = [(v, lines) for v, lines in entries if v != version and not ("-" not in version and v.startswith(f"{base}-"))]
    out = trimmed(preamble) + [""] + entry
    for _, lines in kept:
        out += [""] + trimmed(lines)
    path.write_text("\n".join(out) + "\n")


def github(args):
    version = args.tag.removeprefix("v")
    _, entries = split_entries(pathlib.Path(args.changelog).read_text())
    found = next((lines for v, lines in entries if v == version), None)
    if found is None:
        sys.exit(f"error: {args.changelog} has no entry for {version}")
    out = trimmed(found[1:])
    while out and not out[0].strip():
        out = out[1:]
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
    changelog_parser = commands.add_parser("changelog", help="add the version's entry to CHANGELOG.md")
    changelog_parser.add_argument("--version", required=True)
    changelog_parser.add_argument("--repo", required=True, help="owner/name")
    changelog_parser.add_argument("--until", default="HEAD", help="list commits up to this revision")
    changelog_parser.add_argument("--file", default="CHANGELOG.md")
    changelog_parser.set_defaults(run=changelog)
    github_parser = commands.add_parser("github", help="the GitHub release body")
    github_parser.add_argument("--tag", required=True)
    github_parser.add_argument("--repo", required=True, help="owner/name")
    github_parser.add_argument("--build", required=True, type=int)
    github_parser.add_argument("--dist", help="folder with the release's archives, for their checksums")
    github_parser.add_argument("--windows-signed", action="store_true")
    github_parser.add_argument("--changelog", default="CHANGELOG.md")
    github_parser.set_defaults(run=github)
    in_app_parser = commands.add_parser("in-app", help="the part of a release body update dialogs show")
    in_app_parser.add_argument("notes")
    in_app_parser.set_defaults(run=in_app)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
