# Java + Bedrock on one server, for free

Everything here is free. You don't need a paid host, a paid plugin or a Minecraft account for Bedrock players.

* **Java players** join like any Java server (they use their own Java account as usual).
* **Bedrock players** (Windows, phone, Xbox, PlayStation, Switch) join the same world through **Geyser**, which
  translates Bedrock to Java. **Floodgate** lets them in with their Xbox account only, so they don't need to own
  Java Edition.

Both are free and open source: https://geysermc.org/download

> **Easiest:** on a Linux server, `scripts/oracle-setup.sh` sets up everything below automatically: the newest
> Geyser runs as its own program next to the server (so every Bedrock version can join), and ViaFabric lets
> Java players on newer Minecraft versions join too.

## 1. Get the server files (all free)

In one folder on your PC (for example `C:\mcserver`):

1. **Fabric server launcher** for Minecraft **1.21.11**: https://fabricmc.net/use/server/
   (pick Minecraft 1.21.11, download the `.jar`).
2. In a `mods` folder next to it, put:
   * **Fabric API** for 1.21.11: https://modrinth.com/mod/fabric-api (Versions tab, pick 1.21.11)
   * **Geyser (Fabric)** and **Floodgate (Fabric)**: https://geysermc.org/download
     The download page gives builds for the newest Minecraft version. If it no longer offers 1.21.11, check
     older builds on https://modrinth.com/mod/geyser and https://modrinth.com/mod/floodgate for 1.21.11.
     If there are none, move this mod to the version Geyser supports (see "Changing Minecraft version" in
     `README.md`).
   * This mod: `vigil-2.0.0.jar` (from `gradlew build`, in `build/libs/`).
3. You need **Java 21** installed (free: https://adoptium.net).

Start it once with:
```
java -Xms2G -Xmx4G -jar fabric-server-launch.jar nogui
```
Accept the EULA (`eula.txt` → `eula=true`) and start again.

## 2. Settings

`config/Geyser-Fabric/config.yml`:
```yaml
bedrock:
  port: 19132
remote:
  auth-type: floodgate
```
Leave `online-mode=true` in `server.properties` (Floodgate handles Bedrock logins; Java players still sign in
normally, which keeps cracked accounts out).

Bedrock players show up with a `.` before their name (for example `.Steve`). All commands in this mod accept that.
Use quotes if the name has a space: `/inspect ".Steve Two"`.

## 3. Let friends connect

The server needs two ports: **25565 (TCP)** for Java and **19132 (UDP)** for Bedrock.

**Option A: port forwarding (free, fastest).** In your router, forward TCP 25565 and UDP 19132 to your PC.
Friends connect with your public IP (search "what is my IP"). Java uses `IP:25565`, Bedrock uses `IP`, port 19132.

**Option B: no router access, use playit.gg (free tier).** Install https://playit.gg, then create one **Minecraft
Java** tunnel (to local port 25565) and one **Minecraft Bedrock** tunnel (UDP, to local port 19132). Friends use
the two addresses playit gives you.

**Same house / same Wi-Fi:** Java uses your PC's local IP (like `192.168.1.20`). Bedrock sees it under the
Friends tab (LAN games) or adds it as a server with that IP and port 19132.

### Consoles (Xbox, PlayStation, Switch)

Consoles can't type in a server address. Two free ways around that:
* **BedrockConnect**: change the console's DNS to a BedrockConnect server, open a featured server, and it lets
  you enter your server's address. Instructions: https://github.com/Pugmatt/BedrockConnect
* **MCXboxBroadcast** (free Geyser extension): makes the server show up in the friends list of a helper Xbox
  account. https://github.com/MCXboxBroadcast/Broadcaster

## 4. Keeping it fast

* Give the server **4 GB** (`-Xmx4G`). More isn't faster for a small group.
* In `server.properties`: `view-distance=8`, `simulation-distance=6`. These help most.
* Free performance mods for Fabric (optional, drop into `mods/`): **Lithium** (game logic),
  **FerriteCore** (memory), **ServerCore** or **Krypton** (networking). All free on Modrinth. Make sure each matches
  your Minecraft version.
* This mod's own heavy parts can be tuned in `config/vigil/config.json`: turn `xray.oreHiding` off if chunk
  loading feels slow, and raise `general.lagTpsThreshold` so checks relax sooner under lag.
* Bedrock players get more lenient anti-cheat limits automatically (`movement.bedrockLeniency`,
  `combat.bedrockReachTolerance`, `combat.bedrockMaxCps`), so touch and controller players aren't flagged for
  normal play.
