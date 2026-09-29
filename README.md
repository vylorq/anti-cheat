# Anti-Cheat & Server Management (Fabric, Minecraft 1.21.4)

A server-side Fabric mod: anti-cheat with admin review, staff tools, claims, barriers, lobby, jail, PvP arenas,
traders, secure player trading and new-player verification. Java and Bedrock (Geyser + Floodgate) players.
It is built from `ANTICHEAT_SPEC.md`.

> **Read "Status and what to check first" before using this on a real server.**

## Status and what to check first

This was written in a cloud environment that **could not download Minecraft or Fabric**
(`maven.fabricmc.net` and Mojang's servers are blocked there). So:

* **`core/`** (all the logic, about half the code) **was compiled and tested**: 101 unit tests pass
  (`gradlew :core:test`). That includes movement prediction against a vanilla-physics simulator, autoclicker
  analysis with legit jitter and butterfly clicking, claims, barriers, traders (including two players buying the last
  item at once), secure trading, death restores, SQLite storage, and the language files.
* **The Fabric layer (`src/`) has not been compiled yet.** A javac pass without Minecraft found no syntax errors, and
  every call between the mod's own classes resolves. Calls into Minecraft use Yarn 1.21.4 names from memory, so
  **expect a handful of compile errors on the first build**. These are usually a renamed method or a changed
  constructor, fixed in a line or two each. Version-sensitive calls are grouped in `util/Mc.java`, `util/ItemConv.java`
  and the `mixin/` package.
* Some mixins are marked `require = 0`. If their target method was renamed, they silently do nothing instead of
  crashing the server, and those features are then off. They are: fluid flow, fire spread, dispensers, hoppers,
  explosion filtering, TNT limits, redstone clock detection, trampling, endermen, withers, vanish entity
  tracking, silent vanish join/leave messages, UHC no-regen, trader lightning/pushing, projectiles vs barriers,
  and "obtained naturally" from crafting. After the first successful start, run the game tests
  (below) and check the log for mixin warnings.

### Things from the spec I could not do without your existing code

* **Section 2 and 3 (read the existing mod, fix command typos and duplicates):** your current mod is on your PC, not
  in this repository, so I couldn't audit it. I picked **Minecraft 1.21.4** (you mentioned it) and wrote everything
  with **mod id `anticheat`** and **package `com.vylorq.anticheat`**. See "Merging with your existing mod" below.
* **The existing x-ray detector:** rebuilt from scratch (ore hiding, fake-vein trap, mining-ratio analysis, grouped ore
  alerts). If yours has a detail worth keeping, it can go into `feature/Xray.java`.

## Building (Windows + VS Code)

1. Install **JDK 21** (for example Temurin 21) and the VS Code "Extension Pack for Java".
2. Open this folder in VS Code, then in a terminal:
   ```
   gradlew build            (Windows: .\gradlew.bat build)
   ```
   The mod jar is `build/libs/anticheat-2.0.0.jar`. SQLite and the core module are bundled inside it.
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

## Merging with your existing mod

The spec says to keep your mod id, name and package. To switch this code over to them:

1. Rename the package folders `com/vylorq/anticheat` (in both `src/` and `core/src/`) and replace
   `com.vylorq.anticheat` everywhere.
2. In `src/main/resources/fabric.mod.json`, change `"id"` and `"name"`. In `Ac.java`, change `MOD_ID`.
   Rename `anticheat.mixins.json` if you like, and update the reference in `fabric.mod.json`.
3. Go through your old command list (spec section 3) and compare it with the table below. Keep any old
   misspelled names as hidden aliases for one release.

## First-time setup on a server

1. Put the jar in `mods/` together with **Fabric API**. Optional: **Geyser-Fabric + Floodgate** (Bedrock players),
   **LuckPerms / fabric-permissions-api** (permission nodes `anticheat.*`; otherwise op level 3 = admin).
2. Start the server once, then in the **server console**: `ac setowner <YourName>`.
3. In game, each admin sets a PIN: `/ac pin set 1234`, then uses `/login 1234` each time they join (section 15).
4. Build what you need:
   * Lobby: `/claim wand`, select two corners, `/lobby set`, stand at the spawn, `/lobby setspawn`.
   * Waiting room: build it, optionally select it with the Claim Stick, stand inside, `/waitingroom set`.
     Everyone who has already played is accepted automatically.
   * Jail cells: `/jail setcell <name>` inside each cell.
   * Spawn protection: select it, `/claim spawn`.
   * Arenas: select the area, `/arena create <name>`, then use the menu to set spawns and save blocks.
   * Traders: `/trader stick`, right-click a block.
5. Settings: `config/anticheat/config.json` (every threshold and toggle), `/settings` in game, `/ac reload`.
   Messages: `config/anticheat/lang/en_us.json` or `ar_sa.json` override the bundled files; set
   `general.language` to `ar_sa` for Arabic.

Data lives in `config/anticheat/`: `anticheat.db` (SQLite: state and logs), `evidence/` (clip files, also the export
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
| `/ac reload\|alerts\|stats\|log [player]\|pin set\|pin clear\|admin add\|remove\|tp\|export <clip>\|farm add\|remove\|list\|event title\|countdown\|dropparty\|restart\|backup\|inspector` | admin/owner | Admin root |

Bedrock names with a prefix or spaces work everywhere. Put them in quotes: `/inspect ".Steve Two"`.

## Code layout

```
core/                     Minecraft-independent logic + unit tests (gradlew :core:test)
  detect/                 violation points, suspicion, warnings, watchlist, exempt, shadow, detection engine
  movement/  combat/      movement prediction + setback safety; reach, aim, autoclicker
  evidence/ review/       evidence recorder and clips; review cases
  claims/ barrier/ redstone/ blocklog/ xray/ items/ chat/ joins/
  staff/ deaths/ lobby/ jail/ waiting/ arena/ trader/ trade/ extras/ storage/ lang/ config/ perm/
src/main/java/.../anticheat
  Ac.java                 all services for the running server, saving
  feature/                gameplay wiring (movement, combat, claims, traders, arenas, ...)
  gui/                    chest menus (work on Bedrock through Geyser)
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
