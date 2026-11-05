#!/usr/bin/env bash
# Builds and launches TableGrip.
#
#   scripts/run.sh                 # run with your normal profile (~/.tablegrip)
#   scripts/run.sh --fresh         # run against a throwaway profile/settings/history directory
#   scripts/run.sh --home DIR      # run against the profile in DIR
#   scripts/run.sh -- --info       # anything after -- goes to Gradle
#
# The first run downloads the JetBrains Runtime the app needs (see README).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gradle_args=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fresh)
            home="$(mktemp -d "${TMPDIR:-/tmp}/tablegrip.XXXXXX")"
            echo "Using a throwaway profile in $home"
            gradle_args+=("-Ptablegrip.home=$home")
            ;;
        --home)
            [[ $# -ge 2 ]] || { echo "--home needs a directory" >&2; exit 2; }
            gradle_args+=("-Ptablegrip.home=$2")
            shift
            ;;
        --)
            shift
            gradle_args+=("$@")
            break
            ;;
        -h | --help)
            sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown option: $1 (see --help)" >&2
            exit 2
            ;;
    esac
    shift
done

cd "$root"
exec ./gradlew :app:run ${gradle_args[@]+"${gradle_args[@]}"}
