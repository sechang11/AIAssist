#!/usr/bin/env bash
# Ship the working tree to the build box and build it there.
#
#   scripts/sync-build.sh "testDebugUnitTest assembleDebug"
#
# For building without Android Studio, on a machine that has the SDK. Needs
# bash, so on Windows run it from Git Bash. Override with the environment:
#
#   REMIX_HOST=you@10.0.0.2 REMIX_KEY=~/.ssh/id_ed25519 scripts/sync-build.sh
set -uo pipefail
TASKS="${1:-testDebugUnitTest assembleDebug}"
KEY="${REMIX_KEY:-$HOME/.ssh/k4xhix_ed25519}"
HOST="${REMIX_HOST:-k4shix@192.168.0.45}"
REMOTE="${REMIX_REMOTE:-/home/k4shix/remix-build/app-src}"
ENVSH="${REMIX_ENV:-/home/k4shix/remix-build/env.sh}"
TMP="$(mktemp -d)"

cd "$(dirname "$0")/.." || exit 1

# The version the phone compares against. Minutes since 2025, so it climbs even
# for several builds off one commit, which commit count alone would not. The
# name is the human half: which commit is actually on the phone.
CODE=$(( ( $(date +%s) - 1735689600 ) / 60 ))
COUNT=$(git rev-list --count HEAD)
SHA=$(git log -1 --format=%h)
DIRTY=""
git diff --quiet || DIRTY="+"
git diff --cached --quiet || DIRTY="+"
NAME="$COUNT$DIRTY ($SHA)"
echo "building $NAME, code $CODE"

# Everything git would show: tracked plus untracked-not-ignored. Keeps build/
# and .gradle/ on the far side untouched so the daemon stays warm.
git ls-files -c -o --exclude-standard -z > "$TMP/list"
tar --null -czf "$TMP/src.tgz" -T "$TMP/list" || exit 1
echo "sending $(du -h "$TMP/src.tgz" | cut -f1)"
scp -q -i "$KEY" "$TMP/src.tgz" "$HOST:/tmp/remix-src.tgz" || exit 1
rm -rf "$TMP"

ssh -i "$KEY" "$HOST" "bash -s" <<REMOTE_EOS
set -uo pipefail
cd "$REMOTE" || exit 1
tar -xzf /tmp/remix-src.tgz
source "$ENVSH"
./gradlew --no-daemon -PremixVersionCode=$CODE -PremixVersionName="$NAME" $TASKS 2>&1 | tail -70
exit \${PIPESTATUS[0]}
REMOTE_EOS
