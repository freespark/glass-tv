#!/usr/bin/env bash
# Put the TV back to a snapshot from backup-tv.sh: remove Glass TV, re-enable every package that was
# enabled, restore the Home launcher and the accessibility / contrast / screensaver settings.
# Usage: TV_HOST=192.168.1.50 ./rollback-tv.sh [backup/<timestamp>]    (default: backup/latest)
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"; export TV_HOST
B="${1:-backup/latest}"
[ -s "$B/packages-enabled.txt" ] && [ -s "$B/home-activity.txt" ] || { echo "ERROR: no usable backup in $B" >&2; exit 1; }
tv() { tools/venv/bin/python -I tools/tv.py "$@" | tr -d '\r'; }

# Safety: only roll back the TV the backup was taken from.
want=$(cat "$B/device-name.txt" 2>/dev/null || true)
have=$(tv 'settings get global device_name' | tail -n 1)
if [ -n "$want" ] && [ "$want" != "$have" ]; then
  echo "ERROR: backup is from '$want' but $TV_HOST is '$have'. Nothing was changed." >&2; exit 1
fi

home=$(cat "$B/home-activity.txt")
now_disabled=$(tv 'pm list packages -d' | sed 's/^package://' | sort)
{
  # Packages enabled in the backup but disabled now (the stock launcher, and anything a debloat touched).
  comm -12 "$B/packages-enabled.txt" <(printf '%s\n' "$now_disabled") | sed 's/^/pm enable --user 0 /'
  if grep -qx com.gathin.tvhome "$B/packages-enabled.txt"; then echo "echo Glass TV was in the backup, kept"
  else echo "pm uninstall com.gathin.tvhome"; fi
  case "$home" in */*) echo "cmd package set-home-activity --user 0 $home" ;; esac
  while IFS='=' read -r k v; do
    ns=secure; case "$k" in *:*) ns=${k%%:*}; k=${k#*:} ;; esac   # older backups have no namespace: secure
    if [ "$v" = null ] || [ -z "$v" ]; then echo "settings delete $ns $k"
    else printf 'settings put %s %s %q\n' "$ns" "$k" "$v"; fi
  done < "$B/key-settings.txt"
  echo "input keyevent KEYCODE_HOME"
} > "$B/.rollback-commands.txt"

echo "Commands to run on $have ($TV_HOST):"; cat "$B/.rollback-commands.txt"
if [ "${DRY_RUN:-0}" = 1 ]; then echo "DRY_RUN=1: nothing was sent."; exit 0; fi
tools/venv/bin/python -I tools/tv.py --batch < "$B/.rollback-commands.txt"
echo "Home now: $(tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1)"
echo "Rollback done."
