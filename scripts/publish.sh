#!/usr/bin/env bash
# Put the freshly built APK where the phone can get it, three ways.
#
#   1. latest.json, which the app itself reads on launch and offers to install.
#      This is the loop worth having: no browser, no file manager, one tap.
#   2. A page at http://192.168.0.45:8099 with a download button, for the first
#      install and for when the app is too broken to update itself.
#   3. adb, if the phone is paired over wifi.
#
# The version numbers are read back out of the built APK rather than passed in,
# so what the phone compares against cannot disagree with what it installs.
# Run this ON the build box. REMIX_ADDR is the address the phone will use to
# reach it, which is not necessarily the one you ssh in on.
set -u
ADDR="${REMIX_ADDR:-192.168.0.45:8099}"
export PATH="$HOME/remix-build/android-sdk/platform-tools:$PATH"
AAPT="$HOME/remix-build/android-sdk/build-tools/35.0.0/aapt2"

SRC="$HOME/remix-build/app-src/app/build/outputs/apk/debug/app-debug.apk"
DIST="$HOME/remix-dist"
NOTE="${1:-}"

[ -f "$SRC" ] || { echo "no APK at $SRC"; exit 1; }
mkdir -p "$DIST"
cp "$SRC" "$DIST/remix-debug.apk"

BUILT=$(date '+%H:%M on %-d %B')
SIZE=$(du -h "$DIST/remix-debug.apk" | cut -f1)

BADGING=$("$AAPT" dump badging "$DIST/remix-debug.apk" 2>/dev/null | head -1)
CODE=$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
NAME=$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
[ -n "$CODE" ] || { echo "could not read versionCode from the APK"; exit 1; }

# Escape the note for JSON: it is written by hand on the command line.
esc() { printf '%s' "$1" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read())[1:-1])'; }

cat > "$DIST/latest.json" <<JSON
{
  "versionCode": $CODE,
  "versionName": "$(esc "$NAME")",
  "note": "$(esc "$NOTE")",
  "built": "$(esc "$BUILT")",
  "size": "$(esc "$SIZE")",
  "url": "http://$ADDR/remix-debug.apk"
}
JSON

cat > "$DIST/index.html" <<HTML
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Remix build</title>
<style>
  body { margin:0; font-family: system-ui, -apple-system, sans-serif; background:#111; color:#eee;
         display:flex; align-items:center; justify-content:center; min-height:100vh; padding:24px; }
  .card { max-width:420px; width:100%; }
  h1 { font-size:22px; margin:0 0 6px; }
  p { color:#aaa; font-size:15px; line-height:1.5; margin:0 0 18px; }
  .note { background:#1c1c1c; border-left:3px solid #4f46e5; border-radius:0 8px 8px 0;
          padding:12px 14px; font-size:14px; color:#ccc; margin-bottom:18px; }
  a.get { display:block; text-align:center; background:#4f46e5; color:#fff; text-decoration:none;
          font-weight:700; font-size:17px; padding:16px; border-radius:14px; }
  small { display:block; color:#777; font-size:13px; line-height:1.5; margin-top:16px; }
</style>
<div class="card">
  <h1>Remix</h1>
  <p>Build $NAME &middot; $BUILT &middot; $SIZE</p>
  ${NOTE:+<div class="note">$NOTE</div>}
  <a class="get" href="remix-debug.apk">Download and install</a>
  <small>You only need this page once. After the first install the app checks
  here itself and offers the update on its home screen.</small>
</div>
HTML

# --directory rather than a cd in a subshell: the subshell alone was enough to
# hold the ssh channel open after the script had finished, so publishing from
# this machine hung every time until it timed out. Matching on the listening
# port rather than pgrep -f, which also matches the ssh command line running it.
if ! ss -ltn 2>/dev/null | grep -q ":8099"; then
  setsid nohup python3 -m http.server 8099 --bind 0.0.0.0 --directory "$DIST" \
    > "$HOME/dist-server.log" 2>&1 < /dev/null &
  disown
  sleep 2
fi
echo "page:  http://$ADDR   (build $NAME, code $CODE, $SIZE)"
echo -n "json:  "
curl -s -m 5 http://127.0.0.1:8099/latest.json | tr -d '\n' | cut -c1-140; echo

if adb devices 2>/dev/null | grep -q "device$"; then
  echo -n "adb:   "
  adb install -r "$DIST/remix-debug.apk" 2>&1 | tail -1
else
  echo "adb:   no device connected, the app will offer it instead"
fi
