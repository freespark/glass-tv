#!/usr/bin/env bash
# Snapshot the TV state that Glass TV's installer can change, so rollback-tv.sh can put it back.
# Usage: TV_HOST=192.168.1.50 ./backup-tv.sh        -> backup/<timestamp>/
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"; export TV_HOST
tv() { tools/venv/bin/python -I tools/tv.py "$@" | tr -d '\r'; }

B=backup/$(date +%Y%m%d-%H%M%S)
mkdir -p "$B"

tv getprop > "$B/getprop.txt"
tv 'settings get global device_name' | tail -n 1 > "$B/device-name.txt"
tv 'pm list packages -e' | sed 's/^package://' | sort > "$B/packages-enabled.txt"
tv 'pm list packages -d' | sed 's/^package://' | sort > "$B/packages-disabled.txt"
tv 'pm list packages -3' | sed 's/^package://' | sort > "$B/packages-3rdparty.txt"
tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
  | tail -n 1 > "$B/home-activity.txt"
tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
  > "$B/home-candidates.txt"
for ns in global secure system; do tv "settings list $ns" > "$B/settings-$ns.txt"; done
# The settings the installer (and its optional flags) may touch, in a form rollback-tv.sh reads back.
{
  for k in enabled_accessibility_services accessibility_enabled high_text_contrast_enabled screensaver_components; do
    echo "secure:$k=$(tv "settings get secure $k" | tail -n 1)"
  done
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    echo "global:$k=$(tv "settings get global $k" | tail -n 1)"
  done
  echo "system:time_12_24=$(tv 'settings get system time_12_24' | tail -n 1)"
} > "$B/key-settings.txt"

# Refuse a snapshot that is missing its core files: a rollback from it would do the wrong thing.
for f in packages-enabled.txt home-activity.txt key-settings.txt; do
  [ -s "$B/$f" ] || { echo "ERROR: $B/$f is empty; backup incomplete." >&2; exit 1; }
done
ln -sfn "$(basename "$B")" backup/latest
echo "Backup saved to $B (device: $(cat "$B/device-name.txt"), home: $(cat "$B/home-activity.txt"))"
