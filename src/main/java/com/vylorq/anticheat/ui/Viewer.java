package com.vylorq.anticheat.ui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.platform.Floodgate;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.Supplier;

/**
 * Who a menu or message is being built for, so text comes out in their language and symbols suit their client
 * (34.12, 34.14). Everything runs on the server thread, so a thread-local is enough.
 */
public final class Viewer {
    private static final ThreadLocal<ServerPlayerEntity> CURRENT = new ThreadLocal<>();

    private Viewer() {
    }

    public static ServerPlayerEntity current() {
        return CURRENT.get();
    }

    /** Runs something with {@code p} as the viewer (null keeps the server defaults). */
    public static void with(ServerPlayerEntity p, Runnable r) {
        ServerPlayerEntity before = CURRENT.get();
        CURRENT.set(p);
        try {
            r.run();
        } finally {
            CURRENT.set(before);
        }
    }

    public static <T> T with(ServerPlayerEntity p, Supplier<T> s) {
        ServerPlayerEntity before = CURRENT.get();
        CURRENT.set(p);
        try {
            return s.get();
        } finally {
            CURRENT.set(before);
        }
    }

    public static boolean bedrock() {
        return isBedrock(CURRENT.get());
    }

    public static boolean isBedrock(ServerPlayerEntity p) {
        if (p == null || !Ac.running()) {
            return false;
        }
        var s = Ac.sessionOrNull(p.getUuid());
        return s != null ? s.bedrock : Floodgate.isBedrock(p.getUuid());
    }

    /** Language for the current viewer (server default when there's none). */
    public static String language() {
        return language(CURRENT.get());
    }

    /**
     * A player's language: what they picked with /language, otherwise their game language when Vigil has it.
     * Bedrock clients can't shape Arabic letters in item names and chat, so they get English unless they choose
     * Arabic themselves.
     */
    public static String language(ServerPlayerEntity p) {
        if (!Ac.running()) {
            return "en_us";
        }
        var lang = Ac.lang();
        if (p == null) {
            return lang.defaultLanguage();
        }
        String chosen = Ac.get().misc.languages.get(p.getUuid());
        if (chosen != null && !chosen.equals("auto") && lang.supports(chosen)) {
            return chosen;
        }
        if (isBedrock(p)) {
            return "en_us";
        }
        String client = clientLanguage(p);
        if (client != null && lang.supports(client)) {
            return client;
        }
        if (client != null && client.startsWith("ar_") && lang.supports("ar_sa")) {
            return "ar_sa";
        }
        return lang.defaultLanguage();
    }

    private static String clientLanguage(ServerPlayerEntity p) {
        try {
            String l = p.getClientOptions().language();
            return l == null ? null : l.toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
