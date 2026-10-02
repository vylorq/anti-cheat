package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.gui.LanguageMenu;
import com.vylorq.anticheat.gui.VigilPanel;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Viewer;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /vigil (34.13): one admin root (aliases /vg and the old /ac) that opens the Vigil Panel, a clickable help grouped
 * by category that only lists what you may use, usage hints for incomplete commands, and "Did you mean ...?" for
 * typos. Every admin command is also reachable as /vigil &lt;command&gt;; the short top-level commands still work.
 */
public final class VigilCommands {
    private VigilCommands() {
    }

    enum Group { PEOPLE, PUNISH, PLACES, TOOLS, PLAYER }

    /** One help line: usage, description key, who may see it. */
    record Help(Group group, String root, String usage, String key, Perm perm) {
    }

    static final List<Help> HELP = List.of(
            new Help(Group.PEOPLE, "vigil", "/vigil", "help.vigil", null),
            new Help(Group.PEOPLE, "review", "/review", "help.review", Perm.REVIEW),
            new Help(Group.PEOPLE, "inspect", "/inspect <player>", "help.inspect", Perm.INSPECT),
            new Help(Group.PEOPLE, "whereis", "/whereis <player>", "help.whereis", Perm.INSPECT),
            new Help(Group.PEOPLE, "watch", "/watch add|remove|list|history <player>", "help.watch", Perm.WATCH),
            new Help(Group.PEOPLE, "exempt", "/exempt add|remove|list <player>", "help.exempt", Perm.EXEMPT),
            new Help(Group.PEOPLE, "report", "/report list", "help.reports", Perm.REVIEW),
            new Help(Group.PEOPLE, "deaths", "/deaths <player>", "help.deaths", Perm.DEATHS),
            new Help(Group.PEOPLE, "log", "/vigil log [player]", "help.log", Perm.ALERTS),
            new Help(Group.PUNISH, "warn", "/warn <player> <reason>", "help.warn", Perm.WARN),
            new Help(Group.PUNISH, "mute", "/mute <player> <time> <reason>", "help.mute", Perm.MUTE),
            new Help(Group.PUNISH, "unmute", "/unmute <player>", "help.unmute", Perm.MUTE),
            new Help(Group.PUNISH, "kick", "/kick <player> <reason>", "help.kick", Perm.KICK),
            new Help(Group.PUNISH, "tempban", "/tempban <player> <time> <reason>", "help.tempban", Perm.BAN),
            new Help(Group.PUNISH, "ban", "/ban <player> <reason>", "help.ban", Perm.BAN),
            new Help(Group.PUNISH, "unban", "/unban <player>", "help.unban", Perm.BAN),
            new Help(Group.PUNISH, "freeze", "/freeze <player>", "help.freeze", Perm.FREEZE),
            new Help(Group.PUNISH, "jail", "/jail <player> <time> <reason>", "help.jail", Perm.JAIL),
            new Help(Group.PUNISH, "unjail", "/unjail <player>", "help.unjail", Perm.JAIL),
            new Help(Group.PUNISH, "rollback", "/rollback <player> <time> [radius]", "help.rollback", Perm.ROLLBACK),
            new Help(Group.PUNISH, "restore", "/restore <player> <time> [radius]", "help.restore", Perm.ROLLBACK),
            new Help(Group.PLACES, "claim", "/claim wand|create|menu|who|near|spawn", "help.claim", Perm.CLAIM),
            new Help(Group.PLACES, "lockedbox", "/lockedbox wand|create [height]|remove", "help.lockedbox", Perm.MANAGE_ADMINS),
            new Help(Group.PLACES, "barrier", "/barrier create|remove|list|newplayers|reset", "help.barrier", Perm.BARRIER),
            new Help(Group.PLACES, "lobby", "/lobby set|setspawn|edit|chest", "help.lobby", Perm.LOBBY_ADMIN),
            new Help(Group.PLACES, "arena", "/arena create|menu|kit save", "help.arena", Perm.ARENA_ADMIN),
            new Help(Group.PLACES, "trader", "/trader stick|create|list|edit|remove", "help.trader", Perm.TRADER_ADMIN),
            new Help(Group.PLACES, "requests", "/requests [accept|deny|tp <player>]", "help.requests", Perm.WAITING_ROOM),
            new Help(Group.PLACES, "waitingroom", "/waitingroom set", "help.waitingroom", Perm.WAITING_ROOM),
            new Help(Group.TOOLS, "vanish", "/vanish", "help.vanish", Perm.VANISH),
            new Help(Group.TOOLS, "sc", "/sc <message>", "help.sc", Perm.STAFF_CHAT),
            new Help(Group.TOOLS, "login", "/login <pin>", "help.login", Perm.ALERTS),
            new Help(Group.TOOLS, "maintenance", "/maintenance on|off", "help.maintenance", Perm.MAINTENANCE),
            new Help(Group.TOOLS, "lag", "/lag", "help.lag", Perm.LAG),
            new Help(Group.TOOLS, "settings", "/settings", "help.settings", Perm.SETTINGS),
            new Help(Group.TOOLS, "end", "/vigil end open|close|portal|remove", "help.end", Perm.SETTINGS),
            new Help(Group.TOOLS, "watcher", "/vigil watcher on|off|summon|night|log|exclude", "help.watcher", Perm.WATCHER),
            new Help(Group.TOOLS, "alerts", "/vigil alerts", "help.alerts", Perm.ALERTS),
            new Help(Group.TOOLS, "stats", "/vigil stats", "help.stats", Perm.STATS),
            new Help(Group.TOOLS, "inspector", "/vigil inspector", "help.inspector", Perm.INSPECTOR_TOOL),
            new Help(Group.TOOLS, "tp", "/vigil tp <world> <x> <y> <z>", "help.tp", Perm.TELEPORT),
            new Help(Group.TOOLS, "builder", "/vigil builder add|remove|list <player> [time] [anywhere]", "help.builder", Perm.MANAGE_ADMINS),
            new Help(Group.TOOLS, "build", "/build set|replace|walls|copy|paste|rotate|undo|load|save|import", "help.build", Perm.MANAGE_ADMINS),
            new Help(Group.TOOLS, "tempadmin", "/vigil tempadmin add|remove <player>", "help.tempadmin", Perm.MANAGE_ADMINS),
            new Help(Group.TOOLS, "event", "/vigil event ...", "help.event", Perm.EVENTS),
            new Help(Group.TOOLS, "restart", "/vigil restart", "help.restart", Perm.RESTART),
            new Help(Group.TOOLS, "backup", "/vigil backup", "help.backup", Perm.RESTART),
            new Help(Group.TOOLS, "reload", "/vigil reload", "help.reload", Perm.RELOAD),
            new Help(Group.PLAYER, "trade", "/trade <player>", "help.trade", null),
            new Help(Group.PLAYER, "value", "/value [inventory]", "help.value", null),
            new Help(Group.PLAYER, "market", "/market [item]", "help.market", null),
            new Help(Group.PLAYER, "balance", "/balance [player]", "help.balance", null),
            new Help(Group.PLAYER, "pay", "/pay <player> <amount>", "help.pay", null),
            new Help(Group.PLAYER, "baltop", "/baltop", "help.baltop", null),
            new Help(Group.PLAYER, "ah", "/ah  ·  /ah sell <price>", "help.ah", null),
            new Help(Group.PLAYER, "orders", "/orders  ·  /order <item> <amount> <price>", "help.orders", null),
            new Help(Group.PLAYER, "collect", "/collect", "help.collect", null),
            new Help(Group.PLAYER, "bounty", "/bounty <player>  ·  /bounties", "help.bounty", null),
            new Help(Group.PLAYER, "shop", "/shop sell|buy <price> <cash|emeralds|item> [per sale]  ·  /shop remove  ·  /shops", "help.shop", null),
            new Help(Group.PLAYER, "booth", "/booth  ·  /booth claim|add <price> <cash|emeralds|item>|offers|leave  ·  /booths", "help.booth", null),
            new Help(Group.PLACES, "booth-admin", "/booth create|delete", "help.booth-admin", Perm.TRADER_ADMIN),
            new Help(Group.PEOPLE, "notes", "/note <player> <text>  ·  /notes <player>", "help.notes", Perm.INSPECT),
            new Help(Group.PEOPLE, "snapshot", "/snapshot <player> [note]  ·  /snapshots <player>", "help.snapshot", Perm.INSPECT_EDIT),
            new Help(Group.TOOLS, "blacklist", "/blacklist add [item]|remove <item>|list", "help.blacklist", Perm.SETTINGS),
            new Help(Group.TOOLS, "servershop-add", "/servershop add <buy> <sell>", "help.servershop-add", Perm.SETTINGS),
            new Help(Group.PLAYER, "servershop", "/servershop", "help.servershop", null),
            new Help(Group.PEOPLE, "invtools", "/invtools <player>", "help.invtools", Perm.INSPECT_EDIT),
            new Help(Group.TOOLS, "eco", "/eco give|take|set <player> <amount>", "help.eco", Perm.SETTINGS),
            new Help(Group.PLAYER, "report", "/report <player> <reason>", "help.report", null),
            new Help(Group.PLAYER, "lobby", "/lobby", "help.lobby-go", null),
            new Help(Group.PLAYER, "request", "/request join", "help.request", null),
            new Help(Group.PLAYER, "duel", "/duel <player>", "help.duel", null),
            new Help(Group.PLAYER, "arena", "/arena join|leave|spectate", "help.arena-play", null),
            new Help(Group.PLAYER, "language", "/language", "help.language", null),
            new Help(Group.PLAYER, "team", "/team create|invite|join|leave|claim|home|chat|info|list|vault|ally|allies|where|map|mapshare|ping|border|motd|top|allypvp|level|war|bank", "help.team", null),
            new Help(Group.PLAYER, "tc", "/tc <message>", "help.tc", null),
            new Help(Group.PLAYER, "tca", "/tca <message>", "help.tca", null),
            new Help(Group.TOOLS, "announce", "/announce <message>  (Title|Subtitle, &colours)", "help.announce", Perm.EVENTS),
            new Help(Group.TOOLS, "notify", "/notify <players> <message>", "help.notify", Perm.EVENTS),
            new Help(Group.PLAYER, "stats", "/stats [player] | /stats top [playtime|kills|deaths|mined|walked]", "help.pstats", null),
            new Help(Group.PLAYER, "events", "/events [list]", "help.events-status", null),
            new Help(Group.TOOLS, "events", "/events start <event> | /events stop", "help.events", Perm.EVENTS));

    /** Commands reachable as /vigil &lt;name&gt; (they also stay top-level). */
    private static final List<String> UNDER_VIGIL = List.of("review", "inspect", "whereis", "watch", "exempt", "freeze", "jail", "unjail",
            "claim", "barrier", "lockedbox", "events", "lobby", "arena", "trader", "requests", "deaths", "rollback", "restore", "lag", "vanish", "maintenance",
            "settings", "end", "builder", "watcher", "waitingroom", "report", "warn", "mute", "unmute", "kick", "tempban", "ban", "unban", "login");

    private static final int PER_PAGE = 9;

    static boolean canSee(ServerCommandSource s) {
        ServerPlayerEntity p = s.getPlayer();
        return p == null ? Mc.hasLevel(s, 3) : Perms.isStaff(p);
    }

    static int panel(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            return help(ctx.getSource(), 1, null);
        }
        if (!Perms.pinOk(p)) {
            Msg.err(ctx.getSource(), "staff.pin.required");
            return 0;
        }
        if (!VigilPanel.canOpen(p)) {
            Msg.err(ctx.getSource(), "general.no-permission");
            return 0;
        }
        VigilPanel.open(p);
        return 1;
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        LiteralCommandNode<ServerCommandSource> vigil = (LiteralCommandNode<ServerCommandSource>) d.getRoot().getChild("vigil");
        // /vigil help [page|command]
        vigil.addChild(literal("help").executes(ctx -> help(ctx.getSource(), 1, null))
                .then(argument("what", StringArgumentType.word()).executes(ctx -> {
                    String what = StringArgumentType.getString(ctx, "what");
                    try {
                        return help(ctx.getSource(), Integer.parseInt(what), null);
                    } catch (NumberFormatException e) {
                        return help(ctx.getSource(), 1, what);
                    }
                })).build());
        vigil.addChild(literal("language").executes(ctx -> language(ctx)).build());
        vigil.addChild(literal("staffchat").requires(s -> Perms.visible(s, Perm.STAFF_CHAT)).redirect(d.getRoot().getChild("sc")).build());
        CommandNode<ServerCommandSource> log = vigil.getChild("log");
        if (log != null) {
            vigil.addChild(literal("logs").requires(log.getRequirement()).executes(log.getCommand()).redirect(log).build());
        }
        for (String name : UNDER_VIGIL) {
            CommandNode<ServerCommandSource> target = d.getRoot().getChild(name);
            if (target == null || vigil.getChild(name) != null) {
                continue;
            }
            var node = literal(name).requires(target.getRequirement()).redirect(target);
            node.executes(target.getCommand() != null ? target.getCommand() : ctx -> usage(ctx.getSource(), name));
            vigil.addChild(node.build());
        }
        // Typo in a subcommand: "Did you mean ...?"
        vigil.addChild(argument("unknown", StringArgumentType.greedyString()).executes(ctx ->
                unknown(ctx.getSource(), vigil, StringArgumentType.getString(ctx, "unknown"))).build());

        // Incomplete top-level commands show their usage instead of vanilla's "Unknown or incomplete command".
        for (Help h : HELP) {
            CommandNode<ServerCommandSource> root = d.getRoot().getChild(h.root);
            if (root != null && root.getCommand() == null && !h.root.equals("vigil")) {
                d.register(literal(h.root).executes(ctx -> usage(ctx.getSource(), h.root)));
            }
        }

        d.register(literal("vg").requires(vigil.getRequirement()).executes(VigilCommands::panel).redirect(vigil));
        d.register(literal("ac").requires(vigil.getRequirement()).executes(VigilCommands::panel).redirect(vigil));
        d.register(literal("language").executes(VigilCommands::language)
                .then(literal("auto").executes(ctx -> setLanguage(ctx, "auto")))
                .then(literal("english").executes(ctx -> setLanguage(ctx, "en_us")))
                .then(literal("arabic").executes(ctx -> setLanguage(ctx, "ar_sa"))));
    }

    private static int language(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
            return 0;
        }
        LanguageMenu.open(p);
        return 1;
    }

    private static int setLanguage(CommandContext<ServerCommandSource> ctx, String code) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
            return 0;
        }
        LanguageMenu.set(p, code);
        return 1;
    }

    private static boolean allowed(ServerCommandSource src, Help h) {
        return h.perm == null ? (h.group == Group.PLAYER || canSee(src)) : Perms.visible(src, h.perm);
    }

    /** Clickable help: grouped, only what you may use, paginated. */
    static int help(ServerCommandSource src, int page, String filter) {
        ServerPlayerEntity p = src.getPlayer();
        return Viewer.with(p, () -> {
            List<Object> lines = new ArrayList<>();
            Group last = null;
            for (Help h : HELP) {
                if (!allowed(src, h)) {
                    continue;
                }
                if (filter != null && !h.root.equalsIgnoreCase(filter) && !h.usage.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (h.group != last) {
                    lines.add(h.group);
                    last = h.group;
                }
                lines.add(h);
            }
            if (lines.isEmpty()) {
                Msg.err(src, filter == null ? "general.no-permission" : "help.no-match", filter);
                return 0;
            }
            int pages = Math.max(1, (lines.size() + PER_PAGE - 1) / PER_PAGE);
            int pg = Math.max(1, Math.min(page, pages));
            MutableText head = Theme.prefix().append(Theme.c(Msg.tr("help.title"), Theme.GOLD_LIGHT))
                    .append(Theme.c("  " + Msg.tr("ui.page", pg, pages), Theme.DIM));
            src.sendFeedback(() -> head, false);
            for (int i = (pg - 1) * PER_PAGE; i < Math.min(lines.size(), pg * PER_PAGE); i++) {
                Object o = lines.get(i);
                MutableText t;
                if (o instanceof Group g) {
                    t = Theme.c(Theme.Sym.DOT.sp() + Msg.tr("help.group." + g.name().toLowerCase(Locale.ROOT)), Theme.VIOLET);
                } else {
                    Help h = (Help) o;
                    String desc = Msg.tr(h.key);
                    String fill = h.usage.contains(" ") ? h.usage.substring(0, h.usage.indexOf(' ') + 1) : h.usage;
                    t = Text.literal("  ").append(Msg.suggest("", fill, desc).append(Theme.c(h.usage, Theme.GOLD_LIGHT))
                            .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.SuggestCommand(fill))
                                    .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal(desc + "\n" + Msg.tr("help.click"))))))
                            .append(Theme.c("  " + desc, Theme.SOFT));
                }
                MutableText line = t;
                src.sendFeedback(() -> line, false);
            }
            if (pages > 1) {
                MutableText nav = Text.literal("  ");
                if (pg > 1) {
                    nav.append(Msg.button("", "/vigil help " + (pg - 1), Msg.tr("ui.prev")).append(Theme.c("« " + Msg.tr("ui.prev"), Theme.VIOLET))
                            .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/vigil help " + (pg - 1)))));
                    nav.append(Text.literal("   "));
                }
                if (pg < pages) {
                    nav.append(Theme.c(Msg.tr("ui.next") + " »", Theme.VIOLET)
                            .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/vigil help " + (pg + 1)))
                                    .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal(Msg.tr("ui.next"))))));
                }
                src.sendFeedback(() -> nav, false);
            }
            return 1;
        });
    }

    /** "✖ Usage: /jail <player> <time> <reason> [Click to fill]". */
    static int usage(ServerCommandSource src, String root) {
        ServerPlayerEntity p = src.getPlayer();
        Viewer.with(p, () -> {
            for (Help h : HELP) {
                if (!h.root.equals(root) || !allowed(src, h)) {
                    continue;
                }
                String fill = h.usage.contains(" ") ? h.usage.substring(0, h.usage.indexOf(' ') + 1) : h.usage;
                MutableText t = Msg.typed(Msg.Type.ERROR, Msg.tr("help.usage", h.usage)).append(Text.literal(" "))
                        .append(Msg.suggest("", fill, Msg.tr(h.key)).append(Theme.c("[" + Msg.tr("help.fill") + "]", Theme.GOLD_LIGHT))
                                .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.SuggestCommand(fill))));
                src.sendFeedback(() -> t, false);
                return;
            }
            Msg.err(src, "general.no-permission");
        });
        return 0;
    }

    private static int unknown(ServerCommandSource src, CommandNode<ServerCommandSource> vigil, String input) {
        String word = input.split(" ")[0].toLowerCase(Locale.ROOT);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (CommandNode<ServerCommandSource> c : vigil.getChildren()) {
            if (!(c instanceof LiteralCommandNode) || !c.canUse(src)) {
                continue;
            }
            int dist = distance(word, c.getName());
            if (dist < bestDist) {
                bestDist = dist;
                best = c.getName();
            }
        }
        ServerPlayerEntity p = src.getPlayer();
        String suggestion = best;
        boolean close = best != null && bestDist <= Math.max(2, word.length() / 3);
        Viewer.with(p, () -> {
            MutableText t = Msg.typed(Msg.Type.ERROR, Msg.tr("help.unknown", word));
            if (close) {
                t.append(Text.literal(" ")).append(Theme.c(Msg.tr("help.did-you-mean", "/vigil " + suggestion), Theme.GOLD_LIGHT)
                        .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.SuggestCommand("/vigil " + suggestion + " "))));
            } else {
                t.append(Text.literal(" ")).append(Theme.c("[" + Msg.tr("help.title") + "]", Theme.GOLD_LIGHT)
                        .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/vigil help"))));
            }
            src.sendFeedback(() -> t, false);
        });
        return 0;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
