<p align="center"><img src="branding/vigil_logo.png" alt="Vigil" width="480"></p>

# Vigil: Server Guard (Fabric, Minecraft 1.21.11)

Anti-cheat and server protection that keeps watch through the night. A server-side Fabric mod: anti-cheat with admin
review, staff tools, claims, barriers, lobby, jail, PvP arenas, traders, secure player trading, new-player
verification and the Watcher. Java and Bedrock (Geyser + Floodgate) players. Built from `ANTICHEAT_SPEC.md`,
`WATCHER_UPDATE.md` and `UI_UPDATE.md`.

> **Java + Bedrock crossplay setup (all free): see [CROSSPLAY.md](CROSSPLAY.md). Free hosting on Oracle Cloud:
> [ORACLE_HOSTING.md](ORACLE_HOSTING.md).**

## Status

* Every push is built and tested on GitHub: `core` unit tests, the in-game tests (`runGametest` on a headless
  1.21.11 server) and a crossplay test where a Bedrock client joins through the newest Geyser, sees the Watcher and
  opens the Vigil menus.
* The mod id is **`vigil`** (it used to be `anticheat`). On first start Vigil moves `config/anticheat/` to
  `config/vigil/` and renames the database, so nothing is lost. Permission nodes are `vigil.*`; old `anticheat.*`
  nodes still work.
* Logo files: `branding/` (`icon.png`, `vigil_icon.png/.svg`, `vigil_logo.png/.svg`). The mod icon is
  `src/main/resources/assets/vigil/icon.png`.

## Using it

* **`/vigil`** (or `/vg`, or the old `/ac`) opens the **Vigil Panel**: every feature is a click away. People on the
  second row, places on the third, tools on the fourth. Numbers in a button's name mean something is waiting.
* **`/vigil help`** lists every command you may use, grouped and clickable. A mistyped subcommand or player name gets
  a "Did you mean ...?".
* **`/settings`** has pages (General, PvP & World, Anti-Cheat, Protection, Lobby & Jail, Waiting Room, Arenas,
  Traders, Watcher, Messages & Style). Every setting shows its current and default value; shift + right-click resets
  it; a gold dot marks changed ones; Search finds any setting.
* **`/language`**: each player sees their game's language (English or Arabic) automatically, or picks one here.
  Bedrock players get English unless they pick Arabic, because Bedrock can't join Arabic letters in menus.
* Menus look the same everywhere: coloured frame, info at the top, Back / Prev / Search / Next / Filter / Close at
  the bottom. Anything destructive asks first. Bans and jails ask for the time and reason, then confirm with the
  player's head. Bedrock players get native forms for typing and for yes/no questions.

## Building (Windows + VS Code)

1. Install **JDK 21** (for example Temurin 21) and the VS Code "Extension Pack for Java".
2. Open this folder in VS Code, then in a terminal:
   ```
   gradlew build            (Windows: .\gradlew.bat build)
   ```
   The mod jar is `build/libs/vigil-2.0.0.jar`. SQLite and the core module are bundled inside it.
3. Other useful tasks:
   ```
   gradlew :core:test       run the unit tests (no Minecraft needed)
   gradlew runServer        start a dev server with the mod
   gradlew runGametest      start a headless server, run the in-game tests, exit
   ```
   `runGametest` writes results to `build/gametest/junit.xml`.

### Changing Minecraft version

Edit `gradle.properties`: `minecraft_version`, `yarn_mappings`, `loader_version`, `fabric_version` (take matching
values from https://fabricmc.net/develop). 1.21.5+ changed several APIs (NBT getters return `Optional`, click events
became records, and so on), so expect more fixes there. Check that Geyser-Fabric and Floodgate support the exact
version.

## First-time setup on a server

1. Put the jar in `mods/` together with **Fabric API**. Optional: **Geyser-Fabric + Floodgate** (Bedrock players),
   **LuckPerms / fabric-permissions-api** (permission nodes `vigil.*`, old `anticheat.*` also work; otherwise op level 3 = admin).
2. Start the server once, then in the **server console**: `ac setowner <YourName>`.
3. In game, each admin sets a PIN: `/vigil pin set 1234`, then uses `/login 1234` each time they join (section 15).
4. Build what you need:
   * Lobby: `/claim wand`, select two corners, `/lobby set`, stand at the spawn, `/lobby setspawn`.
   * Waiting room: build it, optionally select it with the Claim Stick, stand inside, `/waitingroom set`.
     Everyone who has already played is accepted automatically.
   * Jail cells: `/jail setcell <name>` inside each cell.
   * Spawn protection: select it, `/claim spawn`.
   * Arenas: select the area, `/arena create <name>`, then use the menu to set spawns and save blocks.
   * Traders: `/trader stick`, right-click a block.
5. Settings: `config/vigil/config.json` (every threshold and toggle), `/settings` in game, `/vigil reload`.
   Messages: `config/vigil/lang/en_us.json` or `ar_sa.json` override the bundled files; set
   `general.language` to `ar_sa` for Arabic.

Data lives in `config/vigil/`: `vigil.db` (SQLite: state and logs), `evidence/` (clip files, also the export
format), `snapshots/` (arena and claim block snapshots), `backups/` (daily database copies and world zips).

## How the detection works (short version)

* Checks add **violation points**. Points fade over time and give a **suspicion score** (0–100: green, yellow,
  red). The system **never kicks, bans or mutes on its own**. At the review threshold it opens a **review case** with
  evidence, and admins decide. The only automatic action is a **setback** (pulling back impossible movement).
* **Movement**: for each packet the server works out the most a player could have moved from vanilla physics
  (friction, acceleration, gravity/drag, jump velocity, step height, attributes that include Speed, Soul Speed,
  Depth Strider and so on), plus grace for knockback/explosions/wind charges (read from the packets the server
  sends), teleports, respawns, dimension changes, low TPS, unloaded chunks, climbing, liquids, cobwebs, powder snow,
  scaffolding, bubble columns, levitation, slime/bed bounces, pistons, riptide, elytra and fireworks. Every check
  goes through a buffer, so one odd packet never counts. Bedrock players get more lenient limits. This is an
  **upper-bound predictor, not a full tick-by-tick simulation** like Grim's. It is designed never to false-flag, at
  the cost of letting small speed boosts through. `config.movement` has the tolerances.
* **Combat**: lag-compensated reach from the target's position history, line of sight to the hitbox,
  multi-target angles, rotation analysis (snaps, too-steady rotation, perfect centre aim, missing mouse steps),
  attacks with a container open, while eating or blocking, and a strict autoclicker (CPS, low variation, uniform
  random delays, repeating patterns, sudden switches to steady clicking, 3+ clicks in one tick). Legit jitter and
  butterfly clicking are covered by tests.
* **Anti-x-ray**: enclosed ores are sent as stone. Fake diamond veins are sent in hidden spots (they never exist
  in the world). Blocks are revealed as soon as they get an open face.

## Commands

| Command | Who | Purpose |
|---|---|---|
| `/review [id]` | admin | Review queue / a case |
| `/inspect <player> [spectate\|tp [visible\|invisible]\|inv\|ender]`, `/inspect leave` | admin | Inspect, spectate, teleport |
| `/whereis <player>` | admin | Exact coordinates with teleport links |
| `/watch add <player> [reason] [time]`, `remove`, `list`, `history` | admin | Watchlist |
| `/exempt add\|remove\|list`, `/exempt setbacks <player> on\|off` | owner | System-exempt list |
| `/freeze <player>` | admin | Freeze / unfreeze |
| `/warn`, `/mute <p> <time>`, `/unmute`, `/kick`, `/tempban <p> <time>`, `/ban`, `/unban` | admin | Punishments (reason templates: hacking, griefing, spam, toxicity, xray) |
| `/report <player> <reason>`, `/report list`, `/report close <id>` | all / admin | Player reports |
| `/vanish`, `/sc [message]`, `/login <pin>`, `/maintenance on\|off` | admin | Staff tools |
| `/rollback <player> <time> [radius]`, `/restore ...` | admin | Undo / redo block changes |
| `/deaths <player>` | admin | Death logs, teleport, restore once |
| `/lag`, `/settings` | admin | Lag finder, live settings menu |
| `/claim wand\|create <name> <time>\|menu\|who <name>\|near\|spawn` | admin | Claims |
| `/barrier create <name> [radius [circle\|box\|sphere [time]]]`, `remove`, `list`, `adminpass` | admin | Barriers |
| `/lobby`, `/lobby set\|setspawn\|edit\|chest <view\|take\|loot>` | all / admin | Lobby |
| `/jail <player> <time> <reason>`, `/unjail`, `/jail list\|setcell\|delcell` | admin | Jail |
| `/arena join [mode] [kit]\|leave\|spectate <name>\|stats`, `/duel <player> [kit]` | all | Arenas |
| `/arena create <name>\|menu\|kit save <name>` | admin | Arena setup |
| `/trader stick\|create\|list\|edit <n>\|remove <n>` | admin | Traders |
| `/trade <player>`, `/trade accept\|deny <player>` | all | Secure trading |
| `/request join`, `/requests [accept\|deny\|tp <player>]`, `/waitingroom set` | new players / admin | Verification |
| `/caught` | all | Public "cheaters caught" counter (toggle) |
| `/vigil` (aliases `/vg`, `/ac`) | admin | The Vigil Panel |
| `/vigil help [page\|command]` | admin | Clickable help |
| `/vigil reload\|alerts\|stats\|log [player]\|pin set\|pin clear\|admin add\|remove\|tempadmin\|tp\|export <clip>\|farm\|event\|restart\|backup\|inspector` | admin/owner | Admin root |
| `/vigil <command>` | admin | Every admin command also works under `/vigil` (e.g. `/vigil jail ...`) |
| `/vigil watcher on\|off\|summon\|night\|log\|exclude` | owner | The Watcher |
| `/language [auto\|english\|arabic]` | all | Your language |

Builds that come with the mod are copied into `config/vigil/builds` on start: `lobby_enraze` is the "Lobby" map
by EnrazeGames (a sky island, converted from 1.10 to 1.21; its "Map built by EnrazeGames" sign is left out).
Stand where its spawn spot should go (high up, it hangs about 28 blocks below you), then `/build load lobby_enraze` and `/build paste confirm`.

Bedrock names with a prefix or spaces work everywhere. Put them in quotes: `/inspect ".Steve Two"`.

## Code layout

```
core/                     Minecraft-independent logic + unit tests (gradlew :core:test)
  detect/                 violation points, suspicion, warnings, watchlist, exempt, shadow, detection engine
  movement/  combat/      movement prediction + setback safety; reach, aim, autoclicker
  evidence/ review/       evidence recorder and clips; review cases
  claims/ barrier/ redstone/ blocklog/ xray/ items/ chat/ joins/
  staff/ deaths/ lobby/ jail/ waiting/ arena/ trader/ trade/ extras/ storage/ lang/ config/ perm/
src/main/java/.../anticheat   (package name kept; the mod id is vigil)
  Ac.java                 all services for the running server, saving
  feature/                gameplay wiring (movement, combat, claims, traders, arenas, ...)
  ui/                     Vigil look: colours, symbols, buttons, sounds, boss bars, per-player language
  gui/                    chest menus (standard layout, work on Bedrock through Geyser; forms for Bedrock)
  command/                commands
  mixin/                  hooks into Minecraft
  platform/               optional Floodgate / permissions-api bridges (reflection, no hard dependency)
  gametest/               in-game tests for runGametest
```

## Testing checklist (spec section 32)

Automated now: unit tests for every rule in `core`, plus game tests for anti-x-ray, claim borders, safe
setbacks and block snapshots. Still to do by hand on your test server (see spec section 32): parkour/ice/elytra/
wind-charge/mace no-false-flag runs, a Bedrock device through Geyser, a test cheat client on a private server,
and the disconnect/crash-during-trade/arena/jail cases. Items in trade and trader windows are saved as escrow on
every change and returned at next login after a crash. Arena snapshots are written to disk before entering and
restored exactly once.
