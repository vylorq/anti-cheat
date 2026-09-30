package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.Arena;
import com.vylorq.anticheat.core.arena.ArenaManager;
import com.vylorq.anticheat.core.arena.Kit;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Arenas;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Sounds;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Arena setup (admin) and joining (players) (section 22). */
public final class ArenaMenu {
    private ArenaMenu() {
    }

    private static void changed(ServerPlayerEntity p, Arena a, String what) {
        Ac.markDirty("arenas");
        Staff.log(p, "arena-" + what, null, a.name, "");
        Sounds.play(p, Sounds.Ui.SUCCESS);
    }

    private static String labels(List<Arena.Mode> modes) {
        List<String> l = new ArrayList<>();
        for (Arena.Mode m : modes) {
            l.add(m.label());
        }
        return l.isEmpty() ? Msg.tr("rv.none") : String.join(", ", l);
    }

    private static String spawnSummary(Arena a) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.teamSpawns.size(); i++) {
            l.add(Msg.tr("am.team-spawns", i + 1, a.teamSpawns.get(i).size()));
        }
        return l.isEmpty() ? Msg.tr("rv.none") : String.join(", ", l);
    }

    public static void list(ServerPlayerEntity admin) {
        Menu m = Menu.std(Category.ARENAS, Msg.trFor(admin, "cat.arenas")).perm(Perm.ARENA_ADMIN);
        m.renderer(menu -> {
            List<Arena> arenas = Ac.get().arenas.arenas();
            menu.info(Btn.of(Items.DIAMOND_SWORD).name(Category.ARENAS, Msg.tr("cat.arenas")).desc(Msg.tr("panel.desc.arenas"))
                    .line(Msg.tr("panel.count", arenas.size())).line(Msg.tr("am.matches", Ac.get().arenas.matches().size()))
                    .hint(Msg.tr("am.create-hint")).build());
            menu.list(arenas, a -> Btn.of(a.enabled ? Items.IRON_SWORD : Items.WOODEN_SWORD).name(Category.ARENAS, a.name)
                            .onOff(a.enabled)
                            .line(Msg.tr("am.modes", labels(a.modes)))
                            .line(Msg.tr("am.spawns", spawnSummary(a)))
                            .line(Msg.tr("am.rules-line", a.rules.bestOf, a.rules.timeLimitSeconds))
                            .left(Msg.tr("panel.action.edit")).build(),
                    a -> (p, c) -> edit(p, a), a -> a.name,
                    List.of(), Msg.tr("am.empty"), Msg.tr("am.empty-hint"));
        });
        m.open(admin);
    }

    public static void edit(ServerPlayerEntity admin, Arena a) {
        Menu m = Menu.std(Category.ARENAS, Msg.trFor(admin, "cat.arenas"), a.name).perm(Perm.ARENA_ADMIN);
        m.renderer(menu -> {
            boolean snap = a.snapshot != null && BlockSnapshots.exists(a.snapshot);
            menu.info(Btn.of(Items.IRON_SWORD).name(Category.ARENAS, a.name).onOff(a.enabled)
                    .line(Msg.tr("am.spawns", spawnSummary(a)))
                    .status(a.spectatorSpot == null ? Theme.RED : Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr(a.spectatorSpot == null ? "am.spec-missing" : "am.spec-set"))
                    .status(a.returnPoint == null ? Theme.SOFT : Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr(a.returnPoint == null ? "am.return-missing" : "am.return-set"))
                    .status(snap ? Theme.GREEN : Theme.RED, Theme.Sym.DOT.sp() + Msg.tr(snap ? "am.reset-saved" : "am.reset-missing")).build());
            for (int t = 0; t < 2; t++) {
                int team = t;
                int count = a.teamSpawns.size() > t ? a.teamSpawns.get(t).size() : 0;
                menu.set(10 + t, Btn.of(t == 0 ? Items.RED_BANNER : Items.BLUE_BANNER).name(Category.ARENAS, Msg.tr("am.team-spawn", t + 1))
                        .desc(Msg.tr("am.team-spawn-desc")).line(Msg.tr("panel.count", count))
                        .left(Msg.tr("am.action.add-here")).right(Msg.tr("am.action.clear")).build(), (p, c) -> {
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
            menu.set(12, Btn.of(Items.ENDER_EYE).name(Category.ARENAS, Msg.tr("am.spectator")).desc(Msg.tr("am.spectator-desc"))
                    .left(Msg.tr("am.action.set-here")).build(), (p, c) -> {
                a.spectatorSpot = Mc.location(p);
                changed(p, a, "spectator-spot");
                menu.refresh();
            });
            menu.set(13, Btn.of(Items.COMPASS).name(Category.ARENAS, Msg.tr("am.return")).desc(Msg.tr("am.return-desc"))
                    .left(Msg.tr("am.action.set-here")).build(), (p, c) -> {
                a.returnPoint = Mc.location(p);
                changed(p, a, "return-point");
                menu.refresh();
            });
            menu.set(14, Btn.of(Items.STRUCTURE_BLOCK).name(Category.ARENAS, Msg.tr("am.reset")).desc(Msg.tr("am.reset-desc"))
                    .left(Msg.tr("cm.action.save")).build(), (p, c) -> {
                ServerWorld w = Mc.world(Ac.server(), a.area.world);
                if (w == null) return;
                try {
                    a.snapshot = "arena-" + a.name.toLowerCase(Locale.ROOT);
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
                menu.set(slot++, Btn.of(on ? Items.LIME_DYE : Items.GRAY_DYE).name(Category.ARENAS, Msg.tr("am.mode", mode.label()))
                        .onOff(on).left(Msg.tr(on ? "ui.action.turn-off" : "ui.action.turn-on")).build(), (p, c) -> {
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
            menu.set(28, Btn.of(Items.GOLDEN_SWORD).name(Category.ARENAS, Msg.tr("am.best-of")).line(Msg.tr("settings.now", a.rules.bestOf))
                    .left("1 / 3 / 5").build(), (p, c) -> {
                a.rules.bestOf = a.rules.bestOf == 1 ? 3 : a.rules.bestOf == 3 ? 5 : 1;
                changed(p, a, "rules");
                menu.refresh();
            });
            menu.set(29, Btn.of(Items.CLOCK).name(Category.ARENAS, Msg.tr("am.time-limit")).desc(Msg.tr("am.time-limit-desc"))
                    .line(Msg.tr("settings.now", a.rules.timeLimitSeconds + "s")).left("+60s").right("-60s").build(), (p, c) -> {
                a.rules.timeLimitSeconds = Math.max(0, a.rules.timeLimitSeconds + (c.isRight() ? -60 : 60));
                changed(p, a, "rules");
                menu.refresh();
            });
            Btn sd = Btn.of(Items.WITHER_SKELETON_SKULL).name(Category.ARENAS, Msg.tr("am.sudden-death"));
            for (Arena.SuddenDeath s : Arena.SuddenDeath.values()) {
                boolean cur = s == a.rules.suddenDeath;
                sd.status(cur ? Theme.GOLD_LIGHT : Theme.SOFT, (cur ? Theme.Sym.ARROW.sp() : "  ") + Msg.tr("am.sd." + s.name().toLowerCase(Locale.ROOT)));
            }
            menu.set(30, sd.left(Msg.tr("settings.next-option")).build(), (p, c) -> {
                Arena.SuddenDeath[] all = Arena.SuddenDeath.values();
                a.rules.suddenDeath = all[(a.rules.suddenDeath.ordinal() + 1) % all.length];
                changed(p, a, "rules");
                menu.refresh();
            });
            boolean regen = a.rules.naturalRegen;
            menu.set(31, Btn.of(regen ? Items.LIME_DYE : Items.GRAY_DYE).name(Category.ARENAS, Msg.tr("set.natural-regen")).onOff(regen)
                    .left(Msg.tr(regen ? "ui.action.turn-off" : "ui.action.turn-on")).build(), (p, c) -> {
                a.rules.naturalRegen = !a.rules.naturalRegen;
                changed(p, a, "rules");
                menu.refresh();
            });
            menu.set(32, Btn.of(Items.CHEST).name(Category.ARENAS, Msg.tr("am.kits")).desc(Msg.tr("am.kits-desc"))
                    .line(a.rules.allowedKits.isEmpty() ? Msg.tr("am.all-kits") : String.join(", ", a.rules.allowedKits))
                    .left(Msg.tr("panel.action.change")).build(), (p, c) -> Input.text(p, Msg.tr("am.kits-ask"),
                    a.rules.allowedKits.isEmpty() ? "all" : String.join(",", a.rules.allowedKits), txt -> {
                        List<String> kits = new ArrayList<>();
                        if (txt != null && !txt.trim().equalsIgnoreCase("all")) {
                            for (String k : txt.split(",")) {
                                if (!k.isBlank()) {
                                    kits.add(k.trim().toLowerCase(Locale.ROOT));
                                }
                            }
                        }
                        a.rules.allowedKits = kits;
                        changed(p, a, "rules");
                        edit(p, a);
                    }));
            menu.set(38, Btn.of(a.enabled ? Items.LIME_DYE : Items.GRAY_DYE).name(Category.ARENAS, Msg.tr("am.enabled")).onOff(a.enabled)
                    .left(Msg.tr(a.enabled ? "ui.action.turn-off" : "ui.action.turn-on")).build(), (p, c) -> {
                a.enabled = !a.enabled;
                changed(p, a, a.enabled ? "enable" : "disable");
                menu.refresh();
            });
            menu.set(42, Btn.of(Items.TNT).name(Category.PUNISHMENTS, Msg.tr("am.delete")).desc(Msg.tr("cm.delete-desc"))
                    .shift(Msg.tr("panel.action.delete")).build(), (p, c) -> {
                if (!c.isShift()) {
                    Msg.warn(p, "cm.delete-shift");
                    return;
                }
                Confirm.open(p, Category.ARENAS, Msg.tr("am.confirm-delete", a.name), Msg.tr("am.confirm-delete-detail"),
                        Btn.of(Items.IRON_SWORD).name(Category.ARENAS, a.name).build(), () -> {
                            Ac.get().arenas.removeArena(a.name);
                            changed(p, a, "delete");
                            Msg.success(p, "am.deleted", a.name);
                            list(p);
                        });
            });
        });
        m.open(admin);
    }

    private static Item modeIcon(Arena.Mode mode) {
        return switch (mode) {
            case ONE_V_ONE -> Items.IRON_SWORD;
            case TWO_V_TWO -> Items.DIAMOND_SWORD;
            case THREE_V_THREE -> Items.NETHERITE_SWORD;
            case FFA -> Items.TRIDENT;
        };
    }

    /** Player menu (34.9): modes as big icons with the queue size, and Leave. */
    public static void join(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.ARENAS, 3, Msg.trFor(p, "cat.arenas")).perm(Perm.ARENA_PLAY);
        m.renderer(menu -> {
            ArenaManager am = Ac.get().arenas;
            boolean queued = am.inQueue(p.getUuid());
            menu.info(Btn.of(Items.DIAMOND_SWORD).name(Category.ARENAS, Msg.tr("am.join-title")).desc(Msg.tr("am.join-desc"))
                    .status(queued ? Theme.GOLD : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr(queued ? "am.in-queue" : "am.not-queued")).build());
            int slot = 10;
            for (Arena.Mode mode : Arena.Mode.values()) {
                int waiting = am.queued(mode);
                menu.set(slot, Btn.of(modeIcon(mode)).name(Category.ARENAS, mode.label()).line(Msg.tr("am.players", mode.players))
                        .status(waiting > 0 ? Theme.GOLD : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr("am.waiting", waiting))
                        .left(Msg.tr("am.action.pick-kit")).glint(waiting > 0).build(), (pl, c) -> kits(pl, mode));
                slot += 2;
            }
            if (queued) {
                menu.set(16, Btn.of(Items.BARRIER).color(Theme.RED).name(Msg.tr("am.leave")).left(Msg.tr("am.action.leave")).build(), (pl, c) -> {
                    Ac.get().arenas.leaveQueue(pl.getUuid());
                    Msg.send(pl, "arena.left-queue");
                    menu.refresh();
                });
            }
        });
        m.open(p);
    }

    private static void kits(ServerPlayerEntity p, Arena.Mode mode) {
        ArenaManager am = Ac.get().arenas;
        Menu m = Menu.std(Category.ARENAS, Msg.trFor(p, "cat.arenas"), mode.label()).perm(Perm.ARENA_PLAY);
        m.renderer(menu -> {
            menu.info(Btn.of(modeIcon(mode)).name(Category.ARENAS, mode.label()).desc(Msg.tr("am.pick-kit-desc")).build());
            menu.list(am.kits(), k -> {
                        Btn b = Btn.of(Items.CHEST).name(Category.ARENAS, k.name);
                        for (String d : k.description) {
                            b.desc(d);
                        }
                        if (!k.naturalRegen) {
                            b.status(Theme.RED, Theme.Sym.WARN.sp() + Msg.tr("am.no-regen"));
                        }
                        return b.left(Msg.tr("am.action.join")).build();
                    },
                    k -> (pl, c) -> {
                        pl.closeHandledScreen();
                        Arenas.join(pl, mode, k.name);
                    },
                    k -> k.name, List.of(), Msg.tr("am.no-kits"), Msg.tr("am.no-kits-hint"));
        });
        m.open(p);
    }
}
