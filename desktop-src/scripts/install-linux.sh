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

echo "Looking for the latest successful build of ${REPO} (${BRANCH})..."
RUN_ID=$(gh run list -R "$REPO" -b "$BRANCH" -w "Desktop App" -s success -L 1 --json databaseId -q '.[0].databaseId')
if [ -z "${RUN_ID}" ]; then
  echo "No successful build found." >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "Downloading build ${RUN_ID} (${ARTIFACT})..."
gh run download "$RUN_ID" -R "$REPO" -n "$ARTIFACT" -D "$TMP"
DEB=$(find "$TMP" -name '*.deb' | head -1)
[ -n "${DEB}" ] || { echo "No .deb in the build." >&2; exit 1; }

echo "Installing $(basename "$DEB") (sudo password may be asked)..."
pkill -f '/opt/kv4p-ht-desktop/' 2>/dev/null || true
# apt needs a path it can read as the _apt user; /tmp/... from mktemp may not be.
cp "$DEB" /tmp/kv4p-ht-desktop.deb
chmod 644 /tmp/kv4p-ht-desktop.deb
sudo apt-get install -y --allow-downgrades /tmp/kv4p-ht-desktop.deb
rm -f /tmp/kv4p-ht-desktop.deb

if ! id -nG "$USER" | tr ' ' '\n' | grep -qx dialout; then
  echo "Adding ${USER} to the 'dialout' group for USB serial access..."
  sudo usermod -aG dialout "$USER"
  echo "Log out and back in (or reboot) once so the radio's port can be opened."
fi

echo "Done (build ${RUN_ID}). Start it from the menu or run: /opt/kv4p-ht-desktop/bin/kv4p-ht-desktop"
