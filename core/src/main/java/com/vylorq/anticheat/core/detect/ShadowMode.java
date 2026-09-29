package com.vylorq.anticheat.core.detect;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shadow mode (section 6.4): hits deal no damage to other players and block changes are hidden from others.
 * The player is never told.
 */
public final class ShadowMode {
    public static final class Data {
        public Set<UUID> players = new HashSet<>();
    }

    private final Data data;

    public ShadowMode(Data data) {
        this.data = data == null ? new Data() : data;
    }

    public Data data() {
        return data;
    }

    public synchronized boolean isShadowed(UUID id) {
        return data.players.contains(id);
    }

    /** @return the new state. */
    public synchronized boolean toggle(UUID id) {
        if (!data.players.remove(id)) {
            data.players.add(id);
            return true;
        }
        return false;
    }

    public synchronized void set(UUID id, boolean on) {
        if (on) {
            data.players.add(id);
        } else {
            data.players.remove(id);
        }
    }
}
