package com.vylorq.anticheat.feature;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-admin toggle for flag alerts (/ac alerts). */
public final class Alerts {
    private static final Set<UUID> MUTED = ConcurrentHashMap.newKeySet();

    private Alerts() {
    }

    public static boolean enabled(UUID admin) {
        return !MUTED.contains(admin);
    }

    public static boolean toggle(UUID admin) {
        if (!MUTED.remove(admin)) {
            MUTED.add(admin);
            return false;
        }
        return true;
    }
}
