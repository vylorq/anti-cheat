#!/usr/bin/env bash
# One-command Minecraft server setup for a fresh Oracle Cloud Ubuntu server (Always Free, ARM or x86).
#   Java + Bedrock (Geyser + Floodgate) + Fabric API + the anti-cheat mod, built from GitHub on the server.
#
# Run it on the server (put your Minecraft name, Java or Bedrock, after OWNER= to become the server owner):
#   curl -fsSL https://raw.githubusercontent.com/vylorq/anti-cheat/main/scripts/oracle-setup.sh | OWNER=YourName bash
# If the GitHub repo is private, also pass a GitHub token:  ... | OWNER=YourName GITHUB_TOKEN=ghp_xxx bash
# Run it again any time to update the mod (the world and settings are kept).
set -euo pipefail

MC_VERSION="${MC_VERSION:-1.21.4}"
REPO="${REPO:-https://github.com/vylorq/anti-cheat.git}"
BRANCH="${BRANCH:-main}"
SERVER_DIR="$HOME/mc"
SRC_DIR="$HOME/anti-cheat"
OWNER="${OWNER:-}"
GITHUB_TOKEN="${GITHUB_TOKEN:-}"
if [ -n "$GITHUB_TOKEN" ]; then
  REPO="${REPO/https:\/\//https://x-access-token:$GITHUB_TOKEN@}"
fi

say() { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m!! %s\033[0m\n' "$*"; exit 1; }

# Memory for Minecraft: about 2/3 of the machine, between 2 and 16 GB.
TOTAL_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
MEM_MB=$(( TOTAL_MB * 2 / 3 ))
(( MEM_MB > 16384 )) && MEM_MB=16384
(( MEM_MB < 2048 )) && MEM_MB=2048

say "Installing Java 21, git and tools"
sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk-headless git curl jq iptables-persistent

say "Opening ports 25565/tcp (Java) and 19132/udp (Bedrock) in the server firewall"
sudo iptables -C INPUT -p tcp --dport 25565 -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 5 -p tcp --dport 25565 -j ACCEPT
sudo iptables -C INPUT -p udp --dport 19132 -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 5 -p udp --dport 19132 -j ACCEPT
sudo netfilter-persistent save

say "Getting the mod source"
if [ -d "$SRC_DIR/.git" ]; then
  git -C "$SRC_DIR" remote set-url origin "$REPO"
  git -C "$SRC_DIR" fetch origin "$BRANCH"
  git -C "$SRC_DIR" checkout -B "$BRANCH" "origin/$BRANCH"
else
  git clone -b "$BRANCH" "$REPO" "$SRC_DIR" || fail "Could not download the code. If the GitHub repo is private, make it public (GitHub -> repo Settings -> Change visibility) or run again with GITHUB_TOKEN=... (see ORACLE_HOSTING.md)."
fi

say "Building the mod (first time takes several minutes)"
cd "$SRC_DIR"
chmod +x gradlew
if ! ./gradlew build --no-daemon > "$HOME/build.log" 2>&1; then
  grep -E "error:|FAILED|What went wrong" -A3 "$HOME/build.log" | head -80 || true
  fail "The build failed. Copy the errors above (or run: cat ~/build.log) and send them to Claude to fix. Then run this script again."
fi
MOD_JAR="$SRC_DIR/$(ls build/libs/anticheat-*.jar | grep -v sources | head -1)"

say "Downloading the Fabric server for Minecraft $MC_VERSION"
mkdir -p "$SERVER_DIR/mods"
cd "$SERVER_DIR"
LOADER=$(curl -fsSL "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION" | jq -r '[.[] | select(.loader.stable)][0].loader.version')
INSTALLER=$(curl -fsSL "https://meta.fabricmc.net/v2/versions/installer" | jq -r '[.[] | select(.stable)][0].version')
curl -fsSL -o server.jar "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER/$INSTALLER/server/jar"

# Newest Modrinth build of a project for this Minecraft version on Fabric.
modrinth() {
  local project="$1"
  local url
  url=$(curl -fsSL -G "https://api.modrinth.com/v2/project/$project/version" \
        --data-urlencode "game_versions=[\"$MC_VERSION\"]" \
        --data-urlencode 'loaders=["fabric"]' | jq -r '.[0].files[] | select(.primary) | .url' | head -1)
  [ -n "$url" ] && [ "$url" != "null" ] || fail "No $project build for Minecraft $MC_VERSION on Modrinth. Ask Claude to move the mod to a newer Minecraft version."
  rm -f "mods/$project"-*.jar mods/"${project^}"-*.jar
  curl -fsSL -o "mods/$project-$MC_VERSION.jar" "$url"
  echo "  $project OK"
}

say "Downloading Fabric API, Geyser and Floodgate"
modrinth fabric-api
modrinth geyser
modrinth floodgate
cp "$MOD_JAR" mods/anticheat.jar

echo "eula=true" > eula.txt
if [ ! -f server.properties ]; then
  cat > server.properties <<EOF
motd=Our server (Java + Bedrock)
view-distance=8
simulation-distance=6
online-mode=true
enforce-secure-profile=false
EOF
fi

say "Creating the background service (starts on boot, restarts on crash)"
sudo tee /etc/systemd/system/minecraft.service > /dev/null <<EOF
[Unit]
Description=Minecraft server
After=network-online.target

[Service]
User=$USER
WorkingDirectory=$SERVER_DIR
ExecStart=/usr/bin/java -Xms${MEM_MB}M -Xmx${MEM_MB}M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -jar server.jar nogui
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable minecraft >/dev/null
sudo systemctl restart minecraft

# Bedrock players log in through Floodgate (no Java account needed).
GEYSER_CFG="$SERVER_DIR/config/Geyser-Fabric/config.yml"
say "Waiting for the first start to create the Geyser settings (up to 5 minutes)"
for _ in $(seq 1 60); do [ -f "$GEYSER_CFG" ] && break; sleep 5; done
if [ -f "$GEYSER_CFG" ] && ! grep -q "auth-type: floodgate" "$GEYSER_CFG"; then
  sed -i 's/auth-type: .*/auth-type: floodgate/' "$GEYSER_CFG"
  sudo systemctl restart minecraft
fi

# Make OWNER the server owner (full access to every staff tool).
ANTI_CFG="$SERVER_DIR/config/anticheat/config.json"
if [ -n "$OWNER" ]; then
  # By name, so it works for Java and Bedrock alike: the first player with this name to join becomes the owner.
  say "Making $OWNER the owner (as soon as they join)"
  for _ in $(seq 1 24); do [ -f "$ANTI_CFG" ] && break; sleep 5; done
  if [ -f "$ANTI_CFG" ]; then
    sudo systemctl stop minecraft
    jq --arg n "$OWNER" '.general.ownerName = $n | .general.ownerUuid = ""' "$ANTI_CFG" > "$ANTI_CFG.tmp" && mv "$ANTI_CFG.tmp" "$ANTI_CFG"
    sudo systemctl start minecraft
    echo "  $OWNER becomes the owner the next time they join."
  else
    echo "  The mod's config was not created yet. Run this script again in a minute to set the owner."
  fi
fi

IP=$(curl -fsSL https://ifconfig.me || echo "YOUR_SERVER_IP")
say "Done! The server is starting (give it a minute)."
cat <<EOF

  Java players:     $IP
  Bedrock players:  $IP   port 19132

  Server log:        journalctl -u minecraft -f      (Ctrl+C to leave)
  Stop/start:        sudo systemctl stop minecraft   /   sudo systemctl start minecraft
  Update the mod:    run the same setup command again
  Set the owner:     run the setup command again with OWNER=YourName

  Remember to also open 25565 TCP and 19132 UDP in Oracle's Security List (see ORACLE_HOSTING.md).
EOF
