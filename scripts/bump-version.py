#!/usr/bin/env python3
"""Sets tablegrip.version in gradle.properties to the next release's version and prints what it did.

  bump-version.py patch|minor|major [--channel stable|beta]
  bump-version.py --version 1.4.0

Prints `version=`, `previous_version=` and `channel=` lines, for $GITHUB_OUTPUT.

  stable, current stable -> the usual bump: 1.3.2 + minor -> 1.4.0
  stable, current beta   -> the beta's base, unless the bump outranks it: 1.4.0-beta.2 + patch|minor -> 1.4.0,
                            + major -> 2.0.0 (2.0.0-beta.1 + major -> 2.0.0)
  beta,   current stable -> the bumped base, then -beta.N: 1.3.2 + minor -> 1.4.0-beta.1
  beta,   current beta   -> the same base rule, then the next -beta.N: 1.4.0-beta.1 + minor -> 1.4.0-beta.2

N is one more than the highest v<base>-beta.N tag, so the history must have its tags (not a shallow clone).
`--version` sets an exact version instead, such as the first release's. Python 3.9+, standard library only.
"""

import argparse
import pathlib
import re
import subprocess
import sys

PROPERTIES = pathlib.Path("gradle.properties")
KEY = re.compile(r"^tablegrip\.version=(?P<version>.*)$", re.MULTILINE)
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$")


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()


def next_beta(base):
    if git("rev-parse", "--is-shallow-repository") == "true":
        sys.exit("error: shallow clone; the beta number comes from the tags (fetch with fetch-depth: 0)")
    numbers = [int(m[1]) for tag in git("tag", "--list", f"v{base}-beta.*").split()
               if (m := re.fullmatch(rf"v{re.escape(base)}-beta\.(\d+)", tag))]
    return max(numbers, default=0) + 1


def bumped(current, bump, channel):
    match = SEMVER.match(current)
    if not match:
        sys.exit(f"error: tablegrip.version={current} isn't X.Y.Z or X.Y.Z-beta.N")
    major, minor, patch = (int(part) for part in match.groups()[:3])
    beta = match[4] is not None

    # A beta's base already stands for the next release: keep it when it satisfies the bump.
    if not beta or (bump == "major" and (minor or patch)) or (bump == "minor" and patch):
        if bump == "major":
            major, minor, patch = major + 1, 0, 0
        elif bump == "minor":
            minor, patch = minor + 1, 0
        else:
            patch += 1
    base = f"{major}.{minor}.{patch}"
    return base if channel == "stable" else f"{base}-beta.{next_beta(base)}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bump", nargs="?", choices=("patch", "minor", "major"))
    parser.add_argument("--channel", choices=("stable", "beta"), default="stable")
    parser.add_argument("--version", help="an exact version instead of a bump")
    args = parser.parse_args()
    if (args.bump is None) == (args.version is None):
        parser.error("give a bump type or --version, not both")

    text = PROPERTIES.read_text()
    found = KEY.search(text)
    if not found:
        sys.exit(f"error: no tablegrip.version in {PROPERTIES}")
    current = found["version"].strip()

    if args.version:
        version = args.version.removeprefix("v")
        if not SEMVER.match(version):
            sys.exit(f"error: {version} isn't X.Y.Z or X.Y.Z-beta.N")
    else:
        version = bumped(current, args.bump, args.channel)
    if git("tag", "--list", f"v{version}"):
        sys.exit(f"error: v{version} is already released")

    PROPERTIES.write_text(text[:found.start("version")] + version + text[found.end("version"):])
    print(f"version={version}")
    print(f"previous_version={current}")
    print(f"channel={'beta' if '-' in version else 'stable'}")


if __name__ == "__main__":
    sys.exit(main())
