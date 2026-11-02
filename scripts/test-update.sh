#!/usr/bin/env bash
# Updates a local build of TableGrip to a newer local build through Sparkle, end to end, without touching the real
# feed, key or your ~/.tablegrip:
#   1. makes a throwaway Ed25519 key pair,
#   2. builds 99.0.0 (build 990) and 99.0.1 (build 991) with that public key, ad-hoc signed,
#   3. signs the newer DMG, writes a signed feed for it, serves it on http://localhost:8089,
#   4. starts 99.0.0 against that feed, with a throwaway profile.
# Then either ⌘P → "Check for Updates…" → Install and Relaunch, or wait a minute for the automatic check and download
# and quit: the app should come back, or be left, as 99.0.1 (991).
#
#   scripts/test-update.sh          # build and run
#   scripts/test-update.sh clean    # remove the test folder
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="${TABLEGRIP_UPDATE_TEST_DIR:-${TMPDIR:-/tmp}/tablegrip-update-test}"
port=8089

# Sparkle keeps its state (last check, downloaded update) in the app's defaults, shared with an installed TableGrip.
forget_sparkle() {
  for key in SULastCheckTime SUAutomaticallyUpdate SUEnableAutomaticChecks SUHasLaunchedBefore SUUpdateGroupIdentifier SUSkippedVersion SUInitialFailedFeedSigningValidationDate; do
    defaults delete dev.tablegrip.app "$key" 2> /dev/null || true
  done
}

if [[ "${1:-}" == clean ]]; then
  rm -rf "$work"
  forget_sparkle
  echo "Removed $work and Sparkle's state"
  exit 0
fi
forget_sparkle

rm -rf "$work"
mkdir -p "$work"/{old,new,feed,meta,home}
cd "$root"

echo "→ Throwaway Ed25519 key"
openssl genpkey -algorithm ed25519 -out "$work/key.pem" 2>/dev/null
# Sparkle wants the raw 32-byte seed and public key, base64.
openssl pkey -in "$work/key.pem" -outform DER | tail -c 32 | base64 > "$work/private.key"
public=$(openssl pkey -in "$work/key.pem" -pubout -outform DER | tail -c 32 | base64)

build() { # version build
  ./gradlew -q --no-configuration-cache :app:createDistributable :app:packageDmg \
    -Ptablegrip.version="$1" -Ptablegrip.build="$2" -Ptablegrip.sparklePublicKey="$public"
}

echo "→ Building 99.0.0 (990)"
build 99.0.0 990
ditto app/build/compose/binaries/main/app/TableGrip.app "$work/old/TableGrip.app"

echo "→ Building 99.0.1 (991)"
build 99.0.1 991
cp app/build/compose/binaries/main/dmg/*.dmg "$work/feed/TableGrip-99.0.1.dmg"

sign_update=$(find app/build/sparkle -name sign_update -type f -perm -u+x | head -1)
signature=$("$sign_update" --ed-key-file "$work/private.key" -p "$work/feed/TableGrip-99.0.1.dmg")
arch=$([[ $(uname -m) == arm64 ]] && echo macos-arm64 || echo macos-x64)
cat > "$work/meta/appcast-$arch.json" <<JSON
{"file": "TableGrip-99.0.1.dmg", "length": $(stat -f %z "$work/feed/TableGrip-99.0.1.dmg"), "edSignature": "$signature", "minimumSystemVersion": "12.0", "build": 991}
JSON

echo "→ Signed feed"
TABLEGRIP_APPCAST_DIR="$work/appcast" python3 scripts/appcast.py add --metadata "$work/meta" --version 99.0.1 --build 991 \
  --tag local --repo local/local
# Point the enclosure at the local server instead of GitHub.
sed -i '' "s#https://github.com/local/local/releases/download/local/#http://localhost:$port/#" "$work/appcast/$arch.xml"
"$sign_update" --ed-key-file "$work/private.key" "$work/appcast/$arch.xml"
cp "$work/appcast/$arch.xml" "$work/feed/appcast.xml"

echo "→ Serving $work/feed on http://localhost:$port"
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$work/feed" > "$work/server.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT

echo "→ Starting 99.0.0. Check for Updates… from ⌘P; quit it to end the test."
TABLEGRIP_UPDATE_FEED="http://localhost:$port/appcast.xml" JAVA_TOOL_OPTIONS="-Dtablegrip.home=$work/home" \
  "$work/old/TableGrip.app/Contents/MacOS/TableGrip" || true
# Sparkle installs once the app is gone (and relaunches it after "Install and Relaunch"); wait for both.
echo "→ Waiting for the install"
for _ in $(seq 1 90); do
  [[ $(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$work/old/TableGrip.app/Contents/Info.plist") == 991 ]] && break
  sleep 1
done
while pgrep -f "$work/old/TableGrip.app/Contents/MacOS/TableGrip" > /dev/null; do sleep 1; done
echo "→ Installed: $(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$work/old/TableGrip.app/Contents/Info.plist") ($(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$work/old/TableGrip.app/Contents/Info.plist"))"
