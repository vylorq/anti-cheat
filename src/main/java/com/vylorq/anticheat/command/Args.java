package com.vylorq.anticheat.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Argument helpers. Player arguments accept Bedrock names with a prefix and spaces (quote them: "\".Steve Two\"")
 * and offline players the server has seen before.
 */
public final class Args {
    private Args() {
    }

    public static final SuggestionProvider<ServerCommandSource> ONLINE = (ctx, b) -> {
        for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
            b.suggest(Msg.q(p.getGameProfile().name()));
        }
        return b.buildFuture();
    };

    public static RequiredArgumentBuilder<ServerCommandSource, String> player(String name) {
        return CommandManager.argument(name, StringArgumentType.string()).suggests(ONLINE);
    }

    public static RequiredArgumentBuilder<ServerCommandSource, String> word(String name) {
        return CommandManager.argument(name, StringArgumentType.word());
    }

    public static RequiredArgumentBuilder<ServerCommandSource, String> rest(String name) {
        return CommandManager.argument(name, StringArgumentType.greedyString());
    }

    public static String str(CommandContext<ServerCommandSource> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    public static String strOr(CommandContext<ServerCommandSource> ctx, String name, String def) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /** Online player by exact or case-insensitive name; Bedrock prefix optional. */
    public static ServerPlayerEntity online(String name) {
        var pm = Ac.server().getPlayerManager();
        ServerPlayerEntity p = pm.getPlayer(name);
        if (p != null) {
            return p;
        }
        for (ServerPlayerEntity o : pm.getPlayerList()) {
            String n = o.getGameProfile().name();
            if (n.equalsIgnoreCase(name) || (n.length() > 1 && !Character.isLetterOrDigit(n.charAt(0)) && n.substring(1).equalsIgnoreCase(name))) {
                return o;
            }
        }
        return null;
    }

    /** A player the server knows (online or offline), or null with an error sent. */
    public static UUID known(ServerCommandSource src, String name) {
        ServerPlayerEntity p = online(name);
        if (p != null) {
            return p.getUuid();
        }
        UUID id = Ac.get().joins.findByName(name);
        if (id == null) {
            String close = closest(name, false);
            if (close != null) {
                Msg.err(src, "general.unknown-player-suggest", name, close);
            } else {
                Msg.err(src, "general.unknown-player", name);
            }
        }
        return id;
    }

    public static ServerPlayerEntity requireOnline(ServerCommandSource src, String name) {
        ServerPlayerEntity p = online(name);
        if (p == null) {
            String close = closest(name, true);
            if (close != null) {
                Msg.err(src, "general.not-online-suggest", name, close);
            } else {
                Msg.err(src, "general.not-online", name);
            }
        }
        return p;
    }

    /** The nearest player name to a typo ("Stev" -> "Steve"), or null when nothing is close. */
    public static String closest(String typed, boolean onlineOnly) {
        String t = typed.toLowerCase(java.util.Locale.ROOT);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            names.add(o.getGameProfile().name());
        }
        if (!onlineOnly) {
            names.addAll(Ac.get().joins.data().names.values());
        }
        for (String n : names) {
            if (n == null) {
                continue;
            }
            String bare = n.length() > 1 && !Character.isLetterOrDigit(n.charAt(0)) ? n.substring(1) : n;
            int d = Math.min(VigilCommands.distance(t, n.toLowerCase(java.util.Locale.ROOT)), VigilCommands.distance(t, bare.toLowerCase(java.util.Locale.ROOT)));
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best != null && bestDist <= Math.max(1, Math.min(3, t.length() / 3)) ? best : null;
    }

    public static String nameOf(UUID id, String fallback) {
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
        if (p != null) {
            return p.getGameProfile().name();
        }
        String n = Ac.get().joins.name(id);
        return n == null ? fallback : n;
    }

    /** @return the duration, or empty with an error sent. */
    public static OptionalLong duration(ServerCommandSource src, String text) {
        OptionalLong d = Durations.parse(text);
        if (d.isEmpty()) {
            Msg.err(src, "general.bad-duration");
        }
        return d;
    }
}
