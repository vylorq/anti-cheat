package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.core.detect.Watchlist;
import com.vylorq.anticheat.core.jail.JailManager;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.staff.Reports;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.core.waiting.WaitingRoom;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Sounds;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import com.vylorq.anticheat.util.Tps;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Vigil Panel (34.4): one place to reach everything. People on row 2, places on row 3, tools on row 4. Buttons
 * someone can't use become blank glass so the layout never moves.
 */
public final class VigilPanel {
    private VigilPanel() {
    }

    private record Entry(int slot, Category cat, Perm perm) {
    }

    private static final List<Entry> BUTTONS = List.of(
            new Entry(10, Category.REVIEW, Perm.REVIEW),
            new Entry(11, Category.PLAYERS, Perm.INSPECT),
            new Entry(12, Category.WATCHLIST, Perm.WATCH),
            new Entry(14, Category.PUNISHMENTS, Perm.BAN),
            new Entry(15, Category.REPORTS, Perm.REVIEW),
            new Entry(16, Category.LOGS, Perm.ALERTS),
            new Entry(19, Category.CLAIMS, Perm.CLAIM),
            new Entry(20, Category.BARRIERS, Perm.BARRIER),
            new Entry(21, Category.LOBBY, Perm.LOBBY_ADMIN),
            new Entry(23, Category.JAIL, Perm.JAIL),
            new Entry(24, Category.ARENAS, Perm.ARENA_ADMIN),
            new Entry(25, Category.TRADERS, Perm.TRADER_ADMIN),
            new Entry(28, Category.STAFF, Perm.VANISH),
            new Entry(29, Category.DEATHS, Perm.DEATHS),
            new Entry(30, Category.LAG, Perm.LAG),
            new Entry(32, Category.WAITING, Perm.WAITING_ROOM),
            new Entry(33, Category.WATCHER, Perm.WATCHER),
            new Entry(34, Category.SETTINGS, Perm.SETTINGS));

    public static boolean canOpen(ServerPlayerEntity p) {
        for (Entry e : BUTTONS) {
            if (Perms.has(p, e.perm)) {
                return true;
            }
        }
        return false;
    }

    public static void open(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "panel.title")).root().live();
        m.renderer(menu -> {
            Ac ac = Ac.get();
            MinecraftServer server = ac.server;
            int cases = ac.reviews.openCount();
            int reports = ac.reports.open().size();
            int requests = ac.waitingRoom.pending().size();
            double tps = Tps.tps();
            menu.info(Btn.of(Items.ENDER_EYE).color(Theme.GOLD_LIGHT).name("VIGIL")
                    .desc(Msg.tr("panel.summary"))
                    .line(Msg.tr("panel.online", server.getCurrentPlayerCount(), server.getMaxPlayerCount()))
                    .status(cases > 0 ? Theme.RED : Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr("panel.cases", cases))
                    .status(requests > 0 ? Theme.GOLD : Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr("panel.requests", requests))
                    .status(reports > 0 ? Theme.GOLD : Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr("panel.reports", reports))
                    .status(tps >= 19 ? Theme.GREEN : tps >= 15 ? Theme.GOLD : Theme.RED,
                            Theme.Sym.DOT.sp() + Msg.tr("panel.tps", String.format(java.util.Locale.ROOT, "%.1f", tps)))
                    .glint(cases + reports + requests > 0).build());
            for (Entry e : BUTTONS) {
                if (!Perms.has(p, e.perm)) {
                    continue;
                }
                int waiting = switch (e.cat) {
                    case REVIEW -> cases;
                    case REPORTS -> reports;
                    case WAITING -> requests;
                    case JAIL -> ac.jail.list().size();
                    default -> 0;
                };
                String id = e.cat.name().toLowerCase(java.util.Locale.ROOT);
                Btn b = Btn.of(e.cat.icon).name(e.cat, Msg.tr(e.cat.key())).desc(Msg.tr("panel.desc." + id));
                if (e.cat == Category.JAIL) {
                    b.line(Msg.tr("panel.jailed", waiting));
                } else {
                    b.count(waiting);
                }
                menu.set(e.slot, b.left(Msg.tr("ui.action.open")).build(), e.perm, (pl, c) -> openCategory(pl, e.cat));
            }
            menu.icon(Menu.CLOSE, Btn.pane(Category.VIGIL.glass));
            menu.set(49, Btn.of(Items.BARRIER).color(Theme.RED).name(Msg.tr("ui.close")).build(), null, (pl, c) -> pl.closeHandledScreen());
            menu.fill(Btn.pane(Category.VIGIL.glass));
        });
        m.open(p);
    }

    public static void openCategory(ServerPlayerEntity p, Category cat) {
        switch (cat) {
            case REVIEW -> ReviewMenu.queue(p, 0);
            case PLAYERS -> players(p, false);
            case WATCHLIST -> watchlist(p);
            case PUNISHMENTS -> punishments(p);
            case REPORTS -> reports(p);
            case LOGS -> logs(p);
            case CLAIMS -> ClaimMenu.list(p, 0);
            case BARRIERS -> barriers(p);
            case LOBBY -> lobby(p);
            case JAIL -> jail(p);
            case ARENAS -> ArenaMenu.list(p);
            case TRADERS -> traders(p);
            case STAFF -> staffTools(p);
            case DEATHS -> players(p, true);
            case LAG -> lag(p);
            case WAITING -> waiting(p);
            case WATCHER -> SettingsMenu.page(p, SettingsMenu.Page.WATCHER);
            case SETTINGS -> SettingsMenu.open(p);
            default -> open(p);
        }
    }

    private static String ago(long at) {
        return Msg.tr("ui.ago", Durations.format(Math.max(0, System.currentTimeMillis() - at)));
    }

    private static String left(long expiresAt) {
        if (expiresAt == 0 || expiresAt == Durations.PERMANENT) {
            return Msg.tr("ui.permanent");
        }
        return Theme.Sym.CLOCK.sp() + Msg.tr("ui.left", Durations.format(Math.max(0, expiresAt - System.currentTimeMillis())));
    }

    // ---- People ----

    private record Known(UUID id, String name, boolean online) {
    }

    /** Players: everyone online, or everyone the server has seen. {@code deaths} opens death logs instead of inspect. */
    public static void players(ServerPlayerEntity p, boolean deaths) {
        Category cat = deaths ? Category.DEATHS : Category.PLAYERS;
        Menu m = Menu.std(cat, Msg.trFor(p, cat.key()));
        m.renderer(menu -> {
            Ac ac = Ac.get();
            List<Known> all = new ArrayList<>();
            java.util.Set<UUID> online = new java.util.HashSet<>();
            for (ServerPlayerEntity o : ac.server.getPlayerManager().getPlayerList()) {
                all.add(new Known(o.getUuid(), o.getGameProfile().name(), true));
                online.add(o.getUuid());
            }
            for (Map.Entry<UUID, String> e : ac.joins.data().names.entrySet()) {
                if (!online.contains(e.getKey())) {
                    all.add(new Known(e.getKey(), e.getValue(), false));
                }
            }
            menu.info(Btn.of(cat.icon).name(cat, Msg.tr(cat.key())).desc(Msg.tr(deaths ? "panel.desc.deaths" : "panel.desc.players"))
                    .line(Msg.tr("panel.players-count", online.size(), all.size())).build());
            Comparator<Known> byName = Comparator.comparing(k -> k.name.toLowerCase(java.util.Locale.ROOT));
            menu.list(all, k -> Btn.head(k.id, k.name).name(cat, k.name)
                            .status(k.online ? Theme.GREEN : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr(k.online ? "ui.online" : "ui.offline"))
                            .left(Msg.tr(deaths ? "panel.action.deaths" : "panel.action.inspect")).build(),
                    k -> (pl, c) -> {
                        if (deaths) {
                            InspectMenu.deaths(pl, k.id, 0);
                        } else {
                            InspectMenu.open(pl, k.id);
                        }
                    },
                    k -> k.name,
                    List.of(new Menu.Filter<>(Msg.tr("panel.filter.online"), k -> k.online, byName),
                            new Menu.Filter<>(Msg.tr("panel.filter.everyone"), k -> true, byName)),
                    Msg.tr("panel.empty.players"), Msg.tr("panel.empty.players-hint"));
        });
        m.open(p);
    }

    public static void watchlist(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.WATCHLIST, Msg.trFor(p, Category.WATCHLIST.key())).perm(Perm.WATCH);
        m.renderer(menu -> {
            List<Watchlist.Entry> list = Ac.get().watchlist.list();
            menu.info(Btn.of(Items.OBSERVER).name(Category.WATCHLIST, Msg.tr(Category.WATCHLIST.key()))
                    .desc(Msg.tr("panel.desc.watchlist")).line(Msg.tr("panel.watched", list.size())).build());
            menu.list(list, e -> Btn.head(e.uuid, e.name).name(Category.WATCHLIST, e.name)
                            .desc(e.reason)
                            .line(Msg.tr("panel.added-by", e.addedBy, ago(e.addedAt)))
                            .line(left(e.expiresAt))
                            .status(e.auto ? Theme.GOLD : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr(e.auto ? "panel.auto" : "panel.manual"))
                            .left(Msg.tr("panel.action.inspect")).shift(Msg.tr("panel.action.unwatch")).build(),
                    e -> (pl, c) -> {
                        if (c.isShift()) {
                            Confirm.open(pl, Category.WATCHLIST, Msg.tr("panel.confirm.unwatch", e.name), Msg.tr("panel.confirm.unwatch-detail"),
                                    Btn.head(e.uuid, e.name).name(Category.WATCHLIST, e.name).build(), () -> Mc.run(pl, "watch remove " + Msg.q(e.name)));
                        } else {
                            InspectMenu.open(pl, e.uuid);
                        }
                    },
                    e -> e.name + " " + e.reason,
                    List.of(Menu.Filter.sort(Msg.tr("panel.filter.newest"), Comparator.comparingLong((Watchlist.Entry e) -> -e.addedAt)),
                            Menu.Filter.sort(Msg.tr("panel.filter.name"), Comparator.comparing((Watchlist.Entry e) -> e.name.toLowerCase(java.util.Locale.ROOT)))),
                    Msg.tr("panel.empty.watchlist"), Msg.tr("panel.empty.watchlist-hint"));
        });
        m.open(p);
    }

    public static void punishments(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.PUNISHMENTS, Msg.trFor(p, Category.PUNISHMENTS.key())).perm(Perm.BAN);
        m.renderer(menu -> {
            long now = System.currentTimeMillis();
            List<Punishment> all = new ArrayList<>(Ac.get().punishments.data().all.values());
            java.util.function.Predicate<Punishment> active = x -> !x.revoked && (x.expiresAt == 0 || x.expiresAt == Durations.PERMANENT || x.expiresAt > now)
                    && (x.type == Punishment.Type.BAN || x.type == Punishment.Type.MUTE);
            long bans = all.stream().filter(active).filter(x -> x.type == Punishment.Type.BAN).count();
            long mutes = all.stream().filter(active).filter(x -> x.type == Punishment.Type.MUTE).count();
            menu.info(Btn.of(Items.IRON_SWORD).name(Category.PUNISHMENTS, Msg.tr(Category.PUNISHMENTS.key()))
                    .desc(Msg.tr("panel.desc.punishments")).line(Msg.tr("panel.bans-mutes", bans, mutes)).build());
            Comparator<Punishment> newest = Comparator.comparingLong((Punishment x) -> -x.at);
            menu.list(all, x -> {
                        boolean on = active.test(x);
                        Btn b = Btn.head(x.player, x.playerName).name(Category.PUNISHMENTS, x.playerName + " · " + x.type.name().toLowerCase(java.util.Locale.ROOT))
                                .desc(x.reason)
                                .line(Msg.tr("panel.by", x.by, ago(x.at)))
                                .status(on ? Theme.RED : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr(on ? "panel.active" : x.revoked ? "panel.revoked" : "panel.ended"));
                        if (on) {
                            b.line(left(x.expiresAt));
                        }
                        b.left(Msg.tr("panel.action.inspect"));
                        if (on) {
                            b.shift(Msg.tr(x.type == Punishment.Type.BAN ? "panel.action.unban" : "panel.action.unmute"));
                        }
                        return b.build();
                    },
                    x -> (pl, c) -> {
                        if (c.isShift() && active.test(x)) {
                            boolean ban = x.type == Punishment.Type.BAN;
                            Confirm.open(pl, Category.PUNISHMENTS, Msg.tr(ban ? "panel.confirm.unban" : "panel.confirm.unmute", x.playerName),
                                    Msg.tr("panel.confirm.lift-detail"), Btn.head(x.player, x.playerName).name(Category.PUNISHMENTS, x.playerName).build(),
                                    () -> Mc.run(pl, (ban ? "unban " : "unmute ") + Msg.q(x.playerName)));
                        } else {
                            InspectMenu.open(pl, x.player);
                        }
                    },
                    x -> x.playerName + " " + x.reason + " " + x.by,
                    List.of(new Menu.Filter<>(Msg.tr("panel.filter.active"), active, newest),
                            new Menu.Filter<>(Msg.tr("panel.filter.bans"), x -> active.test(x) && x.type == Punishment.Type.BAN, newest),
                            new Menu.Filter<>(Msg.tr("panel.filter.mutes"), x -> active.test(x) && x.type == Punishment.Type.MUTE, newest),
                            new Menu.Filter<>(Msg.tr("panel.filter.history"), x -> true, newest)),
                    Msg.tr("panel.empty.punishments"), Msg.tr("panel.empty.punishments-hint"));
        });
        m.open(p);
    }

    public static void reports(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.REPORTS, Msg.trFor(p, Category.REPORTS.key())).perm(Perm.REVIEW);
        m.renderer(menu -> {
            List<Reports.Report> list = Ac.get().reports.open();
            menu.info(Btn.of(Items.PAPER).name(Category.REPORTS, Msg.tr(Category.REPORTS.key()))
                    .desc(Msg.tr("panel.desc.reports")).line(Msg.tr("panel.reports", list.size())).build());
            menu.list(list, r -> Btn.head(r.suspect, r.suspectName).name(Category.REPORTS, "#" + r.id + " " + r.suspectName)
                            .desc(r.reason)
                            .line(Msg.tr("panel.reported-by", r.reporterName, ago(r.at)))
                            .left(Msg.tr("panel.action.tp-suspect")).right(Msg.tr("panel.action.inspect"))
                            .shift(Msg.tr("panel.action.close-report")).glint(true).build(),
                    r -> (pl, c) -> {
                        if (c.isShift()) {
                            Mc.run(pl, "report close " + r.id);
                            menu.refresh();
                        } else if (c.isRight()) {
                            InspectMenu.open(pl, r.suspect);
                        } else if (r.suspectPos != null) {
                            pl.closeHandledScreen();
                            Vec3 v = r.suspectPos;
                            Mc.run(pl, "ac tp " + r.suspectWorld + " " + (int) v.x() + " " + (int) v.y() + " " + (int) v.z());
                        } else {
                            Msg.error(pl, "panel.no-position");
                        }
                    },
                    r -> r.suspectName + " " + r.reporterName + " " + r.reason,
                    List.of(Menu.Filter.sort(Msg.tr("panel.filter.newest"), Comparator.comparingLong((Reports.Report r) -> -r.at)),
                            Menu.Filter.sort(Msg.tr("panel.filter.oldest"), Comparator.comparingLong((Reports.Report r) -> r.at))),
                    Msg.tr("panel.empty.reports"), Msg.tr("panel.empty.reports-hint"));
        });
        m.open(p);
    }

    private record LogLine(long time, String actor, String action, String target, String detail) {
    }

    public static void logs(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.LOGS, Msg.trFor(p, Category.LOGS.key())).perm(Perm.ALERTS);
        m.renderer(menu -> {
            boolean owner = Perms.has(p, Perm.STAFF_LOG);
            List<LogLine> lines = new ArrayList<>();
            try {
                Ac.get().logs.flush();
                for (var r : Ac.get().db.staffLog(300, owner ? null : p.getUuid().toString())) {
                    lines.add(new LogLine(r.time(), r.a(), r.b(), r.c(), r.d()));
                }
            } catch (Exception e) {
                Ac.LOG.warn("Could not read the staff log", e);
            }
            menu.info(Btn.of(Items.BOOK).name(Category.LOGS, Msg.tr(Category.LOGS.key()))
                    .desc(Msg.tr(owner ? "panel.desc.logs-owner" : "panel.desc.logs-admin")).build());
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
            menu.list(lines, l -> Btn.of(Items.PAPER).name(Category.LOGS, l.action)
                            .line(Msg.tr("panel.log-who", l.actor, f.format(new Date(l.time))))
                            .lines(List.of(l.target == null || l.target.isEmpty() ? "" : "→ " + l.target))
                            .desc(l.detail == null ? "" : l.detail).build(),
                    null,
                    l -> l.actor + " " + l.action + " " + l.target + " " + l.detail,
                    List.of(), Msg.tr("panel.empty.logs"), Msg.tr("panel.empty.logs-hint"));
        });
        m.open(p);
    }

    // ---- Places ----

    public static void barriers(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.BARRIERS, Msg.trFor(p, Category.BARRIERS.key())).perm(Perm.BARRIER);
        m.renderer(menu -> {
            List<Barrier> list = Ac.get().barriers.list();
            menu.info(Btn.of(Items.GLASS).name(Category.BARRIERS, Msg.tr(Category.BARRIERS.key()))
                    .desc(Msg.tr("panel.desc.barriers")).line(Msg.tr("panel.count", list.size()))
                    .hint(Msg.tr("panel.hint.barrier-create")).build());
            menu.list(list, b -> {
                        Vec3 c = b.center();
                        return Btn.of(Items.GLASS).name(Category.BARRIERS, b.name)
                                .line(b.world.replace("minecraft:", "") + " " + (int) c.x() + ", " + (int) c.z())
                                .line(left(b.expiresAt))
                                .status(b.adminsPass ? Theme.GREEN : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr(b.adminsPass ? "panel.admins-pass" : "panel.admins-blocked"))
                                .left(Msg.tr("panel.action.teleport")).shift(Msg.tr("panel.action.delete")).build();
                    },
                    b -> (pl, c) -> {
                        if (c.isShift()) {
                            Confirm.open(pl, Category.BARRIERS, Msg.tr("panel.confirm.delete-barrier", b.name), Msg.tr("panel.confirm.delete-barrier-detail"),
                                    Btn.of(Items.GLASS).name(Category.BARRIERS, b.name).build(), () -> Mc.run(pl, "barrier remove " + Msg.q(b.name)));
                        } else {
                            pl.closeHandledScreen();
                            Vec3 v = b.center();
                            Mc.run(pl, "ac tp " + b.world + " " + (int) v.x() + " " + (int) v.y() + " " + (int) v.z());
                        }
                    },
                    b -> b.name + " " + b.world,
                    List.of(), Msg.tr("panel.empty.barriers"), Msg.tr("panel.empty.barriers-hint"));
        });
        m.open(p);
    }

    public static void lobby(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.LOBBY, Msg.trFor(p, Category.LOBBY.key())).perm(Perm.LOBBY_ADMIN);
        m.renderer(menu -> {
            boolean set = Ac.get().lobby.isSet();
            menu.info(Btn.of(Items.OAK_DOOR).name(Category.LOBBY, Msg.tr(Category.LOBBY.key())).desc(Msg.tr("panel.desc.lobby"))
                    .status(set ? Theme.GREEN : Theme.RED, Theme.Sym.DOT.sp() + Msg.tr(set ? "panel.lobby-set" : "panel.lobby-not-set")).build());
            action(menu, 20, Items.ENDER_PEARL, Category.LOBBY, "panel.lobby.go", "panel.lobby.go-desc", "lobby");
            action(menu, 21, Items.WHITE_BANNER, Category.LOBBY, "panel.lobby.set", "panel.lobby.set-desc", "lobby set");
            action(menu, 22, Items.RED_BED, Category.LOBBY, "panel.lobby.spawn", "panel.lobby.spawn-desc", "lobby setspawn");
            boolean editing = Ac.get().lobby.isEditing(p.getUuid());
            menu.set(23, Btn.of(Items.WOODEN_AXE).name(Category.LOBBY, Msg.tr("panel.lobby.edit")).desc(Msg.tr("panel.lobby.edit-desc"))
                    .onOff(editing).left(Msg.tr(editing ? "ui.action.turn-off" : "ui.action.turn-on")).build(), (pl, c) -> {
                Mc.run(pl, "lobby edit");
                menu.refresh();
            });
            action(menu, 24, Items.CHEST, Category.LOBBY, "panel.lobby.chest", "panel.lobby.chest-desc", "lobby chest");
        });
        m.open(p);
    }

    private static void action(Menu menu, int slot, net.minecraft.item.Item icon, Category cat, String nameKey, String descKey, String command) {
        menu.set(slot, Btn.of(icon).name(cat, Msg.tr(nameKey)).desc(Msg.tr(descKey)).left(Msg.tr("panel.action.do")).build(), (pl, c) -> {
            pl.closeHandledScreen();
            Mc.run(pl, command);
        });
    }

    public static void jail(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.JAIL, Msg.trFor(p, Category.JAIL.key())).perm(Perm.JAIL);
        m.renderer(menu -> {
            Ac ac = Ac.get();
            List<JailManager.Record> list = ac.jail.list();
            boolean onlineOnly = Ac.config().jail.onlineTimeOnly;
            menu.info(Btn.of(Items.IRON_BARS).name(Category.JAIL, Msg.tr(Category.JAIL.key())).desc(Msg.tr("panel.desc.jail"))
                    .line(Msg.tr("panel.jailed", list.size())).line(Msg.tr("panel.cells", ac.jail.cells().size()))
                    .hint(Msg.tr("panel.hint.jail")).build());
            menu.list(list, r -> Btn.head(r.player, r.name).name(Category.JAIL, r.name)
                            .desc(r.reason)
                            .line(Msg.tr("panel.by", r.by, ago(r.jailedAt)))
                            .line(Theme.Sym.CLOCK.sp() + Msg.tr("ui.left", Durations.format(ac.jail.remaining(r.player, onlineOnly))))
                            .left(Msg.tr("panel.action.inspect")).shift(Msg.tr("panel.action.release")).build(),
                    r -> (pl, c) -> {
                        if (c.isShift()) {
                            Confirm.open(pl, Category.JAIL, Msg.tr("panel.confirm.release", r.name), Msg.tr("panel.confirm.release-detail"),
                                    Btn.head(r.player, r.name).name(Category.JAIL, r.name).build(), () -> Mc.run(pl, "unjail " + Msg.q(r.name)));
                        } else {
                            InspectMenu.open(pl, r.player);
                        }
                    },
                    r -> r.name + " " + r.reason,
                    List.of(), Msg.tr("panel.empty.jail"), Msg.tr("panel.empty.jail-hint"));
        });
        m.open(p);
    }

    public static void traders(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.TRADERS, Msg.trFor(p, Category.TRADERS.key())).perm(Perm.TRADER_ADMIN);
        m.renderer(menu -> {
            List<com.vylorq.anticheat.core.trader.Trader> list = new ArrayList<>(Ac.get().traders.traders.values());
            menu.info(Btn.of(Items.EMERALD).name(Category.TRADERS, Msg.tr(Category.TRADERS.key())).desc(Msg.tr("panel.desc.traders"))
                    .line(Msg.tr("panel.count", list.size())).build());
            menu.set(Menu.INFO + 1, Btn.of(Items.BLAZE_ROD).name(Category.TRADERS, Msg.tr("panel.trader-stick"))
                    .desc(Msg.tr("panel.trader-stick-desc")).left(Msg.tr("panel.action.get")).build(), (pl, c) -> {
                pl.closeHandledScreen();
                Mc.run(pl, "trader stick");
            });
            menu.list(list, t -> Btn.of(Items.EMERALD).name(Category.TRADERS, t.name)
                            .line(t.specialty.name().toLowerCase(java.util.Locale.ROOT) + " · " + t.type.name().toLowerCase(java.util.Locale.ROOT))
                            .line(t.location == null ? "" : t.location.world().replace("minecraft:", "") + " " + (int) t.location.x() + ", " + (int) t.location.z())
                            .line(Msg.tr("panel.offers", t.offers.size()))
                            .left(Msg.tr("panel.action.teleport")).right(Msg.tr("panel.action.edit")).shift(Msg.tr("panel.action.delete")).build(),
                    t -> (pl, c) -> {
                        int number = new ArrayList<>(Ac.get().traders.traders.values()).indexOf(t) + 1;
                        if (number <= 0) {
                            return;
                        }
                        if (c.isShift()) {
                            Confirm.open(pl, Category.TRADERS, Msg.tr("panel.confirm.delete-trader", t.name), Msg.tr("panel.confirm.delete-trader-detail"),
                                    Btn.of(Items.EMERALD).name(Category.TRADERS, t.name).build(), () -> Mc.run(pl, "trader remove " + number));
                        } else if (c.isRight()) {
                            Mc.run(pl, "trader edit " + number);
                        } else if (t.location != null) {
                            pl.closeHandledScreen();
                            Mc.run(pl, "ac tp " + t.location.world() + " " + (int) t.location.x() + " " + (int) t.location.y() + " " + (int) t.location.z());
                        }
                    },
                    t -> t.name + " " + t.specialty,
                    List.of(), Msg.tr("panel.empty.traders"), Msg.tr("panel.empty.traders-hint"));
        });
        m.open(p);
    }

    // ---- Tools ----

    public static void staffTools(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.STAFF, Msg.trFor(p, Category.STAFF.key())).perm(Perm.VANISH);
        m.renderer(menu -> {
            Ac ac = Ac.get();
            menu.info(Btn.of(Items.COMPASS).name(Category.STAFF, Msg.tr(Category.STAFF.key())).desc(Msg.tr("panel.desc.staff")).build());
            boolean vanished = StaffTools.isVanished(p.getUuid());
            menu.set(20, Btn.of(Items.GLASS_BOTTLE).name(Category.STAFF, Msg.tr("panel.vanish")).desc(Msg.tr("panel.vanish-desc"))
                    .onOff(vanished).left(Msg.tr(vanished ? "ui.action.turn-off" : "ui.action.turn-on")).build(), Perm.VANISH, (pl, c) -> {
                Mc.run(pl, "vanish");
                menu.refresh();
            });
            boolean alerts = com.vylorq.anticheat.feature.Alerts.enabled(p.getUuid());
            menu.set(21, Btn.of(Items.BELL).name(Category.STAFF, Msg.tr("panel.alerts")).desc(Msg.tr("panel.alerts-desc"))
                    .onOff(alerts).left(Msg.tr(alerts ? "ui.action.turn-off" : "ui.action.turn-on")).build(), Perm.ALERTS, (pl, c) -> {
                Mc.run(pl, "ac alerts");
                menu.refresh();
            });
            boolean quiet = ac.misc.quietUi.contains(p.getUuid());
            menu.set(22, Btn.of(Items.NOTE_BLOCK).name(Category.STAFF, Msg.tr("panel.sounds")).desc(Msg.tr("panel.sounds-desc"))
                    .onOff(!quiet).left(Msg.tr(quiet ? "ui.action.turn-on" : "ui.action.turn-off")).build(), null, (pl, c) -> {
                if (!ac.misc.quietUi.remove(pl.getUuid())) {
                    ac.misc.quietUi.add(pl.getUuid());
                }
                Ac.markDirty("misc");
                Sounds.play(pl, Sounds.Ui.SUCCESS);
                menu.refresh();
            });
            boolean maint = ac.staff.maintenance();
            menu.set(23, Btn.of(Items.REDSTONE_TORCH).name(Category.STAFF, Msg.tr("panel.maintenance")).desc(Msg.tr("panel.maintenance-desc"))
                    .onOff(maint).left(Msg.tr(maint ? "ui.action.turn-off" : "ui.action.turn-on")).build(), Perm.MAINTENANCE, (pl, c) -> {
                if (maint) {
                    Mc.run(pl, "maintenance off");
                    menu.refresh();
                } else {
                    Confirm.open(pl, Category.STAFF, Msg.tr("panel.confirm.maintenance"), Msg.tr("panel.confirm.maintenance-detail"), null,
                            () -> Mc.run(pl, "maintenance on"));
                }
            });
            menu.set(24, Btn.of(Items.WOODEN_HOE).name(Category.STAFF, Msg.tr("panel.inspector")).desc(Msg.tr("panel.inspector-desc"))
                    .left(Msg.tr("panel.action.get")).build(), Perm.INSPECTOR_TOOL, (pl, c) -> Mc.run(pl, "ac inspector"));
            menu.set(29, Btn.of(Items.STICK).name(Category.CLAIMS, Msg.tr("panel.claim-stick")).desc(Msg.tr("panel.claim-stick-desc"))
                    .left(Msg.tr("panel.action.get")).build(), Perm.CLAIM, (pl, c) -> Mc.run(pl, "claim wand"));
            menu.set(30, Btn.of(Items.WRITABLE_BOOK).name(Category.STAFF, Msg.tr("panel.stats")).desc(Msg.tr("panel.stats-desc"))
                    .left(Msg.tr("ui.action.open")).build(), Perm.STATS, (pl, c) -> StatsMenu.open(pl));
            menu.set(31, Btn.of(Items.OAK_SIGN).name(Category.STAFF, Msg.tr("panel.staffchat")).desc(Msg.tr("panel.staffchat-desc"))
                    .left(Msg.tr("panel.action.write")).build(), Perm.STAFF_CHAT, (pl, c) ->
                    Input.text(pl, Msg.tr("panel.staffchat"), "", txt -> {
                        if (txt != null && !txt.isBlank()) {
                            StaffTools.staffChat(pl, txt);
                        }
                    }));
            menu.set(32, Btn.of(Items.BOOK).name(Category.STAFF, Msg.tr("panel.help")).desc(Msg.tr("panel.help-desc"))
                    .left(Msg.tr("ui.action.open")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Mc.run(pl, "vigil help");
            });
            menu.set(33, Btn.of(Items.GLOBE_BANNER_PATTERN).name(Category.STAFF, Msg.tr("panel.language")).desc(Msg.tr("panel.language-desc"))
                    .line(Msg.tr("panel.language-now", Msg.tr("lang.name"))).left(Msg.tr("panel.action.change")).build(), null,
                    (pl, c) -> LanguageMenu.open(pl));
        });
        m.open(p);
    }

    public static void lag(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.LAG, Msg.trFor(p, Category.LAG.key())).perm(Perm.LAG).live();
        m.renderer(menu -> {
            double tps = Tps.tps();
            int entities = 0;
            int chunks = 0;
            for (var w : Ac.server().getWorlds()) {
                for (var ignored : w.iterateEntities()) {
                    entities++;
                }
                chunks += w.getChunkManager().getLoadedChunkCount();
            }
            int color = tps >= 19 ? Theme.GREEN : tps >= 15 ? Theme.GOLD : Theme.RED;
            menu.info(Btn.of(Items.CLOCK).name(Category.LAG, Msg.tr(Category.LAG.key())).desc(Msg.tr("panel.desc.lag")).build());
            menu.icon(20, Btn.of(Items.CLOCK).name(Category.LAG, "TPS").status(color, Theme.Sym.DOT.sp()
                    + String.format(java.util.Locale.ROOT, "%.1f / 20", tps)).line(String.format(java.util.Locale.ROOT, "%.1f ms", Tps.mspt())).build());
            menu.icon(22, Btn.of(Items.ZOMBIE_HEAD).name(Category.LAG, Msg.tr("panel.entities")).line(String.valueOf(entities)).build());
            menu.icon(24, Btn.of(Items.GRASS_BLOCK).name(Category.LAG, Msg.tr("panel.chunks")).line(String.valueOf(chunks)).build());
            menu.set(31, Btn.of(Items.SPYGLASS).name(Category.LAG, Msg.tr("panel.lag-report")).desc(Msg.tr("panel.lag-report-desc"))
                    .left(Msg.tr("ui.action.open")).build(), (pl, c) -> {
                pl.closeHandledScreen();
                Mc.run(pl, "lag");
            });
        });
        m.open(p);
    }

    public static void waiting(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.WAITING, Msg.trFor(p, Category.WAITING.key())).perm(Perm.WAITING_ROOM);
        m.renderer(menu -> {
            List<WaitingRoom.Request> list = Ac.get().waitingRoom.pending();
            boolean on = Ac.config().waitingRoom.enabled;
            menu.info(Btn.of(Items.BLACK_CANDLE).name(Category.WAITING, Msg.tr(Category.WAITING.key())).desc(Msg.tr("panel.desc.waiting"))
                    .onOff(on).line(Msg.tr("panel.requests", list.size())).build());
            menu.list(list, r -> {
                        Btn b = Btn.head(r.player, r.name).name(Category.WAITING, r.name)
                                .line(Msg.tr(r.bedrock ? "panel.bedrock" : "panel.java"))
                                .line(Msg.tr("panel.waiting-since", ago(r.joinedAt)));
                        for (int i = 0; i < r.answers.length; i++) {
                            if (r.answers[i] != null && !r.answers[i].isEmpty()) {
                                b.desc((i + 1) + ". " + r.answers[i]);
                            }
                        }
                        return b.left(Msg.tr("panel.action.accept")).right(Msg.tr("panel.action.deny"))
                                .shift(Msg.tr("panel.action.teleport")).glint(true).build();
                    },
                    r -> (pl, c) -> {
                        String n = Msg.q(r.name);
                        if (c.isShift()) {
                            pl.closeHandledScreen();
                            Mc.run(pl, "requests tp " + n);
                        } else if (c.isRight()) {
                            Confirm.open(pl, Category.WAITING, Msg.tr("panel.confirm.deny", r.name), Msg.tr("panel.confirm.deny-detail"),
                                    Btn.head(r.player, r.name).name(Category.WAITING, r.name).build(), () -> Mc.run(pl, "requests deny " + n));
                        } else {
                            Mc.run(pl, "requests accept " + n);
                            menu.refresh();
                        }
                    },
                    r -> r.name + " " + String.join(" ", java.util.Arrays.stream(r.answers).map(String::valueOf).toList()),
                    List.of(), Msg.tr("panel.empty.waiting"), Msg.tr("panel.empty.waiting-hint"));
        });
        m.open(p);
    }
}
