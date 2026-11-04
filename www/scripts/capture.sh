#!/usr/bin/env bash
# Takes the product screenshots used by the website, in dark and light.
#
#   www/scripts/capture.sh                 # every scene, both themes
#   www/scripts/capture.sh dark            # every scene, dark only
#   www/scripts/capture.sh light editor    # one scene, one theme
#
# Scenes: grid, filter, editor, structure, agent. Output: www/public/screenshots/<scene>-<theme>.webp
# macOS only: uses screencapture, cwebp and cliclick (brew install webp cliclick); cliclick clicks into the console
# and presses ⌘↩, so the terminal needs Accessibility permission.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
here="$root/www/scripts"
out="$root/www/public/screenshots"
home="$root/www/.demo-profile"
themes=("${1:-dark light}")
scenes=("${2:-grid filter editor structure agent}")
mkdir -p "$out"

sql='-- Revenue by customer, last 90 days
SELECT c.full_name,
       c.country,
       count(o.id)            AS orders,
       round(sum(o.total), 2) AS revenue
FROM customers c
JOIN orders o ON o.customer_id = c.id
WHERE o.status = '"'"'paid'"'"'
  AND o.created_at >= date('"'"'2026-07-01'"'"')
GROUP BY c.id
ORDER BY revenue DESC
LIMIT 50;'

stop_app() { pkill -f "tablegrip.home=$home" 2>/dev/null || true; while pgrep -f "tablegrip.home=$home" >/dev/null; do sleep 0.5; done; }

window_id() {
  swift - <<'SWIFT'
import CoreGraphics
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as! [[String: Any]]
for w in list where (w[kCGWindowOwnerName as String] as? String ?? "").hasPrefix("TableGrip") && (w[kCGWindowLayer as String] as? Int ?? 1) == 0 {
  print(w[kCGWindowNumber as String]!); break
}
SWIFT
}

# Window size (points) per scene: the hero is the whole app, feature shots are tighter so their text stays readable
# at the size the site shows them. Only the hero shows the explorer.
window_size() {
  case "$1" in
    grid) echo "1280 800" ;;
    filter) echo "1040 680" ;;
    editor) echo "1040 700" ;;
    structure) echo "1040 600" ;;
    agent) echo "1360 900" ;;
  esac
}

session() { # $1 = scene
  read -r w h <<<"$(window_size "$1")"
  python3 - "$1" "$sql" "$w" "$h" > "$home/session.json" <<'PY'
import json, sys
scene, sql, w, h = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
tabs = [
  {"type": "table", "connectionId": "demo-shop", "name": "orders"},
  {"type": "console", "connectionId": "demo-shop", "text": sql, "caret": len(sql) - 3},
  {"type": "table", "connectionId": "demo-shop", "name": "orders", "structure": True},
  {"type": "table", "connectionId": "demo-shop", "name": "orders",
   "filter": "status = 'refunded' AND total > 200"},
]
selected = {"grid": 0, "editor": 1, "structure": 2, "agent": 1, "filter": 3}[scene]
agent = scene == "agent"
print(json.dumps({"tabs": tabs, "selected": selected, "leftTool": "Explorer" if scene == "grid" else None,
                  "connectionId": "demo-shop", "agentOpen": agent, "agentChat": "demo-chat" if agent else None,
                  "agentSplit": 0.5, "window": {"x": 80, "y": 80, "width": w, "height": h}, "sidebarSplit": 0.22}))
PY
}

for theme in ${themes[@]}; do
  for scene in ${scenes[@]}; do
    stop_app
    "$here/demo-profile.sh" "$theme" "$home" >/dev/null
    session "$scene"
    (cd "$root" && ./gradlew -q :app:run "-Ptablegrip.home=$home" >/dev/null 2>&1 &)
    id=""; for _ in $(seq 1 120); do id="$(window_id)"; [[ -n "$id" ]] && break; sleep 1; done
    [[ -n "$id" ]] || { echo "TableGrip window never appeared" >&2; exit 1; }
    sleep 6
    osascript -e 'tell application "System Events" to set frontmost of (first process whose name starts with "TableGrip") to true' >/dev/null
    sleep 1
    if [[ "$scene" == editor || "$scene" == agent ]]; then
      # Put the caret in the console (the agent's composer takes focus otherwise), then run the query with ⌘↩.
      # Compose ignores System Events' synthetic clicks, so this goes through cliclick.
      cliclick c:420,330 w:400 kd:cmd kp:return ku:cmd
      sleep 3
    fi
    # -o leaves out the window's shadow: the page draws its own border and shadow around each shot.
    screencapture -x -o -l "$id" "$out/$scene-$theme.png"
    cwebp -quiet -q 86 "$out/$scene-$theme.png" -o "$out/$scene-$theme.webp" && rm "$out/$scene-$theme.png"
    echo "saved $out/$scene-$theme.webp"
  done
done
stop_app
