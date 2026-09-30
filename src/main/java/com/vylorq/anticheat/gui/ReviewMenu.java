package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.evidence.EvidenceClip;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.feature.DetectionListener;
import com.vylorq.anticheat.feature.Discord;
import com.vylorq.anticheat.feature.Jail;
import com.vylorq.anticheat.feature.Punish;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The review queue (section 6.2) and evidence timelines (section 11). */
public final class ReviewMenu {
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private ReviewMenu() {
    }

    public static void queue(ServerPlayerEntity admin, int page) {
        Menu m = new Menu("§8Review queue", 6).perm(Perm.REVIEW);
        m.renderer(menu -> {
            List<ReviewCase> open = Ac.get().reviews.open();
            menu.page(open, page, c -> Icons.head(c.player, c.playerName, DetectionListener.color(c.suspicion) + c.playerName + " §7#" + c.id,
                    "Suspicion: " + c.suspicion, "Top checks: " + String.join(", ", c.topChecks(3)),
                    (c.bedrock ? "Bedrock" : "Java") + " • opened " + DATE.format(new Date(c.createdAt)),
                    Ac.get().watchlist.isWatched(c.player) ? "§dWatched" : "", "§eClick to review"),
                    c -> (a, cl) -> openCase(a, c.id), pg -> queue(admin, pg));
            if (open.isEmpty()) {
                menu.icon(22, Icons.of(Items.LIME_DYE, "§aNothing to review"));
            }
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
        Menu m = new Menu("§8Case #" + c.id + ": " + c.playerName, 6).perm(Perm.REVIEW);
        m.renderer(menu -> {
            Ac ac = Ac.get();
            List<String> checks = new ArrayList<>();
            for (Map.Entry<String, Integer> e : c.flagCounts.entrySet()) {
                checks.add("§7" + e.getKey() + ": §f" + e.getValue() + " flags, " + c.points.getOrDefault(e.getKey(), 0.0) + " pts");
            }
            menu.icon(4, Icons.head(c.player, c.playerName, DetectionListener.color(c.suspicion) + c.playerName,
                    "Status: " + c.status, "Suspicion: " + c.suspicion + " (opened at " + c.suspicionAtOpen + ")",
                    c.bedrock ? "Bedrock" : "Java", "Warnings sent: " + c.warnings.size(),
                    ac.watchlist.isWatched(c.player) ? "§dOn watchlist" : "§7Not watched",
                    ac.shadow.isShadowed(c.player) ? "§8In shadow mode" : ""));
            menu.icon(10, Icons.of(Items.PAPER, "§eFlags per check", checks.isEmpty() ? List.of("§7None") : checks));
            List<String> past = new ArrayList<>();
            for (Punishment p : ac.punishments.history(c.player)) {
                past.add("§7" + p.type + ": " + p.reason + (p.revoked ? " (revoked)" : ""));
            }
            menu.icon(11, Icons.of(Items.IRON_BARS, "§ePast punishments", past.isEmpty() ? List.of("§7None") : past.subList(Math.max(0, past.size() - 10), past.size())));
            var deaths = ac.deaths.forPlayer(c.player, 5);
            List<String> dl = new ArrayList<>();
            for (var d : deaths) {
                dl.add("§7" + DATE.format(new Date(d.at)) + " " + com.vylorq.anticheat.feature.Deaths.summary(d));
            }
            menu.set(12, Icons.of(Items.SKELETON_SKULL, "§eDeath logs", dl.isEmpty() ? List.of("§7None") : dl), Perm.DEATHS,
                    (a, cl) -> InspectMenu.deaths(a, c.player, 0));
            menu.set(13, Icons.of(Items.CLOCK, "§bEvidence clips (" + c.clipIds.size() + ")", "Timeline of what happened"),
                    (a, cl) -> clips(a, c.player));
            menu.set(14, Icons.of(Items.SPYGLASS, "§bInspect player"), Perm.INSPECT, (a, cl) -> InspectMenu.open(a, c.player));
            List<String> hist = new ArrayList<>();
            for (var d : c.decisions) {
                hist.add("§7" + DATE.format(new Date(d.at)) + " " + d.by + ": " + d.decision + (d.detail == null ? "" : " " + d.detail));
            }
            menu.icon(16, Icons.of(Items.WRITABLE_BOOK, "§eDecisions", hist.isEmpty() ? List.of("§7None yet") : hist));

            ServerPlayerEntity target = ac.server.getPlayerManager().getPlayer(c.player);
            // Decision buttons.
            menu.set(28, Icons.of(Items.NETHERITE_AXE, "§4Ban", "Left: permanent", "Right: choose time"), Perm.BAN, (a, cl) -> {
                if (cl.isRight()) {
                    Prompts.ask(a, "Ban length (e.g. 7d, 30d, permanent):", len -> {
                        long d = Durations.parse(len).orElse(-2);
                        if (d == -2) {
                            Msg.send(a, "general.bad-duration");
                            return;
                        }
                        decide(a, c, ReviewCase.Decision.BAN, Durations.format(d));
                        Punish.ban(a, c.player, c.playerName, d, "Cheating (review #" + c.id + ")", true);
                    });
                } else {
                    decide(a, c, ReviewCase.Decision.BAN, "permanent");
                    Punish.ban(a, c.player, c.playerName, Durations.PERMANENT, "Cheating (review #" + c.id + ")", true);
                    a.closeHandledScreen();
                }
            });
            menu.set(29, Icons.of(Items.LEATHER_BOOTS, "§cKick"), Perm.KICK, (a, cl) -> {
                decide(a, c, ReviewCase.Decision.KICK, null);
                Punish.kick(a, c.player, c.playerName, "Suspicious activity");
                menu.refresh();
            });
            menu.set(30, Icons.of(Items.BELL, "§eWarn"), Perm.WARN, (a, cl) -> {
                decide(a, c, ReviewCase.Decision.WARN, null);
                Punish.warn(a, c.player, c.playerName, "Suspicious activity");
                menu.refresh();
            });
            menu.set(31, Icons.of(Items.BOOK, "§eMute", "Left: 1h, right: choose"), Perm.MUTE, (a, cl) -> {
                if (cl.isRight()) {
                    Prompts.ask(a, "Mute length (e.g. 30m, 1d):", len -> {
                        long d = Durations.parse(len).orElse(Durations.HOUR);
                        decide(a, c, ReviewCase.Decision.MUTE, Durations.format(d));
                        Punish.mute(a, c.player, c.playerName, d, "Review #" + c.id);
                    });
                } else {
                    decide(a, c, ReviewCase.Decision.MUTE, "1h");
                    Punish.mute(a, c.player, c.playerName, Durations.HOUR, "Review #" + c.id);
                    menu.refresh();
                }
            });
            menu.set(32, Icons.of(Items.IRON_BARS, "§eJail", "Left: 1h, right: choose"), Perm.JAIL, (a, cl) -> {
                ServerPlayerEntity t = ac.server.getPlayerManager().getPlayer(c.player);
                if (t == null) {
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
                if (cl.isRight()) {
                    Prompts.ask(a, "Jail time (e.g. 30m, 2h):", len -> {
                        long d = Durations.parse(len).orElse(Durations.HOUR);
                        decide(a, c, ReviewCase.Decision.JAIL, Durations.format(d));
                        Jail.jail(t, "Review #" + c.id, d, Staff.name(a));
                    });
                } else {
                    decide(a, c, ReviewCase.Decision.JAIL, "1h");
                    Jail.jail(t, "Review #" + c.id, Durations.HOUR, Staff.name(a));
                    menu.refresh();
                }
            });
            menu.set(33, Icons.of(Items.ENDER_EYE, ac.watchlist.isWatched(c.player) ? "§dWatched" : "§dAdd to watchlist"), Perm.WATCH, (a, cl) -> {
                if (!ac.watchlist.isWatched(c.player)) {
                    ac.watchlist.add(c.player, c.playerName, "Review #" + c.id, a.getGameProfile().name(), Durations.PERMANENT, false);
                    Ac.markDirty("watchlist");
                    Staff.log(a, "watch-add", c.player, c.playerName, "review #" + c.id);
                }
                decide(a, c, ReviewCase.Decision.WATCH, null);
                menu.refresh();
            });
            menu.set(34, Icons.of(Items.BLACK_DYE, ac.shadow.isShadowed(c.player) ? "§8Shadow mode: ON" : "§8Shadow mode"), Perm.SHADOW, (a, cl) -> {
                boolean on = ac.shadow.toggle(c.player);
                Ac.markDirty("shadow");
                Staff.log(a, on ? "shadow-on" : "shadow-off", c.player, c.playerName, "review #" + c.id);
                decide(a, c, ReviewCase.Decision.SHADOW, on ? "on" : "off");
                menu.refresh();
            });
            if (target != null) {
                menu.set(38, Icons.of(Items.ENDER_EYE, "§bSpectate"), Perm.SPECTATE, (a, cl) -> {
                    a.closeHandledScreen();
                    StaffTools.spectate(a, target);
                });
                menu.set(39, Icons.of(Items.ENDER_PEARL, "§bTeleport", "Left: your default, right: the other"), Perm.TELEPORT, (a, cl) -> {
                    a.closeHandledScreen();
                    boolean inv = ac.staff.teleportInvisible(a.getUuid(), Ac.config().staff.teleportInvisibleByDefault) != cl.isRight();
                    StaffTools.teleportTo(a, target.getEntityWorld(), Mc.vec(target.getEntityPos()), inv, c.playerName);
                });
            }
            menu.set(42, Icons.of(Items.LIME_CONCRETE, "§aDismiss (false flag)", "Closes the case and resets points.",
                    "Counts toward this check's false-flag stats."), Perm.REVIEW, (a, cl) -> {
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
                queue(a, 0);
            });
            menu.set(45, Menu.back(), (a, cl) -> queue(a, 0));
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
        Menu m = new Menu("§8Evidence: " + (list.isEmpty() ? player.toString().substring(0, 8) : list.get(0).playerName), 6).perm(Perm.REVIEW);
        m.renderer(menu -> menu.page(list, 0, c -> Icons.of(c.pinned ? Items.FILLED_MAP : Items.MAP,
                        "§e" + DATE.format(new Date(c.createdAt)) + " §7(" + c.trigger + ")",
                        c.events.size() + " events", c.pinned ? "§aKept (open case or ban)" : "", "§eClick to view timeline"),
                c -> (a, cl) -> timeline(a, c, 0), pg -> { }));
        m.open(admin);
    }

    public static void timeline(ServerPlayerEntity admin, EvidenceClip clip, int page) {
        Staff.log(admin, "evidence-view", clip.player, clip.playerName, clip.id);
        List<EvidenceEvent> events = clip.condensedTimeline();
        Menu m = new Menu("§8Clip " + clip.id + " (" + clip.playerName + ")", 6).perm(Perm.REVIEW);
        m.renderer(menu -> {
            menu.page(events, page, e -> Icons.of(iconFor(e.type), "§f" + e.describe()), e -> null, pg -> timeline(admin, clip, pg));
            menu.set(45, Icons.of(Items.WRITABLE_BOOK, "§eExport", "Saves the clip file and sends", "a summary to Discord (if set up)"),
                    (a, c) -> {
                        try {
                            var f = Ac.get().clips.save(clip);
                            Msg.send(a, "evidence.exported", f.toString());
                            Discord.send("evidence", "Evidence " + clip.id + " for " + clip.playerName, clip.summary(15), 0x3498DB);
                            Staff.log(a, "evidence-export", clip.player, clip.playerName, clip.id);
                        } catch (Exception ex) {
                            Msg.send(a, "general.error");
                        }
                    });
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
