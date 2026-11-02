#!/usr/bin/env python3
"""Keeps Datagrok's update feeds (appcast/*.xml) in step with its releases.

appcast/releases.json is the record: one entry per published release, with each target's archive, size and Ed25519
signature. The XML feeds are generated from it and never edited by hand, so a bad release is pulled by deleting its
entry and running `render`.

  appcast.py add --metadata DIR --version 1.4.0 --build 412 --tag v1.4.0 --repo owner/name [--prerelease] [--notes FILE]
  appcast.py render

`add` reads the appcast-<target>.json files the release workflow attaches to a release. Python 3.9+, standard library only.
"""

import argparse
import datetime
import email.utils
import html
import json
import os
import pathlib
import sys

# DATAGROK_APPCAST_DIR lets scripts/test-update.sh build a throwaway feed.
ROOT = pathlib.Path(os.environ.get("DATAGROK_APPCAST_DIR") or pathlib.Path(__file__).resolve().parent.parent / "appcast")
RECORD = ROOT / "releases.json"
# Releases kept in each feed: enough for "what's new since your version", small enough to fetch daily.
KEEP = 10
TARGETS = ("macos-arm64", "macos-x64")


def load():
    return json.loads(RECORD.read_text()) if RECORD.exists() else []


def save(releases):
    ROOT.mkdir(exist_ok=True)
    RECORD.write_text(json.dumps(releases, indent=2) + "\n")


def add(args):
    releases = load()
    if any(r["build"] == args.build for r in releases):
        sys.exit(f"Build {args.build} is already in {RECORD.name}; every release needs a new build number.")
    if releases and args.build <= max(r["build"] for r in releases):
        sys.exit(f"Build {args.build} isn't higher than the newest one recorded; updates only go forward.")
    targets = {}
    for target in TARGETS:
        path = pathlib.Path(args.metadata) / f"appcast-{target}.json"
        if not path.exists():
            continue
        meta = json.loads(path.read_text())
        for key in ("file", "length", "edSignature", "minimumSystemVersion"):
            if not meta.get(key):
                sys.exit(f"{path} has no {key}.")
        meta["url"] = f"https://github.com/{args.repo}/releases/download/{args.tag}/{meta.pop('file')}"
        targets[target] = meta
    if not targets:
        sys.exit(f"No appcast-<target>.json in {args.metadata}.")
    releases.append(
        {
            "version": args.version,
            "build": args.build,
            "channel": "beta" if args.prerelease else "stable",
            "date": email.utils.format_datetime(datetime.datetime.now(datetime.timezone.utc)),
            "notesUrl": f"https://github.com/{args.repo}/releases/tag/{args.tag}",
            "notes": pathlib.Path(args.notes).read_text() if args.notes else "",
            "targets": targets,
        }
    )
    save(releases)
    render(args)


def item(release, target):
    archive = release["targets"][target]
    channel = "\n      <sparkle:channel>beta</sparkle:channel>" if release["channel"] == "beta" else ""
    notes = ""
    if release["notes"].strip():
        notes = f'\n      <description sparkle:format="markdown"><![CDATA[{release["notes"].replace("]]>", "]]]]><![CDATA[>")}]]></description>'
    return f"""    <item>
      <title>Datagrok {html.escape(release["version"])}</title>
      <pubDate>{release["date"]}</pubDate>
      <sparkle:version>{release["build"]}</sparkle:version>
      <sparkle:shortVersionString>{html.escape(release["version"])}</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>{html.escape(archive["minimumSystemVersion"])}</sparkle:minimumSystemVersion>
      <sparkle:fullReleaseNotesLink>{html.escape(release["notesUrl"])}</sparkle:fullReleaseNotesLink>{channel}{notes}
      <enclosure url="{html.escape(archive["url"])}" length="{archive["length"]}" type="application/octet-stream" sparkle:edSignature="{html.escape(archive["edSignature"])}"/>
    </item>"""


def render(_args=None):
    releases = sorted(load(), key=lambda r: r["build"], reverse=True)
    for target in TARGETS:
        items = [item(r, target) for r in releases if target in r["targets"]][:KEEP]
        feed = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">\n'
            "  <channel>\n"
            "    <title>Datagrok</title>\n"
            + "".join(i + "\n" for i in items)
            + "  </channel>\n</rss>\n"
        )
        (ROOT / f"{target}.xml").write_text(feed)
        print(f"appcast/{target}.xml: {len(items)} releases")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    add_parser = commands.add_parser("add", help="record a published release and render the feeds")
    add_parser.add_argument("--metadata", required=True, help="folder with the release's appcast-<target>.json files")
    add_parser.add_argument("--version", required=True)
    add_parser.add_argument("--build", required=True, type=int)
    add_parser.add_argument("--tag", required=True)
    add_parser.add_argument("--repo", required=True, help="owner/name")
    add_parser.add_argument("--prerelease", action="store_true")
    add_parser.add_argument("--notes", help="Markdown release notes")
    add_parser.set_defaults(run=add)
    commands.add_parser("render", help="regenerate the feeds from releases.json").set_defaults(run=render)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
