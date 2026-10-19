#!/bin/bash
# Installs (or updates) kv4p HT Desktop on macOS from the latest successful
# GitHub Actions build, without the "app is damaged / unidentified developer"
# Gatekeeper block.
#
# Why this works: macOS only warns about apps carrying the "quarantine"
# attribute, which web browsers add to downloads. The GitHub CLI doesn't, and
# we also clear it explicitly. The build itself is ad-hoc signed, not notarized.
#
# Needs the GitHub CLI once:  brew install gh && gh auth login
# Usage:  bash install-macos.sh            (or: curl -fsSL <raw url> | bash)
set -euo pipefail

REPO="${KV4P_REPO:-LukasFrinn/kv4p-ht}"
BRANCH="${KV4P_BRANCH:-desktop-app}"
ARTIFACT="kv4p-ht-desktop-macos-arm64"
DEST="/Applications"

if ! command -v gh >/dev/null; then
  echo "GitHub CLI not found. Install it with:  brew install gh && gh auth login" >&2
  exit 1
fi

echo "Looking for the latest successful build of $REPO ($BRANCH)..."
RUN_ID=$(gh run list -R "$REPO" -b "$BRANCH" -w "Desktop App" -s success -L 1 --json databaseId -q '.[0].databaseId')
if [ -z "$RUN_ID" ]; then
  echo "No successful build found." >&2
  exit 1
fi

TMP=$(mktemp -d)
trap 'hdiutil detach "$TMP/mnt" -quiet 2>/dev/null || true; rm -rf "$TMP"' EXIT

echo "Downloading build ${RUN_ID}..."
gh run download "$RUN_ID" -R "$REPO" -n "$ARTIFACT" -D "$TMP"
DMG=$(find "$TMP" -name '*.dmg' | head -1)
[ -n "$DMG" ] || { echo "No .dmg in the build." >&2; exit 1; }

echo "Installing to ${DEST}..."
mkdir -p "$TMP/mnt"
hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$TMP/mnt" -quiet
APP=$(find "$TMP/mnt" -maxdepth 1 -name '*.app' | head -1)
NAME=$(basename "$APP")
pkill -f "$DEST/$NAME/Contents/MacOS" 2>/dev/null || true
rm -rf "${DEST:?}/$NAME"
ditto "$APP" "$DEST/$NAME"
xattr -dr com.apple.quarantine "$DEST/$NAME" 2>/dev/null || true

echo "Done: $DEST/$NAME (build $RUN_ID)"
open "$DEST/$NAME"
