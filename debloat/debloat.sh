#!/bin/bash
# Hides (does not delete) every package in a list from the TV's user profile. Undo with restore.sh.
# Re-run after a firmware update, which can bring removed apps back.
#
# Usage: ./debloat.sh <tv-ip>[:port] [package-list]
#        package-list defaults to packages.txt next to this script, e.g. examples/xiaomi-tv.txt
set -u
if [ $# -lt 1 ]; then
  echo "Usage: $0 <tv-ip>[:port] [package-list]"
  exit 1
fi
# Accepts <ip> (port 5555, classic network debugging) or <ip>:<port> (Wireless debugging, Android 11+/Google TV)
case "$1" in *:*) S="$1" ;; *) S="$1:5555" ;; esac
LIST="${2:-$(dirname "$0")/packages.txt}"

adb connect "$S"
# Strip comments and blank lines. `shell -n` stops adb from swallowing the rest of the list from stdin.
sed 's/#.*//; s/[[:space:]]*$//' "$LIST" | grep -v '^$' | while read -r p; do
  echo "$p: $(adb -s "$S" shell -n pm uninstall -k --user 0 "$p" 2>&1)"
done
