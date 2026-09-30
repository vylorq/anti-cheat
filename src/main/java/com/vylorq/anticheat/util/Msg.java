package com.vylorq.anticheat.util;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.ui.Sounds;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Viewer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Chat helpers (34.10). Every player-facing string comes from the language file via {@link #tr}, in the viewer's
 * own language. Mod messages start with "Vigil »" and a symbol for their type: ✔ success, ● info, ⚠ warning,
 * ✖ error. The Watcher never uses these (its messages have no prefix).
 */
public final class Msg {
    private Msg() {
    }

    public enum Type {
        SUCCESS(Theme.Sym.CHECK, Theme.GREEN, Sounds.Ui.SUCCESS),
        INFO(Theme.Sym.DOT, Theme.AQUA, null),
        WARNING(Theme.Sym.WARN, Theme.GOLD, Sounds.Ui.WARNING),
        ERROR(Theme.Sym.CROSS, Theme.RED, Sounds.Ui.ERROR);

        final Theme.Sym sym;
        final int color;
        final Sounds.Ui sound;

        Type(Theme.Sym sym, int color, Sounds.Ui sound) {
            this.sym = sym;
            this.color = color;
            this.sound = sound;
        }
    }

    /** Translated text in the current viewer's language, with § colour codes. */
    public static String tr(String key, Object... args) {
        return Ac.lang().get(Viewer.language(), key, args);
    }

    /** Translated text in a given player's language. */
    public static String trFor(ServerPlayerEntity p, String key, Object... args) {
        return Ac.lang().get(Viewer.language(p), key, args);
    }

    public static MutableText text(String legacy) {
        return Text.literal(legacy);
    }

    /** Guesses the type from the text's leading colour (how the language files were written). */
    static Type typeOf(String legacy) {
        String s = legacy.startsWith("§r") ? legacy.substring(2) : legacy;
        if (s.length() >= 2 && s.charAt(0) == '§') {
            return switch (Character.toLowerCase(s.charAt(1))) {
                case 'a', '2' -> Type.SUCCESS;
                case 'c', '4' -> Type.ERROR;
                case 'e', '6' -> Type.WARNING;
                default -> Type.INFO;
            };
        }
        return Type.INFO;
    }

    private static String stripLeadingColour(String legacy) {
        String s = legacy;
        while (s.length() >= 2 && s.charAt(0) == '§' && "0123456789abcdefr".indexOf(Character.toLowerCase(s.charAt(1))) >= 0) {
            s = s.substring(2);
        }
        return s;
    }

    /** "Vigil » ✔ text" (type guessed from the text's colour). */
    public static MutableText prefixed(String legacy) {
        return typed(typeOf(legacy), legacy);
    }

    public static MutableText typed(Type type, String legacy) {
        MutableText t = Text.empty();
        if (!Ac.running() || Ac.config().general.showPrefix) {
            t.append(Theme.prefix());
        }
        String sym = type.sym.sp();
        if (!sym.isEmpty()) {
            t.append(Theme.c(sym, type.color));
        }
        return t.append(Theme.c(stripLeadingColour(legacy), type == Type.INFO ? Theme.WHITE : type.color));
    }

    public static void send(ServerPlayerEntity p, String key, Object... args) {
        Viewer.with(p, () -> {
            String s = tr(key, args);
            Type type = typeOf(s);
            p.sendMessage(typed(type, s));
            if (type == Type.ERROR || type == Type.SUCCESS) {
                Sounds.play(p, type.sound);
            }
        });
    }

    public static void send(ServerPlayerEntity p, Type type, String key, Object... args) {
        Viewer.with(p, () -> {
            p.sendMessage(typed(type, tr(key, args)));
            if (type.sound != null) {
                Sounds.play(p, type.sound);
            }
        });
    }

    public static void success(ServerPlayerEntity p, String key, Object... args) {
        send(p, Type.SUCCESS, key, args);
    }

    public static void info(ServerPlayerEntity p, String key, Object... args) {
        send(p, Type.INFO, key, args);
    }

    public static void warn(ServerPlayerEntity p, String key, Object... args) {
        send(p, Type.WARNING, key, args);
    }

    public static void error(ServerPlayerEntity p, String key, Object... args) {
        send(p, Type.ERROR, key, args);
    }

    public static void sendRaw(ServerPlayerEntity p, Text t) {
        p.sendMessage(t);
    }

    public static void actionBar(ServerPlayerEntity p, String legacy) {
        p.sendMessage(Text.literal(legacy), true);
    }

    /** Command feedback (type from the text's colour). */
    public static void ok(ServerCommandSource src, String key, Object... args) {
        ServerPlayerEntity p = src.getPlayer();
        Viewer.with(p, () -> {
            String s = tr(key, args);
            Type type = typeOf(s);
            MutableText t = typed(type, s);
            src.sendFeedback(() -> t, false);
            if (type == Type.ERROR || type == Type.SUCCESS) {
                Sounds.play(p, type.sound);
            }
        });
    }

    public static void err(ServerCommandSource src, String key, Object... args) {
        ServerPlayerEntity p = src.getPlayer();
        Viewer.with(p, () -> {
            MutableText t = typed(Type.ERROR, tr(key, args));
            src.sendFeedback(() -> t, false);
            Sounds.play(p, Sounds.Ui.ERROR);
        });
    }

    /** Clickable [button] that runs a command, with a hover explaining it. */
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
