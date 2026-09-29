package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.claims.ClaimRole;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.feature.Claims;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Heightmap;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /claim menu (section 17). */
public final class ClaimMenu {
    private ClaimMenu() {
    }

    /** Admins can't change or delete the owner's claims (section 5). */
    static boolean mayModify(ServerPlayerEntity p, Claim c) {
        if (PermissionPolicy.canModifyClaim(Perms.effectiveRole(p), c.createdByOwner)) {
            return true;
        }
        Msg.send(p, "claim.owner-only");
        return false;
    }

    private static void changed(ServerPlayerEntity p, Claim c, String action, String detail) {
        Ac.markDirty("claims");
        Staff.log(p, "claim-" + action, null, c.name, detail);
    }

    public static void list(ServerPlayerEntity admin, int page) {
        Menu m = new Menu("§8Claims", 6).perm(Perm.CLAIM);
        m.renderer(menu -> {
            List<Claim> all = new ArrayList<>(Ac.get().claims.all());
            menu.page(all, page, c -> {
                long left = c.remaining(System.currentTimeMillis());
                int inside = Ac.get().claims.playersIn(c).size();
                return Icons.of(c.isActive() ? (c.createdByOwner ? Items.GOLDEN_SHOVEL : Items.IRON_SHOVEL) : Items.WOODEN_SHOVEL,
                        (c.isActive() ? "§a" : "§7") + c.name + (c.isActive() ? "" : " §8(archived)"),
                        c.world + " " + c.minX + "," + c.minZ + " → " + c.maxX + "," + c.maxZ,
                        "Time left: " + (left == Durations.PERMANENT ? "permanent" : Durations.format(left)) + (c.paused ? " §e(paused)" : ""),
                        c.members.size() + " members • " + inside + " inside now",
                        c.createdByOwner ? "§6Owner's claim" : "", "§eClick to manage");
            }, c -> (a, cl) -> claim(a, c), pg -> list(admin, pg));
        });
        m.open(admin);
    }

    public static void claim(ServerPlayerEntity admin, Claim c) {
        Menu m = new Menu("§8Claim: " + c.name, 6).perm(Perm.CLAIM).live();
        m.renderer(menu -> {
            long now = System.currentTimeMillis();
            long left = c.remaining(now);
            menu.icon(4, Icons.of(Items.FILLED_MAP, "§a" + c.name, c.world, c.minX + "," + c.minZ + " → " + c.maxX + "," + c.maxZ,
                    "Area: " + c.area() + " blocks", "Status: " + c.status, "Visibility: " + c.visibility,
                    "Time left: " + (left == Durations.PERMANENT ? "permanent" : Durations.format(left)) + (c.paused ? " (paused)" : ""),
                    c.eventLocked ? "§cEVENT LOCKED" : ""));
            menu.set(10, Icons.of(Items.NAME_TAG, "§eRename"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Prompts.ask(p, "New claim name:", name -> {
                    String old = c.name;
                    if (Ac.get().claims.rename(c, name)) {
                        changed(p, c, "rename", old + " -> " + name);
                    } else {
                        Msg.send(p, "claim.name-taken");
                    }
                    claim(p, c);
                });
            });
            menu.set(11, Icons.of(Items.END_ROD, "§eShow borders", "Particles for 15 seconds"), (p, cl) -> {
                BorderView.show(p, c, 15);
                p.closeHandledScreen();
            });
            menu.set(12, Icons.of(Items.PLAYER_HEAD, "§eMembers (" + c.members.size() + ")", "Managers, builders, visitors, passes"), (p, cl) -> members(p, c));
            menu.set(13, Icons.of(Items.COMPARATOR, "§eSettings", "PvP, mobs, fire, explosions, doors, messages"), (p, cl) -> settings(p, c));
            menu.set(14, Icons.of(Items.CLOCK, "§eTimer", "Extend, shorten, pause"), (p, cl) -> timer(p, c));
            menu.set(15, Icons.of(Items.ENDER_PEARL, "§bTeleport", "Left: your default, right: the other"), Perm.TELEPORT, (p, cl) -> {
                ServerWorld w = Mc.world(Ac.server(), c.world);
                if (w == null) return;
                int x = (c.minX + c.maxX) / 2;
                int z = (c.minZ + c.maxZ) / 2;
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                p.closeHandledScreen();
                boolean inv = Ac.get().staff.teleportInvisible(p.getUuid(), Ac.config().staff.teleportInvisibleByDefault) != cl.isRight();
                StaffTools.teleportTo(p, w, new com.vylorq.anticheat.core.util.Vec3(x + 0.5, y, z + 0.5), inv, "claim " + c.name);
            });
            List<String> inside = new ArrayList<>();
            for (UUID id : Ac.get().claims.playersIn(c)) {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                if (o == null) {
                    continue;
                }
                ClaimRole r = c.roleOf(id, now);
                boolean watched = Ac.get().watchlist.isWatched(id);
                inside.add((watched ? "§d⚑ " : "§f") + o.getGameProfile().getName() + " §7(" + (r == null ? "no role" : r.name().toLowerCase()) + ")");
            }
            menu.icon(16, Icons.of(Items.SPYGLASS, "§eInside now (" + inside.size() + ")", inside.isEmpty() ? List.of("§7Nobody") : inside));
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
            List<String> log = new ArrayList<>();
            for (int i = c.entryLog.size() - 1; i >= 0 && log.size() < 15; i--) {
                Claim.EntryLogEntry e = c.entryLog.get(i);
                long stayed = (e.leftAt == 0 ? now : e.leftAt) - e.enteredAt;
                log.add("§7" + f.format(new Date(e.enteredAt)) + " §f" + e.name + " §7stayed " + Durations.format(stayed) + (e.leftAt == 0 ? " §a(inside)" : ""));
            }
            menu.icon(19, Icons.of(Items.WRITABLE_BOOK, "§eEntry log", log.isEmpty() ? List.of("§7No visits") : log));
            menu.set(20, Icons.toggle(c.visibility == Claim.Visibility.PUBLIC, "Public (others can walk in)"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                c.visibility = c.visibility == Claim.Visibility.PUBLIC ? Claim.Visibility.PRIVATE : Claim.Visibility.PUBLIC;
                changed(p, c, "visibility", c.visibility.name());
            });
            menu.set(21, Icons.toggle(c.eventLocked, "Event lock", "Nothing can change inside"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                c.eventLocked = !c.eventLocked;
                changed(p, c, "event-lock", String.valueOf(c.eventLocked));
            });
            menu.set(22, Icons.of(Items.BARRIER, "§eClaim bans (" + c.bans.size() + ")", "Left: ban a player", "Right: unban a player"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Prompts.ask(p, cl.isRight() ? "Player to unban from " + c.name + ":" : "Player to ban from " + c.name + ":", name -> {
                    UUID id = Ac.get().joins.findByName(name);
                    if (id == null) {
                        Msg.send(p, "general.unknown-player", name);
                        return;
                    }
                    if (cl.isRight()) {
                        c.bans.remove(id);
                    } else {
                        c.bans.add(id);
                        ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                        if (o != null) {
                            Claims.enforceInside(o);
                        }
                    }
                    changed(p, c, cl.isRight() ? "unban" : "ban", name);
                    claim(p, c);
                });
            });
            menu.set(23, Icons.of(Items.STRUCTURE_VOID, "§eSnapshots (" + c.snapshots.size() + ")", "Left: save a snapshot now", "Right: restore the latest"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                ServerWorld w = Mc.world(Ac.server(), c.world);
                if (w == null) return;
                Area area = new Area(c.world, c.minX, w.getBottomY(), c.minZ, c.maxX, w.getTopYInclusive(), c.maxZ);
                try {
                    if (cl.isRight()) {
                        if (c.snapshots.isEmpty()) {
                            Msg.send(p, "claim.no-snapshot");
                            return;
                        }
                        int n = BlockSnapshots.restore(w, c.snapshots.get(c.snapshots.size() - 1));
                        changed(p, c, "snapshot-restore", n + " blocks");
                        Msg.send(p, "claim.snapshot-restored", n);
                    } else {
                        String name = "claim-" + c.id + "-" + System.currentTimeMillis();
                        int n = BlockSnapshots.save(w, area, name);
                        c.snapshots.add(name);
                        changed(p, c, "snapshot-save", n + " blocks");
                        Msg.send(p, "claim.snapshot-saved", n);
                    }
                } catch (Exception e) {
                    Msg.send(p, "claim.snapshot-failed", e.getMessage());
                }
            });
            menu.set(24, Icons.of(Items.DAYLIGHT_DETECTOR, "§eScheduled protection: §f" + c.schedule.name(),
                    "ALWAYS, NIGHT, DAY, MEMBERS_OFFLINE, HOURS", "Right-click with HOURS: set hours"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                if (cl.isRight() && c.schedule == Claim.Schedule.HOURS) {
                    Prompts.ask(p, "Protected hours, e.g. 20-6:", txt -> {
                        String[] parts = txt.trim().split("-");
                        try {
                            c.scheduleStartHour = Integer.parseInt(parts[0].trim()) % 24;
                            c.scheduleEndHour = Integer.parseInt(parts[1].trim()) % 24;
                            changed(p, c, "schedule-hours", txt);
                        } catch (Exception e) {
                            Msg.send(p, "general.bad-number");
                        }
                        claim(p, c);
                    });
                    return;
                }
                Claim.Schedule[] all = Claim.Schedule.values();
                c.schedule = all[(c.schedule.ordinal() + 1) % all.length];
                changed(p, c, "schedule", c.schedule.name());
            });
            menu.set(25, Icons.of(Items.PAPER, "§eTemplates", "Left: apply a template", "Right: save members as a template"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Prompts.ask(p, "Template name:", name -> {
                    if (cl.isRight()) {
                        Ac.get().claims.saveTemplate(name, c);
                        changed(p, c, "template-save", name);
                    } else if (Ac.get().claims.applyTemplate(name, c)) {
                        changed(p, c, "template-apply", name);
                    } else {
                        Msg.send(p, "claim.no-template", name);
                    }
                    claim(p, c);
                });
            });
            if (!c.isActive()) {
                menu.set(30, Icons.of(Items.LIME_CONCRETE, "§aReactivate", "Starts with NO members", "Left: 1 day, right: permanent"), (p, cl) -> {
                    if (!mayModify(p, c)) return;
                    if (Ac.get().claims.reactivate(c, cl.isRight() ? Durations.PERMANENT : Durations.DAY, Ac.config().claims.minGap)) {
                        changed(p, c, "reactivate", "");
                    } else {
                        Msg.send(p, "claim.overlap");
                    }
                });
                menu.set(31, Icons.of(Items.PLAYER_HEAD, "§eRestore previous members (" + c.previousMembers.size() + ")"), (p, cl) -> {
                    if (!mayModify(p, c)) return;
                    int n = Ac.get().claims.restorePreviousMembers(c);
                    changed(p, c, "restore-members", n + " members");
                });
            }
            menu.set(40, Icons.of(Items.TNT, "§cDelete claim", "Asks for confirmation"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Menu confirm = new Menu("§8Delete " + c.name + "?", 1).perm(Perm.CLAIM);
                confirm.renderer(cm -> {
                    cm.set(2, Icons.of(Items.LIME_CONCRETE, "§aYes, delete it"), (pp, ccl) -> {
                        if (!mayModify(pp, c)) return;
                        Ac.get().claims.delete(c.id);
                        changed(pp, c, "delete", "");
                        list(pp, 0);
                    });
                    cm.set(6, Icons.of(Items.RED_CONCRETE, "§cNo"), (pp, ccl) -> claim(pp, c));
                });
                confirm.open(p);
            });
            menu.set(45, Menu.back(), (p, cl) -> list(p, 0));
        });
        m.open(admin);
    }

    public static void members(ServerPlayerEntity admin, Claim c) {
        Menu m = new Menu("§8Members: " + c.name, 6).perm(Perm.CLAIM);
        m.renderer(menu -> {
            List<Map.Entry<UUID, Claim.Member>> list = new ArrayList<>(c.members.entrySet());
            menu.page(list, 0, e -> Icons.head(e.getKey(), e.getValue().name, "§f" + e.getValue().name,
                    "Role: §e" + e.getValue().role.name(),
                    e.getValue().expiresAt == Durations.PERMANENT ? "Permanent" : "Pass ends in " + Durations.formatRemaining(e.getValue().expiresAt, System.currentTimeMillis()),
                    "Left: change role", "Right: remove"), e -> (p, cl) -> {
                if (!mayModify(p, c)) return;
                if (cl.isRight()) {
                    Ac.get().claims.removeMember(c, e.getKey());
                    changed(p, c, "member-remove", e.getValue().name);
                } else {
                    ClaimRole[] roles = ClaimRole.values();
                    e.getValue().role = roles[(e.getValue().role.ordinal() + 1) % roles.length];
                    changed(p, c, "member-role", e.getValue().name + " " + e.getValue().role);
                }
                members(p, c);
            }, pg -> { });
            menu.set(46, Icons.of(Items.EMERALD, "§aAdd member", "Then choose a role"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Prompts.ask(p, "Player name and role, e.g. \"Steve builder\" or \"Alex visitor 3h\" (timed pass):", txt -> {
                    String[] parts = txt.trim().split("\\s+");
                    if (parts.length < 2) {
                        Msg.send(p, "claim.member-usage");
                        return;
                    }
                    String roleTxt = parts[parts.length - 1];
                    long pass = Durations.PERMANENT;
                    int nameEnd = parts.length - 1;
                    if (Durations.isDuration(roleTxt) && parts.length >= 3) {
                        pass = Durations.parse(roleTxt).getAsLong();
                        roleTxt = parts[parts.length - 2];
                        nameEnd = parts.length - 2;
                    }
                    String name = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, nameEnd));
                    ClaimRole role;
                    try {
                        role = ClaimRole.valueOf(roleTxt.toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        Msg.send(p, "claim.member-usage");
                        return;
                    }
                    UUID id = Ac.get().joins.findByName(name);
                    if (id == null) {
                        Msg.send(p, "general.unknown-player", name);
                        return;
                    }
                    Ac.get().claims.setMember(c, id, Ac.get().joins.name(id), role, pass);
                    changed(p, c, "member-add", name + " " + role + (pass == Durations.PERMANENT ? "" : " for " + Durations.format(pass)));
                    members(p, c);
                });
            });
            menu.set(45, Menu.back(), (p, cl) -> claim(p, c));
        });
        m.open(admin);
    }

    public static void settings(ServerPlayerEntity admin, Claim c) {
        Menu m = new Menu("§8Settings: " + c.name, 3).perm(Perm.CLAIM);
        m.renderer(menu -> {
            Claim.Settings s = c.settings;
            flag(menu, 10, c, "PvP", s.pvp, v -> s.pvp = v);
            flag(menu, 11, c, "Mob spawning", s.mobSpawning, v -> s.mobSpawning = v);
            flag(menu, 12, c, "Fire spread", s.fireSpread, v -> s.fireSpread = v);
            flag(menu, 13, c, "Explosions", s.explosions, v -> s.explosions = v);
            flag(menu, 14, c, "Visitors use doors & buttons", s.visitorDoors, v -> s.visitorDoors = v);
            flag(menu, 15, c, "Entry/leave messages", s.entryMessages, v -> s.entryMessages = v);
            flag(menu, 16, c, "Entry alerts", s.alerts, v -> s.alerts = v);
            menu.set(18, Menu.back(), (p, cl) -> claim(p, c));
        });
        m.open(admin);
    }

    private static void flag(Menu menu, int slot, Claim c, String name, boolean v, java.util.function.Consumer<Boolean> set) {
        menu.set(slot, Icons.toggle(v, name), (p, cl) -> {
            if (!mayModify(p, c)) return;
            set.accept(!v);
            changed(p, c, "setting", name + " " + !v);
            menu.refresh();
        });
    }

    public static void timer(ServerPlayerEntity admin, Claim c) {
        Menu m = new Menu("§8Timer: " + c.name, 3).perm(Perm.CLAIM).live();
        m.renderer(menu -> {
            ClaimManager cm = Ac.get().claims;
            long left = c.remaining(System.currentTimeMillis());
            menu.icon(4, Icons.of(Items.CLOCK, "§eTime left: §f" + (left == Durations.PERMANENT ? "permanent" : Durations.format(left)),
                    c.paused ? "§ePaused" : ""));
            String[] quick = {"1h", "6h", "1d", "2d", "7d", "permanent"};
            for (int i = 0; i < quick.length; i++) {
                String q = quick[i];
                menu.set(10 + i, Icons.of(Items.PAPER, "§aSet to " + q, "Replaces the current timer"), (p, cl) -> {
                    if (!mayModify(p, c)) return;
                    cm.setDuration(c, Durations.parse(q).getAsLong());
                    changed(p, c, "timer-set", q);
                });
            }
            menu.set(19, Icons.of(Items.LIME_DYE, "§a+1 hour", "Right: +1 day"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                cm.extend(c, cl.isRight() ? Durations.DAY : Durations.HOUR);
                changed(p, c, "timer-extend", cl.isRight() ? "1d" : "1h");
            });
            menu.set(20, Icons.of(Items.RED_DYE, "§c-1 hour", "Right: -1 day"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                cm.extend(c, -(cl.isRight() ? Durations.DAY : Durations.HOUR));
                changed(p, c, "timer-shorten", cl.isRight() ? "1d" : "1h");
            });
            menu.set(22, Icons.of(c.paused ? Items.LIME_CONCRETE : Items.YELLOW_CONCRETE, c.paused ? "§aResume timer" : "§ePause timer"), (p, cl) -> {
                if (!mayModify(p, c)) return;
                cm.pause(c, !c.paused);
                changed(p, c, c.paused ? "timer-pause" : "timer-resume", "");
            });
            menu.set(18, Menu.back(), (p, cl) -> claim(p, c));
        });
        m.open(admin);
    }

    /** Timed particle outlines for one admin. */
    public static final class BorderView {
        private static final java.util.Map<UUID, Object[]> SHOWING = new java.util.concurrent.ConcurrentHashMap<>();

        public static void show(ServerPlayerEntity p, Claim c, int seconds) {
            SHOWING.put(p.getUuid(), new Object[]{c, System.currentTimeMillis() + seconds * 1000L});
        }

        public static void tick() {
            long now = System.currentTimeMillis();
            SHOWING.entrySet().removeIf(e -> {
                ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(e.getKey());
                if (p == null || (long) e.getValue()[1] < now) {
                    return true;
                }
                Claim c = (Claim) e.getValue()[0];
                Claims.outline(p, c.minX, c.minZ, c.maxX, c.maxZ, false);
                return false;
            });
        }
    }
}
