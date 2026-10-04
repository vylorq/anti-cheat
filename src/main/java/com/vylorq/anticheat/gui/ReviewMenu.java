package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.evidence.EvidenceClip;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.feature.Discord;
import com.vylorq.anticheat.feature.Jail;
import com.vylorq.anticheat.feature.Punish;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The review queue (section 6.2) and evidence timelines (section 11). */
public final class ReviewMenu {
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private ReviewMenu() {
    }

    static int suspicionColor(int s) {
        return s >= 75 ? Theme.RED : s >= 50 ? Theme.ORANGE : s >= 25 ? Theme.GOLD : Theme.GREEN;
    }

    public static void queue(ServerPlayerEntity admin, int page) {
        Menu m = Menu.std(Category.REVIEW, Msg.trFor(admin, "cat.review")).perm(Perm.REVIEW);
        m.renderer(menu -> {
            List<ReviewCase> open = Ac.get().reviews.open();
            menu.info(Btn.of(Items.WRITABLE_BOOK).name(Category.REVIEW, Msg.tr("cat.review")).desc(Msg.tr("rv.queue-desc"))
                    .line(Msg.tr("panel.cases", open.size())).build());
            menu.list(open, c -> Btn.head(c.player, c.playerName).name(Category.REVIEW, c.playerName + "  #" + c.id)
                            .status(suspicionColor(c.suspicion), Theme.Sym.DOT.sp() + Msg.tr("rv.suspicion", c.suspicion))
                            .line(Msg.tr("rv.top-checks", String.join(", ", c.topChecks(3))))
                            .line(Msg.tr(c.bedrock ? "panel.bedrock" : "panel.java") + " · " + Msg.tr("ui.ago", Durations.format(System.currentTimeMillis() - c.createdAt)))
                            .lines(List.of(Ac.get().watchlist.isWatched(c.player) ? Theme.Sym.DOT.sp() + Msg.tr("rv.watched") : ""))
                            .left(Msg.tr("rv.action.review")).glint(c.suspicion >= 75).build(),
                    c -> (a, cl) -> openCase(a, c.id),
                    c -> c.playerName,
                    List.of(Menu.Filter.sort(Msg.tr("rv.filter.suspicion"), Comparator.comparingInt((ReviewCase c) -> -c.suspicion)),
                            Menu.Filter.sort(Msg.tr("panel.filter.newest"), Comparator.comparingLong((ReviewCase c) -> -c.createdAt)),
                            Menu.Filter.sort(Msg.tr("panel.filter.oldest"), Comparator.comparingLong((ReviewCase c) -> c.createdAt)),
                            new Menu.Filter<>(Msg.tr("rv.filter.watched"), c -> Ac.get().watchlist.isWatched(c.player), null)),
                    Msg.tr("rv.empty"), Msg.tr("rv.empty-hint"));
        });
        m.open(admin);
    }

    public static void openCase(ServerPlayerEntity admin, long id) {
        ReviewCase c = Ac.get().reviews.get(id);
        if (c == null) {
            Msg.send(admin, "review.not-found");
            return;
        }
        Staff.log(admin, "review-open", c.player, c.playerName, "case #" + id);
        Menu m = Menu.std(Category.REVIEW, Msg.trFor(admin, "cat.review"), "#" + c.id + " " + c.playerName).perm(Perm.REVIEW);
        m.renderer(menu -> {
            Ac ac = Ac.get();
            boolean watched = ac.watchlist.isWatched(c.player);
            boolean shadowed = ac.shadow.isShadowed(c.player);
            menu.info(Btn.head(c.player, c.playerName).name(Category.REVIEW, c.playerName)
                    .status(suspicionColor(c.suspicion), Theme.Sym.DOT.sp() + Msg.tr("rv.suspicion", c.suspicion))
                    .line(Msg.tr("rv.opened-at", c.suspicionAtOpen))
                    .line(Msg.tr("rv.status." + c.status.name().toLowerCase(java.util.Locale.ROOT)))
                    .line(Msg.tr(c.bedrock ? "panel.bedrock" : "panel.java"))
                    .line(Msg.tr("rv.warnings", c.warnings.size()))
                    .lines(List.of(watched ? Theme.Sym.DOT.sp() + Msg.tr("rv.watched") : "", shadowed ? Theme.Sym.DOT.sp() + Msg.tr("rv.shadowed") : ""))
                    .build());
            Btn flags = Btn.of(Items.PAPER).name(Category.REVIEW, Msg.tr("rv.flags")).desc(Msg.tr("rv.flags-desc"));
            if (c.flagCounts.isEmpty()) {
                flags.line(Msg.tr("rv.none"));
            }
            for (Map.Entry<String, Integer> e : c.flagCounts.entrySet()) {
                flags.line(Msg.tr("rv.flag-line", e.getKey(), e.getValue(), Math.round(c.points.getOrDefault(e.getKey(), 0.0))));
            }
            menu.icon(10, flags.build());
            Btn past = Btn.of(Items.IRON_BARS).name(Category.REVIEW, Msg.tr("rv.past"));
            List<Punishment> hist = ac.punishments.history(c.player);
            if (hist.isEmpty()) {
                past.line(Msg.tr("rv.none"));
            }
            for (Punishment p : hist.subList(Math.max(0, hist.size() - 8), hist.size())) {
                past.line(p.type.name().toLowerCase(java.util.Locale.ROOT) + ": " + p.reason + (p.revoked ? " (" + Msg.tr("panel.revoked") + ")" : ""));
            }
            menu.icon(11, past.build());
            Btn deaths = Btn.of(Items.SKELETON_SKULL).name(Category.DEATHS, Msg.tr("rv.deaths"));
            var dl = ac.deaths.forPlayer(c.player, 5);
            if (dl.isEmpty()) {
                deaths.line(Msg.tr("rv.none"));
            }
            for (var d : dl) {
                deaths.line(DATE.format(new Date(d.at)) + " " + com.vylorq.anticheat.feature.Deaths.summary(d));
            }
            menu.set(12, deaths.left(Msg.tr("ui.action.open")).build(), Perm.DEATHS, (a, cl) -> InspectMenu.deaths(a, c.player, 0));
            menu.set(13, Btn.of(Items.CLOCK).name(Category.REVIEW, Msg.tr("rv.clips")).desc(Msg.tr("rv.clips-desc"))
                    .line(Msg.tr("panel.count", c.clipIds.size())).left(Msg.tr("ui.action.open")).build(), (a, cl) -> clips(a, c.player));
            menu.set(14, Btn.of(Items.SPYGLASS).name(Category.PLAYERS, Msg.tr("rv.inspect")).desc(Msg.tr("rv.inspect-desc"))
                    .left(Msg.tr("ui.action.open")).build(), Perm.INSPECT, (a, cl) -> InspectMenu.open(a, c.player));
            Btn decisions = Btn.of(Items.WRITABLE_BOOK).name(Category.REVIEW, Msg.tr("rv.decisions"));
            if (c.decisions.isEmpty()) {
                decisions.line(Msg.tr("rv.none"));
            }
            for (var d : c.decisions) {
                decisions.line(DATE.format(new Date(d.at)) + " " + d.by + ": " + d.decision.name().toLowerCase(java.util.Locale.ROOT)
                        + (d.detail == null ? "" : " " + d.detail));
            }
            menu.icon(16, decisions.build());

            ServerPlayerEntity target = ac.server.getPlayerManager().getPlayer(c.player);
            String reason = Msg.tr("rv.reason", c.id);
            // Decisions (row 4), people tools (row 5).
            menu.set(28, Btn.of(Items.NETHERITE_AXE).name(Category.PUNISHMENTS, Msg.tr("rv.ban")).desc(Msg.tr("rv.ban-desc"))
                    .left(Msg.tr("rv.action.ban-forever")).right(Msg.tr("rv.action.ban-time")).build(), Perm.BAN, (a, cl) ->
                    Flows.punish(a, Category.PUNISHMENTS, c.player, c.playerName, "rv.confirm-ban", cl.isRight(), 7 * Durations.DAY, reason, (r, d) -> {
                        decide(a, c, ReviewCase.Decision.BAN, d == Durations.PERMANENT ? "permanent" : Durations.format(d));
                        Punish.ban(a, c.player, c.playerName, d, r, true);
                    }));
            menu.set(29, Btn.of(Items.LEATHER_BOOTS).name(Category.PUNISHMENTS, Msg.tr("rv.kick")).desc(Msg.tr("rv.kick-desc"))
                    .left(Msg.tr("rv.action.kick")).build(), Perm.KICK, (a, cl) -> Confirm.open(a, Category.PUNISHMENTS,
                    Msg.tr("rv.confirm-kick", c.playerName), Msg.tr("rv.confirm-kick-detail"), Btn.head(c.player, c.playerName).name(Category.REVIEW, c.playerName).build(), () -> {
                        decide(a, c, ReviewCase.Decision.KICK, null);
                        Punish.kick(a, c.player, c.playerName, Msg.tr("rv.suspicious"));
                        openCase(a, c.id);
                    }));
            menu.set(30, Btn.of(Items.BELL).name(Category.PUNISHMENTS, Msg.tr("rv.warn")).desc(Msg.tr("rv.warn-desc"))
                    .left(Msg.tr("rv.action.warn")).build(), Perm.WARN, (a, cl) -> {
                decide(a, c, ReviewCase.Decision.WARN, null);
                Punish.warn(a, c.player, c.playerName, Msg.tr("rv.suspicious"));
                Msg.success(a, "rv.done-warn", c.playerName);
                menu.refresh();
            });
            menu.set(31, Btn.of(Items.BOOK).name(Category.PUNISHMENTS, Msg.tr("rv.mute")).desc(Msg.tr("rv.mute-desc"))
                    .left(Msg.tr("rv.action.mute-hour")).right(Msg.tr("rv.action.choose-time")).build(), Perm.MUTE, (a, cl) -> {
                if (cl.isRight()) {
                    Flows.punish(a, Category.PUNISHMENTS, c.player, c.playerName, "rv.confirm-mute", true, Durations.HOUR, reason, (r, d) -> {
                        decide(a, c, ReviewCase.Decision.MUTE, Durations.format(d));
                        Punish.mute(a, c.player, c.playerName, d, r);
                    });
                } else {
                    decide(a, c, ReviewCase.Decision.MUTE, "1h");
                    Punish.mute(a, c.player, c.playerName, Durations.HOUR, reason);
                    Msg.success(a, "rv.done-mute", c.playerName);
                    menu.refresh();
                }
            });
            menu.set(32, Btn.of(Items.IRON_BARS).name(Category.JAIL, Msg.tr("rv.jail")).desc(Msg.tr("rv.jail-desc"))
                    .left(Msg.tr("rv.action.choose-time")).build(), Perm.JAIL, (a, cl) -> {
                if (ac.server.getPlayerManager().getPlayer(c.player) == null) {
                    Msg.send(a, "general.not-online", c.playerName);
                    return;
                }
                if (!Punish.allowedOn(a, c.player)) {
                    return;
                }
                if (ac.jail.cells().isEmpty()) {
                    Msg.send(a, "jail.no-cells");
                    return;
                }
                Flows.punish(a, Category.JAIL, c.player, c.playerName, "rv.confirm-jail", true, Durations.HOUR, reason, (r, d) -> {
                    ServerPlayerEntity t = ac.server.getPlayerManager().getPlayer(c.player);
                    if (t == null) {
                        Msg.send(a, "general.not-online", c.playerName);
                        return;
                    }
                    decide(a, c, ReviewCase.Decision.JAIL, Durations.format(d));
                    Jail.jail(t, r, d, Staff.name(a));
                });
            });
            menu.set(33, Btn.of(Items.OBSERVER).name(Category.WATCHLIST, Msg.tr("rv.watch")).desc(Msg.tr("rv.watch-desc")).onOff(watched)
                    .left(Msg.tr(watched ? "rv.action.keep-watching" : "rv.action.watch")).build(), Perm.WATCH, (a, cl) -> {
                if (!ac.watchlist.isWatched(c.player)) {
                    ac.watchlist.add(c.player, c.playerName, reason, a.getGameProfile().name(), Durations.PERMANENT, false);
                    Ac.markDirty("watchlist");
                    Staff.log(a, "watch-add", c.player, c.playerName, "review #" + c.id);
                }
                decide(a, c, ReviewCase.Decision.WATCH, null);
                Msg.success(a, "rv.done-watch", c.playerName);
                menu.refresh();
            });
            menu.set(34, Btn.of(Items.BLACK_DYE).name(Category.REVIEW, Msg.tr("rv.shadow")).desc(Msg.tr("rv.shadow-desc")).onOff(shadowed)
                    .left(Msg.tr(shadowed ? "ui.action.turn-off" : "ui.action.turn-on")).build(), Perm.SHADOW, (a, cl) -> {
                boolean on = ac.shadow.toggle(c.player);
                Ac.markDirty("shadow");
                Staff.log(a, on ? "shadow-on" : "shadow-off", c.player, c.playerName, "review #" + c.id);
                decide(a, c, ReviewCase.Decision.SHADOW, on ? "on" : "off");
                menu.refresh();
            });
            if (target != null) {
                menu.set(38, Btn.of(Items.ENDER_EYE).name(Category.PLAYERS, Msg.tr("rv.spectate")).desc(Msg.tr("rv.spectate-desc"))
                        .left(Msg.tr("rv.action.spectate")).build(), Perm.SPECTATE, (a, cl) -> {
                    a.closeHandledScreen();
                    StaffTools.spectate(a, target);
                });
                menu.set(39, Btn.of(Items.ENDER_PEARL).name(Category.PLAYERS, Msg.tr("rv.teleport")).desc(Msg.tr("rv.teleport-desc"))
                        .left(Msg.tr("rv.action.tp-default")).right(Msg.tr("rv.action.tp-other")).build(), Perm.TELEPORT, (a, cl) -> {
                    a.closeHandledScreen();
                    boolean inv = ac.staff.teleportInvisible(a.getUuid(), Ac.config().staff.teleportInvisibleByDefault) != cl.isRight();
                    StaffTools.teleportTo(a, target.getEntityWorld(), Mc.vec(target.getEntityPos()), inv, c.playerName);
                });
            }
            menu.set(42, Btn.of(Items.LIME_CONCRETE).name(Category.CLAIMS, Msg.tr("rv.dismiss")).desc(Msg.tr("rv.dismiss-desc"))
                    .left(Msg.tr("rv.action.dismiss")).build(), Perm.REVIEW, (a, cl) -> Confirm.open(a, Category.REVIEW,
                    Msg.tr("rv.confirm-dismiss", c.playerName), Msg.tr("rv.dismiss-desc"), null, () -> {
                        Ac.get().engine.decide(c.id, ReviewCase.Decision.DISMISS, a.getGameProfile().name(), null);
                        Ac.markDirty("reviews");
                        Ac.markDirty("stats");
                        Staff.log(a, "review-dismiss", c.player, c.playerName, "case #" + c.id);
                        for (String clip : c.clipIds) {
                            try {
                                ac.clips.setPinned(clip, false);
                            } catch (Exception ignored) {
                                // best effort
                            }
                        }
                        Msg.success(a, "rv.done-dismiss", c.playerName);
                        queue(a, 0);
                    }));
        });
        m.open(admin);
    }

    private static void decide(ServerPlayerEntity admin, ReviewCase c, ReviewCase.Decision d, String detail) {
        Ac.get().engine.decide(c.id, d, admin.getGameProfile().name(), detail);
        Ac.markDirty("reviews");
        Ac.markDirty("stats");
        Staff.log(admin, "review-" + d.name().toLowerCase(), c.player, c.playerName, "case #" + c.id + (detail == null ? "" : " " + detail));
    }

    // ---- Evidence ----

    public static void clips(ServerPlayerEntity admin, UUID player) {
        List<EvidenceClip> list = Ac.get().clips.forPlayer(player);
        String name = list.isEmpty() ? com.vylorq.anticheat.command.Args.nameOf(player, player.toString().substring(0, 8)) : list.get(0).playerName;
        Menu m = Menu.std(Category.REVIEW, Msg.trFor(admin, "rv.clips"), name).perm(Perm.REVIEW);
        m.renderer(menu -> {
            menu.info(Btn.head(player, name).name(Category.REVIEW, name).desc(Msg.tr("rv.clips-desc")).line(Msg.tr("panel.count", list.size())).build());
            menu.list(list, c -> Btn.of(c.pinned ? Items.FILLED_MAP : Items.MAP).name(Category.REVIEW, DATE.format(new Date(c.createdAt)))
                            .line(Msg.tr("rv.trigger", c.trigger)).line(Msg.tr("rv.events", c.events.size()))
                            .lines(List.of(c.pinned ? Theme.Sym.DOT.sp() + Msg.tr("rv.kept") : ""))
                            .left(Msg.tr("rv.action.timeline")).right(Msg.tr("rv.action.replay")).build(),
                    c -> (a, cl) -> {
                        if (cl.isRight()) {
                            replay(a, c);
                        } else {
                            timeline(a, c, 0);
                        }
                    }, c -> c.trigger,
                    List.of(), Msg.tr("rv.no-clips"), Msg.tr("rv.no-clips-hint"));
        });
        m.open(admin);
    }

    /** Watch the clip in-game; offers to go to where it happened when that's somewhere else. */
    public static void replay(ServerPlayerEntity admin, EvidenceClip clip) {
        net.minecraft.util.math.Vec3d start = com.vylorq.anticheat.feature.Replay.startOf(clip);
        if (start == null) {
            Msg.send(admin, "replay.empty");
            return;
        }
        net.minecraft.server.world.ServerWorld w = com.vylorq.anticheat.feature.Replay.worldOf(clip);
        net.minecraft.server.world.ServerWorld target = w != null ? w : admin.getEntityWorld();
        boolean far = target != admin.getEntityWorld() || admin.getEntityPos().distanceTo(start) > 48;
        if (!far) {
            admin.closeHandledScreen();
            com.vylorq.anticheat.feature.Replay.start(admin, clip);
            return;
        }
        Confirm.open(admin, Category.REVIEW, Msg.trFor(admin, "rv.replay-go"),
                Msg.trFor(admin, "rv.replay-go-detail", (int) start.x, (int) start.y, (int) start.z, com.vylorq.anticheat.util.Mc.worldId(target)),
                new net.minecraft.item.ItemStack(Items.ENDER_PEARL), () -> {
                    // A few blocks back and up, looking at where it starts.
                    com.vylorq.anticheat.util.Mc.teleport(admin, target, start.x + 3, start.y + 2, start.z + 3, 135f, 25f);
                    com.vylorq.anticheat.feature.Replay.start(admin, clip);
                });
    }

    public static void timeline(ServerPlayerEntity admin, EvidenceClip clip, int page) {
        Staff.log(admin, "evidence-view", clip.player, clip.playerName, clip.id);
        List<EvidenceEvent> events = clip.condensedTimeline();
        Menu m = Menu.std(Category.REVIEW, Msg.trFor(admin, "rv.clips"), clip.playerName).perm(Perm.REVIEW);
        m.renderer(menu -> {
            menu.info(Btn.of(Items.FILLED_MAP).name(Category.REVIEW, clip.id).line(Msg.tr("rv.trigger", clip.trigger))
                    .line(Msg.tr("rv.events", events.size())).build());
            menu.set(7, Btn.of(Items.ENDER_EYE).name(Category.REVIEW, Msg.tr("rv.replay")).desc(Msg.tr("rv.replay-desc"))
                    .left(Msg.tr("rv.action.replay")).build(), (a, c) -> replay(a, clip));
            menu.set(8, Btn.of(Items.WRITABLE_BOOK).name(Category.REVIEW, Msg.tr("rv.export")).desc(Msg.tr("rv.export-desc"))
                    .left(Msg.tr("rv.action.export")).build(), (a, c) -> {
                try {
                    var f = Ac.get().clips.save(clip);
                    Msg.send(a, "evidence.exported", f.toString());
                    Discord.send("evidence", "Evidence " + clip.id + " for " + clip.playerName, clip.summary(15), 0x3498DB);
                    Staff.log(a, "evidence-export", clip.player, clip.playerName, clip.id);
                } catch (Exception ex) {
                    Msg.send(a, "general.error");
                }
            });
            menu.list(events, e -> Btn.of(iconFor(e.type)).color(Theme.WHITE).name(e.describe()).build(), null, EvidenceEvent::describe,
                    List.of(), Msg.tr("rv.no-events"), "");
        });
        m.open(admin);
    }

    private static net.minecraft.item.Item iconFor(EvidenceEvent.Type t) {
        return switch (t) {
            case MOVE -> Items.LEATHER_BOOTS;
            case HIT -> Items.IRON_SWORD;
            case CLICK -> Items.STONE_BUTTON;
            case BREAK -> Items.IRON_PICKAXE;
            case PLACE -> Items.GRASS_BLOCK;
            case INVENTORY -> Items.CHEST;
            case FLAG -> Items.RED_BANNER;
            case CHAT -> Items.OAK_SIGN;
            case COMMAND -> Items.COMMAND_BLOCK;
            default -> Items.PAPER;
        };
    }
}
