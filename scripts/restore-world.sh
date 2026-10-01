#!/usr/bin/env bash
# Puts a world backup back (the server is stopped while it's done, then started again).
#   bash anti-cheat/scripts/restore-world.sh            -> lists the backups
#   bash anti-cheat/scripts/restore-world.sh <name>     -> restores that one
# The world as it is now is kept next to it (world-before-restore-<date>), so nothing is ever lost.
set -e
SERVER_DIR="$HOME/mc"
BK="$SERVER_DIR/config/vigil/backups"
if [ -z "$1" ]; then
  echo "World backups (newest first):"
  ls -t "$BK" 2>/dev/null | grep '^world-' || echo "  none yet"
  echo
  echo "Restore one with:  bash anti-cheat/scripts/restore-world.sh <name>"
  exit 0
fi
F="$BK/$1"
[ -f "$F" ] || F="$BK/$1.zip"
[ -f "$F" ] || { echo "No backup called $1 (run without a name to list them)."; exit 1; }
command -v unzip >/dev/null || sudo DEBIAN_FRONTEND=noninteractive apt-get install -y unzip >/dev/null
LEVEL=$(grep -oP '^level-name=\K.+' "$SERVER_DIR/server.properties" 2>/dev/null || echo world)
OLD="$SERVER_DIR/$LEVEL-before-restore-$(date +%Y%m%d-%H%M%S)"
echo "Stopping the server..."
sudo systemctl stop minecraft
mv "$SERVER_DIR/$LEVEL" "$OLD"
mkdir -p "$SERVER_DIR/$LEVEL"
unzip -q "$F" -d "$SERVER_DIR/$LEVEL"
echo "Starting the server..."
sudo systemctl start minecraft
echo "Restored $(basename "$F"). The world from before is kept at $OLD (delete it when you're happy)."
