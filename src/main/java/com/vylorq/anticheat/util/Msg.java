package com.vylorq.anticheat.util;

import com.vylorq.anticheat.Ac;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Chat helpers. Every player-facing string goes through the language file via {@link #tr}. */
public final class Msg {
    private Msg() {
    }

    /** Translated text from the language file, with § colour codes. */
    public static String tr(String key, Object... args) {
        return Ac.lang().get(key, args);
    }

    public static MutableText text(String legacy) {
        return Text.literal(legacy);
    }

    public static MutableText prefixed(String legacy) {
        return Text.literal(Ac.config().general.prefix + legacy);
    }

    public static void send(ServerPlayerEntity p, String key, Object... args) {
        p.sendMessage(prefixed(tr(key, args)));
    }

    public static void sendRaw(ServerPlayerEntity p, Text t) {
        p.sendMessage(t);
    }

    public static void actionBar(ServerPlayerEntity p, String legacy) {
        p.sendMessage(Text.literal(legacy), true);
    }

    public static void ok(ServerCommandSource src, String key, Object... args) {
        MutableText t = prefixed(tr(key, args));
        src.sendFeedback(() -> t, false);
    }

    public static void err(ServerCommandSource src, String key, Object... args) {
        src.sendError(Text.literal(tr(key, args)));
    }

    /** Clickable text that runs a command. */
    public static MutableText button(String label, String command, String hover) {
        return Text.literal(label).styled(s -> s
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    /** Clickable text that fills the chat box. */
    public static MutableText suggest(String label, String command, String hover) {
        return Text.literal(label).styled(s -> s
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hover))));
    }

    public static MutableText gray(String s) {
        return Text.literal(s).formatted(Formatting.GRAY);
    }

    /** Quotes a player name for use inside a command (Bedrock names can contain spaces). */
    public static String q(String name) {
        return name.contains(" ") || name.startsWith(".") || name.contains("*") ? "\"" + name.replace("\"", "") + "\"" : name;
    }
}
