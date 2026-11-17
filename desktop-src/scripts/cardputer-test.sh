#!/bin/bash
# Checks whether kv4p HT Desktop can run on a small Linux device (Cardputer Zero, Pi Zero 2 class).
# Collects system info, starts the app for ~2 minutes while measuring its memory use,
# then writes everything to ~/kv4p-device-test.txt to send back.
#
# Usage:  curl -fsSL https://raw.githubusercontent.com/LukasFrinn/kv4p-ht/desktop-app/desktop-src/scripts/cardputer-test.sh | bash
# Leave the device alone while it runs; close the app window early only if it hangs the device.
set -uo pipefail

OUT="$HOME/kv4p-device-test.txt"
APP=/opt/kv4p-ht-desktop/bin/kv4p-ht-desktop
SECONDS_TO_RUN=120

exec > >(tee "$OUT") 2>&1

section() { echo; echo "=== $* ==="; }

section "System"
date
uname -a
echo "dpkg architecture: $(dpkg --print-architecture 2>/dev/null || echo unknown)"
grep -E '^(PRETTY_NAME|VERSION_CODENAME)=' /etc/os-release 2>/dev/null
grep -m1 -E 'Model|model name' /proc/cpuinfo 2>/dev/null
echo "CPU cores: $(nproc)"
free -m
swapon --show 2>/dev/null || true
df -h / | tail -1

section "Display"
echo "XDG_SESSION_TYPE=${XDG_SESSION_TYPE:-}  DISPLAY=${DISPLAY:-}  WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-}"
ls -l /dev/fb* /dev/dri 2>/dev/null
for f in /sys/class/graphics/fb*/virtual_size; do [ -f "$f" ] && echo "$f: $(cat "$f")"; done
command -v xrandr >/dev/null && [ -n "${DISPLAY:-}" ] && xrandr --current 2>/dev/null | head -3
command -v glxinfo >/dev/null && [ -n "${DISPLAY:-}" ] && glxinfo -B 2>/dev/null | grep -E 'renderer|OpenGL version'
ps -eo comm | grep -m5 -E '^(Xorg|Xwayland|labwc|wayfire|weston|openbox|lxsession|cage)$' || echo "no known X/Wayland server process found"

section "App"
ARCH=$(dpkg --print-architecture 2>/dev/null)
if [ "$ARCH" != "arm64" ] && [ "$ARCH" != "amd64" ]; then
  echo "This system is '$ARCH'. The app is only built for arm64/amd64, so it cannot run here."
  echo "A 64-bit OS image (arm64) would be needed."
  echo; echo "Report saved to $OUT"
  exit 0
fi
if [ ! -x "$APP" ]; then
  echo "App not installed. Install it first with:"
  echo "  curl -fsSL https://raw.githubusercontent.com/LukasFrinn/kv4p-ht/desktop-app/desktop-src/scripts/install-linux.sh | bash"
  echo "then run this test again."
  echo; echo "Report saved to $OUT"
  exit 0
fi
dpkg-query -W -f='installed package: ${Package} ${Version}\n' kv4p-ht-desktop 2>/dev/null
if [ -z "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
  echo "WARNING: no DISPLAY / WAYLAND_DISPLAY in this shell - the app needs a graphical session."
  echo "Run this from a terminal inside the desktop, or try: DISPLAY=:0 bash cardputer-test.sh"
fi

pkill -f '/opt/kv4p-ht-desktop/' 2>/dev/null && sleep 2
section "Running the app for ${SECONDS_TO_RUN} s"
LOG=$(mktemp)
START=$(date +%s.%N)
"$APP" >"$LOG" 2>&1 &
PID=$!
PEAK=0
WINDOW_AT=""
echo "time_s  app_rss_MB  mem_available_MB  swap_used_MB  cpu%"
for ((i = 0; i < SECONDS_TO_RUN; i += 2)); do
  if ! kill -0 "$PID" 2>/dev/null; then
    wait "$PID"; echo "App exited by itself after ${i} s with code $?"
    break
  fi
  # Sum RSS of the app and anything it started.
  RSS=$(ps -o rss= --pid "$PID" --ppid "$PID" 2>/dev/null | awk '{s+=$1} END {print int(s/1024)}')
  [ "${RSS:-0}" -gt "$PEAK" ] && PEAK=$RSS
  AVAIL=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
  SWAP=$(awk '/SwapTotal/ {t=$2} /SwapFree/ {f=$2} END {print int((t-f)/1024)}' /proc/meminfo)
  CPU=$(ps -o %cpu= --pid "$PID" 2>/dev/null | tr -d ' ')
  if [ -z "$WINDOW_AT" ] && command -v xdotool >/dev/null && xdotool search --name 'kv4p' >/dev/null 2>&1; then
    WINDOW_AT=$(awk -v a="$(date +%s.%N)" -v b="$START" 'BEGIN {printf "%.1f", a - b}')
  fi
  printf "%6d  %10s  %16s  %12s  %4s\n" "$i" "$RSS" "$AVAIL" "$SWAP" "$CPU"
  sleep 2
done

section "Result"
echo "Peak app memory (RSS): ${PEAK} MB"
[ -n "$WINDOW_AT" ] && echo "Window appeared after: ${WINDOW_AT} s" || echo "Window appearance not detected automatically (xdotool missing or no window) - please say how long it took"
if kill -0 "$PID" 2>/dev/null; then
  echo "App was still running at the end; stopping it."
  kill "$PID" 2>/dev/null; sleep 3; kill -9 "$PID" 2>/dev/null
fi
section "App output (last 60 lines)"
tail -60 "$LOG"
rm -f "$LOG"
section "Kernel out-of-memory messages"
(journalctl -k --since "-5 min" 2>/dev/null || dmesg 2>/dev/null) | grep -i -E 'out of memory|oom' | tail -5 || true
echo "(none above means none found or no permission to read)"

echo
echo "Report saved to $OUT - send it back, plus how the app looked/responded on the screen."
