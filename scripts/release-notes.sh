#!/bin/bash
# Prints what's new in a release as Markdown bullets, from the same lists the apps show after an update:
#   scripts/release-notes.sh android <version>   (src/app/dash/Changelog.java, by the versionCode build.sh had for it)
#   scripts/release-notes.sh windows <version>   (windows/changelog.go)
# With <file> <sha256> after the version, the SHA-256 line of the release follows the bullets.
# The bullets are left out if the version has no entry. Run from the repo root; android needs the git history of build.sh.
set -euo pipefail
plat=$1 ver=$2
if [ $# -ge 4 ]; then
  notes=$("$0" "$plat" "$ver")
  [ -z "$notes" ] || printf '%s\n\n' "$notes"
  echo "SHA-256 \`$3\`: \`$4\`"
  exit 0
fi
case "$plat" in
  android)
    code=$(sed -n "s/.*--version-code \([0-9]*\) --version-name $ver .*/\1/p" build.sh)
    [ -n "$code" ] || code=$(git log -p --format= -- build.sh | sed -n "s/^+.*--version-code \([0-9]*\) --version-name $ver .*/\1/p" | head -1)
    [ -n "$code" ] || exit 0
    awk -v code="$code" '
      $0 ~ "ENTRIES.put\\(" code "," { on = 1; next }
      on && /^ *}\);/ { exit }
      on { if (match($0, /"(.*)"/)) print "- " substr($0, RSTART + 1, RLENGTH - 2) }' src/app/dash/Changelog.java
    ;;
  windows)
    awk -v ver="$ver" '
      index($0, "\"" ver "\": {") { on = 1; next }
      on && /^\t},/ { exit }
      on { if (match($0, /"(.*)"/)) print "- " substr($0, RSTART + 1, RLENGTH - 2) }' windows/changelog.go
    ;;
  *) echo "usage: $0 android|windows <version>" >&2; exit 2 ;;
esac
