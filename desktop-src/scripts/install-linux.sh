#!/bin/bash
# Installs (or updates) kv4p HT Desktop on Debian-based Linux (Raspberry Pi OS,
# Debian 13 / ClockworkPi uConsole, Ubuntu) from the latest successful GitHub
# Actions build. Run it again any time to update; no need to uninstall first.
#
# Needs the GitHub CLI once:  sudo apt install gh && gh auth login
# Usage:  bash install-linux.sh            (or: curl -fsSL <raw url> | bash)
set -euo pipefail

REPO="${KV4P_REPO:-LukasFrinn/kv4p-ht}"
BRANCH="${KV4P_BRANCH:-desktop-app}"

case "$(dpkg --print-architecture)" in
  arm64) ARTIFACT="kv4p-ht-desktop-linux-arm64" ;;
  amd64) ARTIFACT="kv4p-ht-desktop-linux-x64" ;;
  *) echo "Unsupported architecture: $(dpkg --print-architecture) (need arm64 or amd64)." >&2; exit 1 ;;
esac

if ! command -v gh >/dev/null; then
  echo "GitHub CLI not found. Install it with:  sudo apt install gh && gh auth login" >&2
  exit 1
fi

echo "Looking for the latest build of ${REPO} (${BRANCH})..."
# Newest build that produced this system's package (other jobs of that run, e.g. macOS, may have failed).
RUN_ID=$(gh api "repos/${REPO}/actions/artifacts?name=${ARTIFACT}&per_page=50" \
  -q "[.artifacts[] | select(.expired | not) | select(.workflow_run.head_branch == \"${BRANCH}\")][0].workflow_run.id")
if [ -z "${RUN_ID}" ] || [ "${RUN_ID}" = "null" ]; then
  echo "No build with ${ARTIFACT} found." >&2
  exit 1
fi

# /var/tmp is on disk; /tmp is a small RAM disk on some devices (e.g. the Cardputer Zero).
TMP=$(mktemp -d /var/tmp/kv4p-install.XXXXXX)
trap 'rm -rf "$TMP" /var/tmp/kv4p-ht-desktop.deb' EXIT

echo "Downloading build ${RUN_ID} (${ARTIFACT})..."
TMPDIR="$TMP" gh run download "$RUN_ID" -R "$REPO" -n "$ARTIFACT" -D "$TMP"
DEB=$(find "$TMP" -name '*.deb' | head -1)
[ -n "${DEB}" ] || { echo "No .deb in the build." >&2; exit 1; }

echo "Installing $(basename "$DEB") (sudo password may be asked)..."
pkill -f '/opt/kv4p-ht-desktop/' 2>/dev/null || true
# apt needs a path it can read as the _apt user; the mktemp folder may not be.
cp "$DEB" /var/tmp/kv4p-ht-desktop.deb
chmod 644 /var/tmp/kv4p-ht-desktop.deb
rm -rf "$TMP"   # free the space before unpacking
sudo apt-get install -y --allow-downgrades /var/tmp/kv4p-ht-desktop.deb
rm -f /var/tmp/kv4p-ht-desktop.deb

if ! id -nG "$USER" | tr ' ' '\n' | grep -qx dialout; then
  echo "Adding ${USER} to the 'dialout' group for USB serial access..."
  sudo usermod -aG dialout "$USER"
  echo "Log out and back in (or reboot) once so the radio's port can be opened."
fi

# M5Stack Cardputer Zero: also put the compact framebuffer version into APPLaunch,
# the launcher on the built-in 320x170 screen.
APPLAUNCH=/usr/share/APPLaunch
if [ -d "$APPLAUNCH/applications" ]; then
  echo "Cardputer Zero detected: adding kv4p HT to APPLaunch..."
  sudo mkdir -p "$APPLAUNCH/bin" "$APPLAUNCH/share/images"
  sudo tee "$APPLAUNCH/bin/kv4p-ht" >/dev/null <<'EOF'
#!/bin/sh
# kv4p HT on the Cardputer Zero's built-in screen (no X11/Wayland needed).
# Small heap and a simple JIT so it fits next to the launcher in ~350 MB of RAM.
export JAVA_TOOL_OPTIONS="-Xmx80m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=32m"
exec /opt/kv4p-ht-desktop/bin/kv4p-ht-desktop --framebuffer "$@"
EOF
  sudo chmod 755 "$APPLAUNCH/bin/kv4p-ht"
  ICON=$(find /opt/kv4p-ht-desktop -maxdepth 2 -name '*.png' | head -1)
  [ -n "$ICON" ] && sudo cp "$ICON" "$APPLAUNCH/share/images/kv4p-ht.png"
  sudo tee "$APPLAUNCH/applications/kv4p-ht.desktop" >/dev/null <<'EOF'
[Desktop Entry]
Type=Application
Name=kv4p HT
Comment=kv4p HT radio: voice, memories and APRS
Exec=/usr/share/APPLaunch/bin/kv4p-ht
Icon=share/images/kv4p-ht.png
Terminal=false
Categories=Utility;
StartupNotify=false
EOF
  echo "kv4p HT is now in APPLaunch (log: ~/.kv4p-desktop/kv4p.log)."
fi

echo "Done (build ${RUN_ID}). Start it from the menu or run: /opt/kv4p-ht-desktop/bin/kv4p-ht-desktop"
