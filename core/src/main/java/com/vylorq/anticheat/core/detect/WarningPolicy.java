package com.vylorq.anticheat.core.detect;

import com.vylorq.anticheat.core.util.Clock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-spam rules for the generic "suspicious activity" warning (section 6.3):
 * at most one per cooldown, at most N per session, never for watched / shadowed / exempt players.
 */
public final class WarningPolicy {
    private static final class State {
        long lastWarn = Long.MIN_VALUE / 2;
        int sessionCount;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public WarningPolicy(Clock clock) {
        this.clock = clock;
    }

    /** Resets the per-session counter. Call on join. */
    public void startSession(UUID id) {
        states.computeIfAbsent(id, k -> new State()).sessionCount = 0;
    }

    public boolean shouldWarn(UUID id, boolean enabled, boolean watched, boolean shadowed, boolean exempt,
                              long cooldownMillis, int maxPerSession) {
        if (!enabled || watched || shadowed || exempt) {
            return false;
        }
        State s = states.computeIfAbsent(id, k -> new State());
        long now = clock.nowMillis();
        if (s.sessionCount >= maxPerSession) {
            return false;
        }
        if (now - s.lastWarn < cooldownMillis) {
            // Flags close together are combined into the one warning already sent.
            return false;
        }
        s.lastWarn = now;
        s.sessionCount++;
        return true;
    }

    public int sessionCount(UUID id) {
        State s = states.get(id);
        return s == null ? 0 : s.sessionCount;
    }
}
