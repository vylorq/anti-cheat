package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.Arena;
import com.vylorq.anticheat.core.arena.ArenaManager;
import com.vylorq.anticheat.core.arena.Kit;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Arenas;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/** Arena setup (admin) and joining (players) (section 22). */
public final class ArenaMenu {
    private ArenaMenu() {
    }

    private static void changed(ServerPlayerEntity p, Arena a, String what) {
        Ac.markDirty("arenas");
        Staff.log(p, "arena-" + what, null, a.name, "");
    }

    public static void list(ServerPlayerEntity admin) {
        Menu m = new Menu("§8Arenas", 6).perm(Perm.ARENA_ADMIN);
        m.renderer(menu -> menu.page(Ac.get().arenas.arenas(), 0, a -> Icons.of(a.enabled ? Items.IRON_SWORD : Items.WOODEN_SWORD,
                (a.enabled ? "§a" : "§7") + a.name, "Modes: " + labels(a.modes), "Spawns: " + spawnSummary(a),
                "Best of " + a.rules.bestOf + ", " + a.rules.timeLimitSeconds + "s", "§eClick to edit"),
                a -> (p, c) -> edit(p, a), pg -> { }));
        m.open(admin);
    }

    private static String labels(List<Arena.Mode> modes) {
        List<String> l = new ArrayList<>();
        for (Arena.Mode m : modes) {
            l.add(m.label());
        }
        return String.join(", ", l);
    }

    private static String spawnSummary(Arena a) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.teamSpawns.size(); i++) {
            l.add("team " + (i + 1) + ": " + a.teamSpawns.get(i).size());
        }
        return l.isEmpty() ? "none" : String.join(", ", l);
    }

    public static void edit(ServerPlayerEntity admin, Arena a) {
        Menu m = new Menu("§8Arena: " + a.name, 6).perm(Perm.ARENA_ADMIN);
        m.renderer(menu -> {
            menu.icon(4, Icons.of(Items.IRON_SWORD, "§a" + a.name, "Spawns: " + spawnSummary(a),
                    "Spectator spot: " + (a.spectatorSpot == null ? "§cnot set" : "set"),
                    "Return point: " + (a.returnPoint == null ? "§7not set" : "set"),
                    "Block reset: " + (a.snapshot != null && BlockSnapshots.exists(a.snapshot) ? "§asaved" : "§cno snapshot")));
            for (int t = 0; t < 2; t++) {
                int team = t;
                menu.set(10 + t, Icons.of(t == 0 ? Items.RED_BANNER : Items.BLUE_BANNER, "§eTeam " + (t + 1) + " spawn",
                        "Left: add a spawn at your position", "Right: clear this team's spawns",
                        "(FFA uses team 1's spawns)"), (p, c) -> {
                    while (a.teamSpawns.size() <= team) {
                        a.teamSpawns.add(new ArrayList<>());
                    }
                    List<com.vylorq.anticheat.core.util.Location> list = new ArrayList<>(a.teamSpawns.get(team));
                    if (c.isRight()) {
                        list.clear();
                    } else {
                        list.add(Mc.location(p));
                    }
                    a.teamSpawns.set(team, list);
                    changed(p, a, "spawn");
                    menu.refresh();
                });
            }
            menu.set(12, Icons.of(Items.ENDER_EYE, "§eSet spectator spot", "Your current position"), (p, c) -> {
                a.spectatorSpot = Mc.location(p);
                changed(p, a, "spectator-spot");
                menu.refresh();
            });
            menu.set(13, Icons.of(Items.COMPASS, "§eSet return point", "Where spectators go when they leave the bounds"), (p, c) -> {
                a.returnPoint = Mc.location(p);
                changed(p, a, "return-point");
                menu.refresh();
            });
            menu.set(14, Icons.of(Items.STRUCTURE_BLOCK, "§eSave blocks for reset", "Blocks are reset to this after every match"), (p, c) -> {
                ServerWorld w = Mc.world(Ac.server(), a.area.world);
                if (w == null) return;
                try {
                    a.snapshot = "arena-" + a.name.toLowerCase();
                    int n = BlockSnapshots.save(w, a.area, a.snapshot);
                    changed(p, a, "snapshot");
                    Msg.send(p, "arena.snapshot-saved", n);
                } catch (Exception e) {
                    Msg.send(p, "claim.snapshot-failed", e.getMessage());
                }
                menu.refresh();
            });
            int slot = 19;
            for (Arena.Mode mode : Arena.Mode.values()) {
                boolean on = a.modes.contains(mode);
                menu.set(slot++, Icons.toggle(on, "Mode " + mode.label()), (p, c) -> {
                    List<Arena.Mode> modes = new ArrayList<>(a.modes);
                    if (on) {
                        modes.remove(mode);
                    } else {
                        modes.add(mode);
                    }
                    a.modes = modes;
                    changed(p, a, "modes");
                    menu.refresh();
                });
            }
            menu.set(28, Icons.of(Items.GOLDEN_SWORD, "§eBest of " + a.rules.bestOf, "Cycles 1 / 3 / 5"), (p, c) -> {
                a.rules.bestOf = a.rules.bestOf == 1 ? 3 : a.rules.bestOf == 3 ? 5 : 1;
                changed(p, a, "rules");
                menu.refresh();
            });
            menu.set(29, Icons.of(Items.CLOCK, "§eTime limit: " + a.rules.timeLimitSeconds + "s", "Left: +60s, right: -60s (0 = none)"), (p, c) -> {
                a.rules.timeLimitSeconds = Math.max(0, a.rules.timeLimitSeconds + (c.isRight() ? -60 : 60));
                changed(p, a, "rules");
                menu.refresh();
            });
            menu.set(30, Icons.of(Items.WITHER_SKELETON_SKULL, "§eSudden death: " + a.rules.suddenDeath, "Cycles NONE / SHRINKING_BORDER / GLOWING"), (p, c) -> {
                Arena.SuddenDeath[] all = Arena.SuddenDeath.values();
                a.rules.suddenDeath = all[(a.rules.suddenDeath.ordinal() + 1) % all.length];
                changed(p, a, "rules");
                menu.refresh();
            });
            menu.set(31, Icons.toggle(a.rules.naturalRegen, "Natural regeneration"), (p, c) -> {
                a.rules.naturalRegen = !a.rules.naturalRegen;
                changed(p, a, "rules");
                menu.refresh();
            });
            menu.set(32, Icons.of(Items.CHEST, "§eAllowed kits", a.rules.allowedKits.isEmpty() ? "All kits" : String.join(", ", a.rules.allowedKits),
                    "Click to set (comma separated, empty = all)"), (p, c) -> Prompts.ask(p, "Allowed kits (comma separated, or 'all'):", txt -> {
                List<String> kits = new ArrayList<>();
                if (!txt.trim().equalsIgnoreCase("all")) {
                    for (String k : txt.split(",")) {
                        if (!k.isBlank()) {
                            kits.add(k.trim().toLowerCase());
                        }
                    }
                }
                a.rules.allowedKits = kits;
                changed(p, a, "rules");
                edit(p, a);
            }));
            menu.set(38, Icons.toggle(a.enabled, "Enabled"), (p, c) -> {
                a.enabled = !a.enabled;
                changed(p, a, a.enabled ? "enable" : "disable");
                menu.refresh();
            });
            menu.set(42, Icons.of(Items.TNT, "§cDelete arena", "Asks for confirmation"), (p, c) -> {
                Menu confirm = new Menu("§8Delete " + a.name + "?", 1).perm(Perm.ARENA_ADMIN);
                confirm.renderer(cm -> {
                    cm.set(2, Icons.of(Items.LIME_CONCRETE, "§aYes, delete"), (pp, cc) -> {
                        Ac.get().arenas.removeArena(a.name);
                        changed(pp, a, "delete");
                        list(pp);
                    });
                    cm.set(6, Icons.of(Items.RED_CONCRETE, "§cNo"), (pp, cc) -> edit(pp, a));
                });
                confirm.open(p);
            });
            menu.set(45, Menu.back(), (p, c) -> list(p));
        });
        m.open(admin);
    }

    /** Player menu: pick a mode and kit to queue. */
    public static void join(ServerPlayerEntity p) {
        Menu m = new Menu("§8Join an arena", 3).perm(Perm.ARENA_PLAY);
        m.renderer(menu -> {
            int slot = 10;
            for (Arena.Mode mode : Arena.Mode.values()) {
                menu.set(slot++, Icons.of(Items.IRON_SWORD, "§e" + mode.label(), "Click to pick a kit"), (pl, c) -> kits(pl, mode));
            }
            menu.set(16, Icons.of(Items.BARRIER, "§cLeave queue"), (pl, c) -> {
                Ac.get().arenas.leaveQueue(pl.getUuid());
                Msg.send(pl, "arena.left-queue");
                pl.closeHandledScreen();
            });
        });
        m.open(p);
    }

    private static void kits(ServerPlayerEntity p, Arena.Mode mode) {
        ArenaManager am = Ac.get().arenas;
        Menu m = new Menu("§8" + mode.label() + ": choose a kit", 3).perm(Perm.ARENA_PLAY);
        m.renderer(menu -> {
            int slot = 0;
            for (Kit k : am.kits()) {
                if (slot >= 18) {
                    break;
                }
                menu.set(slot++, Icons.of(Items.CHEST, "§a" + k.name, k.description.isEmpty() ? "" : k.description.get(0),
                        k.naturalRegen ? "" : "§cNo natural regeneration"), (pl, c) -> {
                    pl.closeHandledScreen();
                    Arenas.join(pl, mode, k.name);
                });
            }
            menu.set(18, Menu.back(), (pl, c) -> join(pl));
        });
        m.open(p);
    }
}
