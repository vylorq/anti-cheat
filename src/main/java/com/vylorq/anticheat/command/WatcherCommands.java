package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.watcher.WatcherEffect;
import com.vylorq.anticheat.core.watcher.WatcherScheduler;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.Watcher;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

/** /watcher (section 33.9). Owner only. */
public final class WatcherCommands {
    private WatcherCommands() {
    }

    private static final SuggestionProvider<ServerCommandSource> EFFECTS = (ctx, b) -> {
        for (WatcherEffect e : WatcherEffect.values()) {
            b.suggest(e.id());
        }
        return b.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("watcher").requires(s -> Perms.visible(s, Perm.WATCHER))
                .executes(WatcherCommands::status)
                .then(literal("help").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
                    Msg.ok(ctx.getSource(), "watcher.help");
                    return 1;
                }))
                .then(literal("on").executes(ctx -> setEnabled(ctx, true)))
                .then(literal("off").executes(ctx -> setEnabled(ctx, false)))
                .then(literal("summon").then(Args.player("player")
                        .executes(ctx -> summon(ctx, null))
                        .then(Args.word("effect").suggests(EFFECTS).executes(ctx -> summon(ctx, Args.str(ctx, "effect"))))))
                .then(literal("night").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
                    int n = Watcher.night(Staff.name(ctx.getSource().getPlayer()));
                    Staff.log(ctx.getSource().getPlayer(), "watcher-night", null, null, String.valueOf(n));
                    Msg.ok(ctx.getSource(), "watcher.night", n);
                    return 1;
                }))
                .then(literal("log").executes(WatcherCommands::log))
                .then(literal("exclude")
                        .then(literal("add").then(Args.player("player").executes(ctx -> exclude(ctx, true))))
                        .then(literal("remove").then(Args.player("player").executes(ctx -> exclude(ctx, false))))
                        .then(literal("list").executes(WatcherCommands::excludeList))));
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
        var c = Ac.config().watcher;
        Msg.ok(ctx.getSource(), "watcher.status", c.enabled ? "§aawake" : "§7asleep", c.minMinutes, c.maxMinutes,
                Ac.get().watcher.data().excluded.size());
        Msg.ok(ctx.getSource(), "watcher.help");
        return 1;
    }

    private static int setEnabled(CommandContext<ServerCommandSource> ctx, boolean on) {
        if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
        Ac.config().watcher.enabled = on;
        Ac.get().configManager.save();
        if (!on) {
            Watcher.stopAll();
        }
        Staff.log(ctx.getSource().getPlayer(), "watcher", null, null, on ? "on" : "off");
        Msg.ok(ctx.getSource(), on ? "watcher.on" : "watcher.off");
        return 1;
    }

    private static int summon(CommandContext<ServerCommandSource> ctx, String effectId) {
        if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
        ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
        if (t == null) return 0;
        String name = t.getGameProfile().name();
        WatcherScheduler s = Ac.get().watcher;
        if (s.excluded(t.getUuid())) {
            Msg.err(ctx.getSource(), "watcher.excluded-player", name);
            return 0;
        }
        if (Watcher.busy(t.getUuid())) {
            Msg.err(ctx.getSource(), "watcher.busy", name);
            return 0;
        }
        WatcherEffect eff;
        if (effectId == null) {
            eff = s.pick(t.getUuid(), Watcher.settings());
            if (eff == null) {
                eff = WatcherEffect.APPEAR;
            }
        } else {
            eff = WatcherEffect.byId(effectId);
            if (eff == null) {
                Msg.err(ctx.getSource(), "watcher.unknown-effect", effectId);
                return 0;
            }
        }
        WatcherEffect started = Watcher.start(t, eff, Staff.name(ctx.getSource().getPlayer()), true);
        if (started == null) {
            Msg.err(ctx.getSource(), "watcher.summon-failed", name, eff.id());
            return 0;
        }
        Staff.log(ctx.getSource().getPlayer(), "watcher-summon", t.getUuid(), name, started.id());
        Msg.ok(ctx.getSource(), "watcher.summoned", name, started.id());
        return 1;
    }

    private static int log(CommandContext<ServerCommandSource> ctx) {
        if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
        List<WatcherScheduler.LogEntry> list = Ac.get().watcher.recent(20);
        if (list.isEmpty()) {
            Msg.ok(ctx.getSource(), "watcher.log-empty");
            return 1;
        }
        Msg.ok(ctx.getSource(), "watcher.log-header");
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
        for (WatcherScheduler.LogEntry e : list) {
            Text line = Msg.gray(f.format(new Date(e.time())) + "  ").append(Text.literal("§f" + e.name()))
                    .append(Text.literal(" §8" + e.effect() + " §7(" + e.by() + ")"));
            ctx.getSource().sendFeedback(() -> line, false);
        }
        return 1;
    }

    private static int exclude(CommandContext<ServerCommandSource> ctx, boolean add) {
        if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
        String name = Args.str(ctx, "player");
        UUID id = Args.known(ctx.getSource(), name);
        if (id == null) return 0;
        Ac.get().watcher.exclude(id, add);
        Ac.markDirty("watcher");
        if (add) {
            Watcher.stopFor(id, false);
        }
        String shown = Args.nameOf(id, name);
        Staff.log(ctx.getSource().getPlayer(), add ? "watcher-exclude" : "watcher-include", id, shown, "");
        Msg.ok(ctx.getSource(), add ? "watcher.exclude-added" : "watcher.exclude-removed", shown);
        return 1;
    }

    private static int excludeList(CommandContext<ServerCommandSource> ctx) {
        if (!Perms.check(ctx.getSource(), Perm.WATCHER)) return 0;
        List<String> names = new ArrayList<>();
        for (UUID id : Ac.get().watcher.data().excluded) {
            names.add(Args.nameOf(id, id.toString()));
        }
        if (names.isEmpty()) {
            Msg.ok(ctx.getSource(), "watcher.exclude-none");
        } else {
            Msg.ok(ctx.getSource(), "watcher.exclude-list", String.join(", ", names));
        }
        return 1;
    }
}
