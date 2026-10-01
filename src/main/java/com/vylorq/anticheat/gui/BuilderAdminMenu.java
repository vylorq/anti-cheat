package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.feature.BuilderDrafts;
import com.vylorq.anticheat.feature.BuilderLog;
import com.vylorq.anticheat.feature.BuilderMode;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** For the owner: every builder (current, past, with drafts), their logs, drafts to approve, and lobby backups. */
public final class BuilderAdminMenu {
    private BuilderAdminMenu() {
    }

    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    public static void open(ServerPlayerEntity p) {
        Menu m = Menu.std(Theme.Category.LOBBY, 6, Msg.trFor(p, "badmin.title")).perm(Perm.MANAGE_ADMINS);
        m.renderer(menu -> {
            Set<UUID> ids = new LinkedHashSet<>(Ac.get().misc.builders.keySet());
            ids.addAll(BuilderDrafts.all().keySet());
            ids.addAll(Ac.get().misc.builderNames.keySet());
            int i = 0;
            for (UUID id : ids) {
                if (i >= GRID.length) {
                    break;
                }
                String name = Ac.get().misc.builderNames.getOrDefault(id, id.toString().substring(0, 8));
                BuilderMode.Builder b = BuilderMode.get(id);
                BuilderDrafts.Draft d = BuilderDrafts.get(id);
                boolean online = Ac.server().getPlayerManager().getPlayer(id) != null;
                Btn btn = Btn.of(Items.PLAYER_HEAD).name(name);
                btn.line(Msg.tr(b == null ? "badmin.not-builder" : online ? "badmin.online" : "badmin.offline"));
                if (b != null && b.until > 0) {
                    btn.line(Msg.tr("badmin.left", Durations.format(Math.max(0, b.until - System.currentTimeMillis()))));
                }
                if (d != null) {
                    btn.status(d.submitted ? Theme.GOLD : Theme.AQUA, Theme.Sym.DOT.sp() + Msg.tr(d.submitted ? "draft.waiting" : "draft.in-progress"));
                }
                menu.set(GRID[i++], btn.glint(d != null && d.submitted).left(Msg.tr("ui.action.open")).build(), null, (pl, c) -> builder(pl, menu, id));
            }
            menu.set(41, Btn.of(Items.CHEST).name(Msg.tr("badmin.backups")).desc(Msg.tr("badmin.backups-desc"))
                    .count(BuilderDrafts.backups().size()).build(), null, (pl, c) -> backups(pl, menu));
            menu.set(39, Btn.of(Items.WRITABLE_BOOK).name(Msg.tr("badmin.add")).desc(Msg.tr("badmin.add-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b[/vigil builder add <player> [time] [anywhere|live]]", "/vigil builder add ", ""));
            });
        });
        m.open(p);
    }

    static void builder(ServerPlayerEntity p, Menu parent, UUID id) {
        String name = Ac.get().misc.builderNames.getOrDefault(id, "?");
        Menu m = Menu.std(Theme.Category.LOBBY, 5, Msg.trFor(p, "badmin.title"), name);
        m.parent(parent);
        m.renderer(menu -> {
            ServerPlayerEntity online = Ac.server().getPlayerManager().getPlayer(id);
            BuilderDrafts.Draft d = BuilderDrafts.get(id);
            BuilderLog.Summary s = BuilderLog.summary(id, 0);
            List<String> lines = new ArrayList<>();
            int shown = 0;
            for (var e : s.placed().entrySet()) {
                if (shown++ >= 6) {
                    break;
                }
                lines.add(e.getKey() + " ×" + e.getValue());
            }
            menu.info(Btn.of(Items.BOOK).name(name).desc(Msg.tr("badmin.summary", sum(s.placed()), sum(s.broken()), s.toolChanges(),
                    s.commands(), s.blocked())).lines(lines).build());
            menu.set(19, Btn.of(Items.ENDER_EYE).name(Msg.tr("badmin.watch")).desc(Msg.tr("badmin.watch-desc"))
                    .build(), null, (pl, c) -> {
                if (online != null) {
                    pl.closeHandledScreen();
                    watch(pl, online);
                } else {
                    Msg.send(pl, "badmin.offline-now");
                }
            });
            menu.set(20, Btn.of(Items.PAPER).name(Msg.tr("badmin.recent")).desc(Msg.tr("badmin.recent-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                sendRecent(pl.getCommandSource(), id, 20);
            });
            menu.set(21, Btn.of(Items.MAP).name(Msg.tr("badmin.full")).desc(Msg.tr("badmin.full-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                sendSummary(pl.getCommandSource(), id, 0);
            });
            menu.set(22, Btn.of(Items.CLOCK).name(Msg.tr("badmin.undo-hour")).desc(Msg.tr("badmin.undo-desc")).build(), null, (pl, c) ->
                    Confirm.open(pl, Theme.Category.LOBBY, Msg.trFor(pl, "badmin.undo-hour"), Msg.trFor(pl, "badmin.undo-desc"),
                            new net.minecraft.item.ItemStack(Items.CLOCK), () -> undo(pl, id, 3_600_000L)));
            menu.set(23, Btn.of(Items.RECOVERY_COMPASS).name(Msg.tr("badmin.undo-day")).desc(Msg.tr("badmin.undo-desc")).build(), null, (pl, c) ->
                    Confirm.open(pl, Theme.Category.LOBBY, Msg.trFor(pl, "badmin.undo-day"), Msg.trFor(pl, "badmin.undo-desc"),
                            new net.minecraft.item.ItemStack(Items.RECOVERY_COMPASS), () -> undo(pl, id, 86_400_000L)));
            if (d != null) {
                menu.set(28, Btn.of(Items.SPYGLASS).name(Msg.tr("draft.review")).desc(Msg.tr("badmin.review-desc")).build(), null, (pl, c) -> {
                    pl.closeHandledScreen();
                    BuilderDrafts.review(pl, id);
                });
                menu.set(29, Btn.of(Items.LIME_CONCRETE).name(Msg.tr("draft.approve")).desc(Msg.tr("badmin.approve-desc")).build(), null, (pl, c) ->
                        Confirm.open(pl, Theme.Category.LOBBY, Msg.trFor(pl, "draft.approve"), Msg.trFor(pl, "badmin.approve-desc"),
                                new net.minecraft.item.ItemStack(Items.LIME_CONCRETE), () -> {
                                    if (BuilderDrafts.approve(pl, id)) {
                                        Msg.send(pl, "draft.approving", name);
                                    } else {
                                        Msg.send(pl, "draft.none-for", name);
                                    }
                                }));
                menu.set(30, Btn.of(Items.RED_CONCRETE).name(Msg.tr("draft.reject")).desc(Msg.tr("badmin.reject-desc")).build(), null, (pl, c) ->
                        Confirm.open(pl, Theme.Category.LOBBY, Msg.trFor(pl, "draft.reject"), Msg.trFor(pl, "badmin.reject-desc"),
                                new net.minecraft.item.ItemStack(Items.RED_CONCRETE), () -> {
                                    BuilderDrafts.reject(pl, id);
                                    Msg.send(pl, "draft.rejecting", name);
                                }));
            }
            if (BuilderMode.get(id) != null && online != null) {
                menu.set(34, Btn.of(Items.BARRIER).name(Msg.tr("badmin.end")).desc(Msg.tr("badmin.end-desc")).build(), null, (pl, c) ->
                        Confirm.open(pl, Theme.Category.LOBBY, Msg.trFor(pl, "badmin.end"), name, new net.minecraft.item.ItemStack(Items.BARRIER),
                                () -> BuilderMode.end(pl, online)));
            }
        });
        m.open(p);
    }

    static void backups(ServerPlayerEntity p, Menu parent) {
        Menu m = Menu.std(Theme.Category.LOBBY, 6, Msg.trFor(p, "badmin.title"), Msg.trFor(p, "badmin.backups"));
        m.parent(parent);
        m.renderer(menu -> {
            List<String> list = BuilderDrafts.backups();
            menu.info(Btn.of(Items.CHEST).name(Msg.tr("badmin.backups")).desc(Msg.tr("badmin.backups-desc")).build());
            int i = 0;
            for (String name : list) {
                if (i >= GRID.length) {
                    break;
                }
                menu.set(GRID[i++], Btn.of(Items.FILLED_MAP).name(name.replace("lobby-", "")).left(Msg.tr("badmin.restore")).build(), null,
                        (pl, c) -> Confirm.open(pl, Theme.Category.LOBBY, Msg.trFor(pl, "badmin.restore"), name,
                                new net.minecraft.item.ItemStack(Items.FILLED_MAP), () -> {
                                    int n = BuilderDrafts.restore(pl, name);
                                    if (n < 0) {
                                        Msg.send(pl, "backup.not-found", name);
                                    } else {
                                        Msg.send(pl, "backup.restored", name, n);
                                    }
                                }));
            }
            menu.set(40, Btn.of(Items.WRITABLE_BOOK).name(Msg.tr("badmin.backup-now")).build(), null, (pl, c) -> {
                String n = BuilderDrafts.backup("manual");
                Msg.send(pl, n == null ? "backup.failed" : "backup.saved", n);
                menu.refresh();
            });
        });
        m.open(p);
    }

    private static int sum(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static void undo(ServerPlayerEntity by, UUID id, long ms) {
        int n = BuilderLog.undoSince(by, id, System.currentTimeMillis() - ms);
        Msg.send(by, "builder.undoing", n);
    }

    /** Goes invisibly next to a builder. */
    public static void watch(ServerPlayerEntity owner, ServerPlayerEntity builder) {
        StaffTools.teleportTo(owner, (ServerWorld) builder.getEntityWorld(),
                new Vec3(builder.getX() + 2, builder.getY() + 1, builder.getZ() + 2), true, "builder " + builder.getGameProfile().name());
        Msg.send(owner, "badmin.watching", builder.getGameProfile().name());
    }

    private static String top(Map<String, Integer> m, int n) {
        List<String> out = new ArrayList<>();
        for (var e : m.entrySet()) {
            if (out.size() >= n) {
                break;
            }
            out.add(e.getKey() + " " + e.getValue());
        }
        return out.isEmpty() ? "-" : String.join(", ", out);
    }

    public static void sendSummary(ServerCommandSource src, UUID id, long since) {
        String name = Ac.get().misc.builderNames.getOrDefault(id, "?");
        BuilderLog.Summary s = BuilderLog.summary(id, since);
        String from = s.first() == 0 ? "-" : Instant.ofEpochMilli(s.first()).toString().substring(0, 16).replace('T', ' ');
        String to = s.last() == 0 ? "-" : Instant.ofEpochMilli(s.last()).toString().substring(0, 16).replace('T', ' ');
        src.sendFeedback(() -> Text.literal(Msg.tr("badmin.log-head", name, from, to, s.lines())), false);
        src.sendFeedback(() -> Text.literal(Msg.tr("badmin.log-placed", sum(s.placed()), top(s.placed(), 10))), false);
        src.sendFeedback(() -> Text.literal(Msg.tr("badmin.log-broken", sum(s.broken()), top(s.broken(), 10))), false);
        src.sendFeedback(() -> Text.literal(Msg.tr("badmin.log-taken", top(s.taken(), 10))), false);
        src.sendFeedback(() -> Text.literal(Msg.tr("badmin.log-other", s.toolChanges(), s.commands(), s.blocked())), false);
        src.sendFeedback(() -> Text.literal(Msg.tr("badmin.log-file", "config/vigil/builder-logs/" + id + "/")), false);
    }

    public static void sendRecent(ServerCommandSource src, UUID id, int n) {
        List<BuilderLog.Entry> all = BuilderLog.read(id, 0);
        String name = Ac.get().misc.builderNames.getOrDefault(id, "?");
        src.sendFeedback(() -> Text.literal(Msg.tr("badmin.recent-head", name, Math.min(n, all.size()), all.size())), false);
        for (int i = Math.max(0, all.size() - n); i < all.size(); i++) {
            String line = "§7" + all.get(i).pretty();
            src.sendFeedback(() -> Text.literal(line), false);
        }
    }
}
