#!/usr/bin/env bash
# Captures the Rust gallery in each theme and compares it with the Kotlin app's reference renders.
#
#   ui-kit/scripts/imgdiff.sh                      # Graphite Dark, Graphite Light, One Dark
#   ui-kit/scripts/imgdiff.sh --all                # every built-in theme with a reference
#   ui-kit/scripts/imgdiff.sh one-dark dracula     # these themes (reference folder names)
#   SPECIMEN=link ui-kit/scripts/imgdiff.sh        # one specimen
#
# Writes captures, diffs and report.html to ui-kit/target/imgdiff/ and exits non-zero when a specimen is outside the
# thresholds. Needs macOS (screencapture, sips) with Screen Recording allowed for the terminal. Inter and JetBrains
# Mono, which the kit doesn't bundle yet, are taken from Jewel's jar in the Gradle cache (run the Kotlin build once).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
reference="$root/conformance/reference"
out="$root/target/imgdiff"

if [[ "${1:-}" == "--all" ]]; then
  themes=()
  for dir in "$reference"/*/; do themes+=("$(basename "$dir")"); done
elif [[ $# -gt 0 ]]; then
  themes=("$@")
else
  themes=(graphite-dark graphite-light one-dark)
fi

fonts="$root/target/fonts"
if [[ ! -f "$fonts/Inter-Regular.ttf" ]]; then
  jar="$(find ~/.gradle/caches/modules-2 -name 'jewel-int-ui-standalone-*.jar' ! -name '*sources*' 2>/dev/null | head -1)"
  if [[ -n "$jar" ]]; then
    mkdir -p "$fonts"
    unzip -joq "$jar" 'fonts/inter/Inter-*.ttf' 'fonts/jetbrains-mono/JetBrainsMono-*.ttf' -x '*Italic*' -d "$fonts"
  else
    echo "Jewel's jar isn't in the Gradle cache: text falls back to system fonts and may not match." >&2
  fi
fi

cargo build -q -p tg-gallery -p tg-imgdiff
rm -rf "$out"
for theme in "${themes[@]}"; do
  json="$(ls "$reference/$theme"/*.json | head -1)"
  name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["theme"])' "$json")"
  args=(--theme "$name" --fonts "$fonts" --capture "$out/$theme")
  [[ -n "${SPECIMEN:-}" ]] && args+=(--specimen "$SPECIMEN")
  "$root/target/debug/tg-gallery" "${args[@]}"
done
"$root/target/debug/tg-imgdiff" --reference "$reference" --actual "$out" --out "$out/report.html"
