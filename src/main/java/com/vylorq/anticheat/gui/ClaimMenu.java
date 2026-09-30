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
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Heightmap;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
        com.vylorq.anticheat.ui.Sounds.play(p, com.vylorq.anticheat.ui.Sounds.Ui.SUCCESS);
    }

    private static String timeLeft(Claim c) {
        long left = c.remaining(System.currentTimeMillis());
        String t = left == Durations.PERMANENT ? Msg.tr("ui.permanent") : Theme.Sym.CLOCK.sp() + Msg.tr("ui.left", Durations.format(left));
        return c.paused ? t + " (" + Msg.tr("cm.paused") + ")" : t;
    }

    private static void status(Btn b, Claim c) {
        if (!c.isActive()) {
            b.status(Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr("cm.archived"));
        } else if (c.paused) {
            b.status(Theme.GOLD, Theme.Sym.DOT.sp() + Msg.tr("cm.paused"));
        } else {
            b.status(Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr("panel.active"));
        }
    }

    public static void list(ServerPlayerEntity admin, int page) {
        Menu m = Menu.std(Category.CLAIMS, Msg.trFor(admin, "cat.claims")).perm(Perm.CLAIM);
        m.renderer(menu -> {
            List<Claim> all = new ArrayList<>(Ac.get().claims.all());
            long now = System.currentTimeMillis();
            long active = all.stream().filter(Claim::isActive).count();
            long soon = all.stream().filter(c -> c.isActive() && !c.paused && c.remaining(now) != Durations.PERMANENT && c.remaining(now) < Durations.DAY).count();
            menu.info(Btn.of(Items.STICK).name(Category.CLAIMS, Msg.tr("cat.claims")).desc(Msg.tr("panel.desc.claims"))
                    .line(Msg.tr("cm.summary", active, soon)).hint(Msg.tr("cm.create-hint")).build());
            menu.list(all, c -> {
                        int inside = Ac.get().claims.playersIn(c).size();
                        Btn b = Btn.of(c.isActive() ? (c.createdByOwner ? Items.GOLDEN_SHOVEL : Items.IRON_SHOVEL) : Items.WOODEN_SHOVEL)
                                .name(Category.CLAIMS, c.name);
                        status(b, c);
                        return b.line(timeLeft(c))
                                .line(c.world.replace("minecraft:", "") + " " + c.minX + "," + c.minZ + " → " + c.maxX + "," + c.maxZ)
                                .line(Msg.tr("cm.members-inside", c.members.size(), inside))
                                .lines(List.of(c.createdByOwner ? Msg.tr("cm.owners-claim") : ""))
                                .left(Msg.tr("ui.action.open")).glint(inside > 0 && c.settings.alerts).build();
                    }, c -> (a, cl) -> claim(a, c),
                    c -> c.name + " " + c.world,
                    List.of(Menu.Filter.sort(Msg.tr("cm.filter.time"), Comparator.comparingLong((Claim c) -> c.isActive() ? c.remaining(now) : Long.MAX_VALUE)),
                            Menu.Filter.sort(Msg.tr("panel.filter.name"), Comparator.comparing((Claim c) -> c.name.toLowerCase(Locale.ROOT))),
                            Menu.Filter.of(Msg.tr("panel.filter.active"), Claim::isActive),
                            Menu.Filter.of(Msg.tr("cm.archived"), c -> !c.isActive())),
                    Msg.tr("cm.empty"), Msg.tr("cm.empty-hint"));
        });
        m.open(admin);
    }

    public static void claim(ServerPlayerEntity admin, Claim c) {
        Menu m = Menu.std(Category.CLAIMS, Msg.trFor(admin, "cat.claims"), c.name).perm(Perm.CLAIM).live();
        m.renderer(menu -> {
            long now = System.currentTimeMillis();
            Btn info = Btn.of(Items.FILLED_MAP).name(Category.CLAIMS, c.name);
            status(info, c);
            menu.info(info.line(timeLeft(c))
                    .line(c.world.replace("minecraft:", "") + " " + c.minX + "," + c.minZ + " → " + c.maxX + "," + c.maxZ)
                    .line(Msg.tr("cm.area", c.area()))
                    .line(Msg.tr(c.visibility == Claim.Visibility.PUBLIC ? "cm.public" : "cm.private"))
                    .lines(List.of(c.eventLocked ? Theme.Sym.WARN.sp() + Msg.tr("cm.event-locked") : "")).build());
            menu.set(10, Btn.of(Items.NAME_TAG).name(Category.CLAIMS, Msg.tr("cm.rename")).desc(Msg.tr("cm.rename-desc"))
                    .left(Msg.tr("panel.action.change")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Input.text(p, Msg.tr("cm.rename"), c.name, name -> {
                    String old = c.name;
                    if (name != null && !name.isBlank() && Ac.get().claims.rename(c, name.trim())) {
                        changed(p, c, "rename", old + " -> " + name);
                        Msg.success(p, "cm.renamed", name.trim());
                    } else {
                        Msg.send(p, "claim.name-taken");
                    }
                    claim(p, c);
                });
            });
            menu.set(11, Btn.of(Items.END_ROD).name(Category.CLAIMS, Msg.tr("cm.borders")).desc(Msg.tr("cm.borders-desc"))
                    .left(Msg.tr("cm.action.show")).build(), (p, cl) -> {
                BorderView.show(p, c, 15);
                p.closeHandledScreen();
            });
            menu.set(12, Btn.of(Items.PLAYER_HEAD).name(Category.CLAIMS, Msg.tr("cm.members")).desc(Msg.tr("cm.members-desc"))
                    .line(Msg.tr("panel.count", c.members.size())).left(Msg.tr("ui.action.open")).build(), (p, cl) -> members(p, c));
            menu.set(13, Btn.of(Items.COMPARATOR).name(Category.CLAIMS, Msg.tr("cm.settings")).desc(Msg.tr("cm.settings-desc"))
                    .left(Msg.tr("ui.action.open")).build(), (p, cl) -> settings(p, c));
            menu.set(14, Btn.of(Items.CLOCK).name(Category.CLAIMS, Msg.tr("cm.timer")).desc(Msg.tr("cm.timer-desc"))
                    .line(timeLeft(c)).left(Msg.tr("ui.action.open")).build(), (p, cl) -> timer(p, c));
            menu.set(15, Btn.of(Items.ENDER_PEARL).name(Category.CLAIMS, Msg.tr("rv.teleport")).desc(Msg.tr("cm.tp-desc"))
                    .left(Msg.tr("rv.action.tp-default")).right(Msg.tr("rv.action.tp-other")).build(), Perm.TELEPORT, (p, cl) -> {
                ServerWorld w = Mc.world(Ac.server(), c.world);
                if (w == null) return;
                int x = (c.minX + c.maxX) / 2;
                int z = (c.minZ + c.maxZ) / 2;
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                p.closeHandledScreen();
                boolean inv = Ac.get().staff.teleportInvisible(p.getUuid(), Ac.config().staff.teleportInvisibleByDefault) != cl.isRight();
                StaffTools.teleportTo(p, w, new com.vylorq.anticheat.core.util.Vec3(x + 0.5, y, z + 0.5), inv, "claim " + c.name);
            });
            Btn inside = Btn.of(Items.SPYGLASS).name(Category.CLAIMS, Msg.tr("cm.inside"));
            int count = 0;
            for (UUID id : Ac.get().claims.playersIn(c)) {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                if (o == null) {
                    continue;
                }
                count++;
                ClaimRole r = c.roleOf(id, now);
                boolean watched = Ac.get().watchlist.isWatched(id);
                inside.status(watched ? Theme.VIOLET : Theme.WHITE, (watched ? Theme.Sym.WARN.sp() : "") + o.getGameProfile().name()
                        + " (" + (r == null ? Msg.tr("cm.no-role") : Msg.tr("cm.role." + r.name().toLowerCase(Locale.ROOT))) + ")");
            }
            if (count == 0) {
                inside.line(Msg.tr("panel.empty.players"));
            }
            menu.icon(16, inside.build());
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
            Btn log = Btn.of(Items.WRITABLE_BOOK).name(Category.CLAIMS, Msg.tr("cm.entry-log"));
            int n = 0;
            for (int i = c.entryLog.size() - 1; i >= 0 && n < 12; i--, n++) {
                Claim.EntryLogEntry e = c.entryLog.get(i);
                long stayed = (e.leftAt == 0 ? now : e.leftAt) - e.enteredAt;
                log.line(f.format(new Date(e.enteredAt)) + " " + e.name + " · " + Durations.format(stayed) + (e.leftAt == 0 ? " (" + Msg.tr("cm.inside-now") + ")" : ""));
            }
            if (n == 0) {
                log.line(Msg.tr("cm.no-visits"));
            }
            menu.icon(19, log.build());
            boolean pub = c.visibility == Claim.Visibility.PUBLIC;
            menu.set(20, Btn.of(pub ? Items.LIME_DYE : Items.GRAY_DYE).name(Category.CLAIMS, Msg.tr("cm.public-toggle")).desc(Msg.tr("cm.public-desc"))
                    .onOff(pub).left(Msg.tr(pub ? "ui.action.turn-off" : "ui.action.turn-on")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                c.visibility = pub ? Claim.Visibility.PRIVATE : Claim.Visibility.PUBLIC;
                changed(p, c, "visibility", c.visibility.name());
            });
            menu.set(21, Btn.of(c.eventLocked ? Items.LIME_DYE : Items.GRAY_DYE).name(Category.CLAIMS, Msg.tr("cm.event-lock")).desc(Msg.tr("cm.event-lock-desc"))
                    .onOff(c.eventLocked).left(Msg.tr(c.eventLocked ? "ui.action.turn-off" : "ui.action.turn-on")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                c.eventLocked = !c.eventLocked;
                changed(p, c, "event-lock", String.valueOf(c.eventLocked));
            });
            menu.set(22, Btn.of(Items.BARRIER).name(Category.CLAIMS, Msg.tr("cm.bans")).desc(Msg.tr("cm.bans-desc"))
                    .line(Msg.tr("panel.count", c.bans.size())).left(Msg.tr("cm.action.ban")).right(Msg.tr("cm.action.unban")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Input.text(p, Msg.tr(cl.isRight() ? "cm.unban-who" : "cm.ban-who", c.name), "", name -> {
                    UUID id = name == null ? null : Ac.get().joins.findByName(name.trim());
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
                    Msg.success(p, cl.isRight() ? "cm.unbanned" : "cm.banned", name, c.name);
                    claim(p, c);
                });
            });
            menu.set(23, Btn.of(Items.STRUCTURE_VOID).name(Category.CLAIMS, Msg.tr("cm.snapshots")).desc(Msg.tr("cm.snapshots-desc"))
                    .line(Msg.tr("panel.count", c.snapshots.size())).left(Msg.tr("cm.action.save")).right(Msg.tr("cm.action.restore")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                ServerWorld w = Mc.world(Ac.server(), c.world);
                if (w == null) return;
                if (cl.isRight()) {
                    if (c.snapshots.isEmpty()) {
                        Msg.send(p, "claim.no-snapshot");
                        return;
                    }
                    Confirm.open(p, Category.CLAIMS, Msg.tr("cm.confirm-restore", c.name), Msg.tr("cm.confirm-restore-detail"), null, () -> {
                        try {
                            int count2 = BlockSnapshots.restore(w, c.snapshots.get(c.snapshots.size() - 1));
                            changed(p, c, "snapshot-restore", count2 + " blocks");
                            Msg.send(p, "claim.snapshot-restored", count2);
                        } catch (Exception e) {
                            Msg.send(p, "claim.snapshot-failed", e.getMessage());
                        }
                    });
                    return;
                }
                Area area = new Area(c.world, c.minX, w.getBottomY(), c.minZ, c.maxX, w.getTopYInclusive(), c.maxZ);
                try {
                    String name = "claim-" + c.id + "-" + System.currentTimeMillis();
                    int count2 = BlockSnapshots.save(w, area, name);
                    c.snapshots.add(name);
                    changed(p, c, "snapshot-save", count2 + " blocks");
                    Msg.send(p, "claim.snapshot-saved", count2);
                } catch (Exception e) {
                    Msg.send(p, "claim.snapshot-failed", e.getMessage());
                }
            });
            Btn sched = Btn.of(Items.DAYLIGHT_DETECTOR).name(Category.CLAIMS, Msg.tr("cm.schedule")).desc(Msg.tr("cm.schedule-desc"));
            for (Claim.Schedule s : Claim.Schedule.values()) {
                boolean cur = s == c.schedule;
                sched.status(cur ? Theme.GOLD_LIGHT : Theme.SOFT, (cur ? Theme.Sym.ARROW.sp() : "  ") + Msg.tr("cm.schedule." + s.name().toLowerCase(Locale.ROOT)));
            }
            sched.left(Msg.tr("settings.next-option"));
            if (c.schedule == Claim.Schedule.HOURS) {
                sched.line(Msg.tr("cm.hours", c.scheduleStartHour, c.scheduleEndHour)).right(Msg.tr("cm.action.hours"));
            }
            menu.set(24, sched.build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                if (cl.isRight() && c.schedule == Claim.Schedule.HOURS) {
                    Input.text(p, Msg.tr("cm.hours-ask"), c.scheduleStartHour + "-" + c.scheduleEndHour, txt -> {
                        try {
                            String[] parts = txt.trim().split("-");
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
            menu.set(25, Btn.of(Items.PAPER).name(Category.CLAIMS, Msg.tr("cm.templates")).desc(Msg.tr("cm.templates-desc"))
                    .left(Msg.tr("cm.action.apply")).right(Msg.tr("cm.action.save-template")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Input.text(p, Msg.tr("cm.template-name"), "", name -> {
                    if (name == null || name.isBlank()) {
                        return;
                    }
                    if (cl.isRight()) {
                        Ac.get().claims.saveTemplate(name.trim(), c);
                        changed(p, c, "template-save", name);
                        Msg.success(p, "cm.template-saved", name.trim());
                    } else if (Ac.get().claims.applyTemplate(name.trim(), c)) {
                        changed(p, c, "template-apply", name);
                        Msg.success(p, "cm.template-applied", name.trim());
                    } else {
                        Msg.send(p, "claim.no-template", name);
                    }
                    claim(p, c);
                });
            });
            if (!c.isActive()) {
                menu.set(30, Btn.of(Items.LIME_CONCRETE).name(Category.CLAIMS, Msg.tr("cm.reactivate")).desc(Msg.tr("cm.reactivate-desc"))
                        .left(Msg.tr("cm.action.one-day")).right(Msg.tr("cm.action.forever")).build(), (p, cl) -> {
                    if (!mayModify(p, c)) return;
                    if (Ac.get().claims.reactivate(c, cl.isRight() ? Durations.PERMANENT : Durations.DAY, Ac.config().claims.minGap)) {
                        changed(p, c, "reactivate", "");
                        Msg.success(p, "cm.reactivated", c.name);
                    } else {
                        Msg.send(p, "claim.overlap");
                    }
                });
                menu.set(31, Btn.of(Items.PLAYER_HEAD).name(Category.CLAIMS, Msg.tr("cm.restore-members")).desc(Msg.tr("cm.restore-members-desc"))
                        .line(Msg.tr("panel.count", c.previousMembers.size())).left(Msg.tr("cm.action.restore")).build(), (p, cl) -> {
                    if (!mayModify(p, c)) return;
                    int restored = Ac.get().claims.restorePreviousMembers(c);
                    changed(p, c, "restore-members", restored + " members");
                    Msg.success(p, "cm.members-restored", restored);
                });
            }
            menu.set(40, Btn.of(Items.TNT).name(Category.PUNISHMENTS, Msg.tr("cm.delete")).desc(Msg.tr("cm.delete-desc"))
                    .shift(Msg.tr("panel.action.delete")).build(), (p, cl) -> {
                if (!cl.isShift()) {
                    Msg.warn(p, "cm.delete-shift");
                    return;
                }
                if (!mayModify(p, c)) return;
                Confirm.open(p, Category.CLAIMS, Msg.tr("cm.confirm-delete", c.name), Msg.tr("cm.confirm-delete-detail"),
                        Btn.of(Items.FILLED_MAP).name(Category.CLAIMS, c.name).build(), () -> {
                            if (!mayModify(p, c)) return;
                            Ac.get().claims.delete(c.id);
                            changed(p, c, "delete", "");
                            Msg.success(p, "cm.deleted", c.name);
                            list(p, 0);
                        });
            });
        });
        m.open(admin);
    }

    public static void members(ServerPlayerEntity admin, Claim c) {
        Menu m = Menu.std(Category.CLAIMS, Msg.trFor(admin, "cat.claims"), c.name, Msg.trFor(admin, "cm.members")).perm(Perm.CLAIM);
        m.renderer(menu -> {
            List<Map.Entry<UUID, Claim.Member>> list = new ArrayList<>(c.members.entrySet());
            menu.info(Btn.of(Items.PLAYER_HEAD).name(Category.CLAIMS, Msg.tr("cm.members")).desc(Msg.tr("cm.members-desc"))
                    .line(Msg.tr("panel.count", list.size())).build());
            menu.set(8, Btn.of(Items.EMERALD).name(Category.CLAIMS, Msg.tr("cm.add-member")).desc(Msg.tr("cm.add-member-desc"))
                    .left(Msg.tr("settings.add")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Input.text(p, Msg.tr("cm.add-member-ask"), "", txt -> {
                    String[] parts = txt == null ? new String[0] : txt.trim().split("\\s+");
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
                        role = ClaimRole.valueOf(roleTxt.toUpperCase(Locale.ROOT));
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
                    Msg.success(p, "cm.member-added", name);
                    members(p, c);
                });
            });
            menu.list(list, e -> {
                        Claim.Member mem = e.getValue();
                        Btn b = Btn.head(e.getKey(), mem.name).name(Category.CLAIMS, mem.name);
                        for (ClaimRole r : ClaimRole.values()) {
                            boolean cur = r == mem.role;
                            b.status(cur ? Theme.GOLD_LIGHT : Theme.SOFT, (cur ? Theme.Sym.ARROW.sp() : "  ") + Msg.tr("cm.role." + r.name().toLowerCase(Locale.ROOT)));
                        }
                        b.line(mem.expiresAt == Durations.PERMANENT ? Msg.tr("ui.permanent")
                                : Msg.tr("cm.pass-ends", Durations.formatRemaining(mem.expiresAt, System.currentTimeMillis())));
                        return b.left(Msg.tr("cm.action.role")).shift(Msg.tr("settings.remove")).build();
                    },
                    e -> (p, cl) -> {
                        if (!mayModify(p, c)) return;
                        if (cl.isShift()) {
                            Confirm.open(p, Category.CLAIMS, Msg.tr("cm.confirm-remove-member", e.getValue().name, c.name),
                                    Msg.tr("cm.confirm-remove-member-detail"), Btn.head(e.getKey(), e.getValue().name).name(Category.CLAIMS, e.getValue().name).build(), () -> {
                                        Ac.get().claims.removeMember(c, e.getKey());
                                        changed(p, c, "member-remove", e.getValue().name);
                                        members(p, c);
                                    });
                            return;
                        }
                        ClaimRole[] roles = ClaimRole.values();
                        e.getValue().role = roles[(e.getValue().role.ordinal() + 1) % roles.length];
                        changed(p, c, "member-role", e.getValue().name + " " + e.getValue().role);
                        menu.refresh();
                    },
                    e -> e.getValue().name,
                    List.of(Menu.Filter.sort(Msg.tr("panel.filter.name"), Comparator.comparing((Map.Entry<UUID, Claim.Member> e) -> e.getValue().name.toLowerCase(Locale.ROOT)))),
                    Msg.tr("cm.no-members"), Msg.tr("cm.no-members-hint"));
        });
        m.open(admin);
    }

    public static void settings(ServerPlayerEntity admin, Claim c) {
        Menu m = Menu.std(Category.CLAIMS, 3, Msg.trFor(admin, "cat.claims"), c.name, Msg.trFor(admin, "cm.settings")).perm(Perm.CLAIM);
        m.renderer(menu -> {
            Claim.Settings s = c.settings;
            flag(menu, 10, c, "cm.flag.pvp", s.pvp, v -> s.pvp = v);
            flag(menu, 11, c, "cm.flag.mobs", s.mobSpawning, v -> s.mobSpawning = v);
            flag(menu, 12, c, "cm.flag.fire", s.fireSpread, v -> s.fireSpread = v);
            flag(menu, 13, c, "cm.flag.explosions", s.explosions, v -> s.explosions = v);
            flag(menu, 14, c, "cm.flag.doors", s.visitorDoors, v -> s.visitorDoors = v);
            flag(menu, 15, c, "cm.flag.messages", s.entryMessages, v -> s.entryMessages = v);
            flag(menu, 16, c, "cm.flag.alerts", s.alerts, v -> s.alerts = v);
        });
        m.open(admin);
    }

    private static void flag(Menu menu, int slot, Claim c, String key, boolean v, java.util.function.Consumer<Boolean> set) {
        menu.set(slot, Btn.of(v ? Items.LIME_DYE : Items.GRAY_DYE).name(Category.CLAIMS, Msg.tr(key)).desc(Msg.tr(key + ".desc"))
                .onOff(v).left(Msg.tr(v ? "ui.action.turn-off" : "ui.action.turn-on")).build(), (p, cl) -> {
            if (!mayModify(p, c)) return;
            set.accept(!v);
            changed(p, c, "setting", key + " " + !v);
            menu.refresh();
        });
    }

    public static void timer(ServerPlayerEntity admin, Claim c) {
        Menu m = Menu.std(Category.CLAIMS, Msg.trFor(admin, "cat.claims"), c.name, Msg.trFor(admin, "cm.timer")).perm(Perm.CLAIM).live();
        m.renderer(menu -> {
            ClaimManager cm = Ac.get().claims;
            Btn info = Btn.of(Items.CLOCK).name(Category.CLAIMS, Msg.tr("cm.timer"));
            status(info, c);
            menu.info(info.line(timeLeft(c)).build());
            String[] quick = {"1h", "6h", "1d", "2d", "7d", "permanent"};
            for (int i = 0; i < quick.length; i++) {
                String q = quick[i];
                String label = q.equals("permanent") ? Msg.tr("ui.permanent") : q;
                menu.set(19 + i, Btn.of(Items.PAPER).name(Category.CLAIMS, Msg.tr("cm.set-to", label)).desc(Msg.tr("cm.set-to-desc"))
                        .left(Msg.tr("panel.action.do")).build(), (p, cl) -> {
                    if (!mayModify(p, c)) return;
                    cm.setDuration(c, Durations.parse(q).getAsLong());
                    changed(p, c, "timer-set", q);
                });
            }
            menu.set(25, Btn.of(Items.NAME_TAG).name(Category.CLAIMS, Msg.tr("cm.custom-time")).desc(Msg.tr("settings.type-time"))
                    .left(Msg.tr("panel.action.do")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                Input.text(p, Msg.tr("cm.custom-time"), "", txt -> {
                    var d = Durations.parse(txt == null ? "" : txt.trim());
                    if (d.isEmpty()) {
                        Msg.error(p, "general.bad-duration");
                    } else {
                        cm.setDuration(c, d.getAsLong());
                        changed(p, c, "timer-set", txt);
                    }
                    timer(p, c);
                });
            });
            menu.set(29, Btn.of(Items.LIME_DYE).name(Category.CLAIMS, Msg.tr("cm.extend")).left("+1h").right("+1d").build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                cm.extend(c, cl.isRight() ? Durations.DAY : Durations.HOUR);
                changed(p, c, "timer-extend", cl.isRight() ? "1d" : "1h");
            });
            menu.set(30, Btn.of(Items.RED_DYE).name(Category.CLAIMS, Msg.tr("cm.shorten")).left("-1h").right("-1d").build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                cm.extend(c, -(cl.isRight() ? Durations.DAY : Durations.HOUR));
                changed(p, c, "timer-shorten", cl.isRight() ? "1d" : "1h");
            });
            menu.set(32, Btn.of(c.paused ? Items.LIME_CONCRETE : Items.YELLOW_CONCRETE).name(Category.CLAIMS, Msg.tr(c.paused ? "cm.resume" : "cm.pause"))
                    .desc(Msg.tr("cm.pause-desc")).left(Msg.tr("panel.action.do")).build(), (p, cl) -> {
                if (!mayModify(p, c)) return;
                cm.pause(c, !c.paused);
                changed(p, c, c.paused ? "timer-pause" : "timer-resume", "");
            });
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
