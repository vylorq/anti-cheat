package com.vylorq.anticheat.core.trade;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Pending /trade requests (expire after 30 seconds). */
public final class TradeRequests {
    private record Req(UUID from, long at) {
    }

    private final Map<UUID, Req> byTarget = new ConcurrentHashMap<>();

    public void request(UUID from, UUID to, long now) {
        byTarget.put(to, new Req(from, now));
    }

    /** @return true if a live request from {@code from} to {@code to} existed (it is consumed). */
    public boolean take(UUID to, UUID from, long now, long timeoutMillis) {
        Req r = byTarget.get(to);
        if (r == null || !r.from().equals(from) || now - r.at() > timeoutMillis) {
            return false;
        }
        byTarget.remove(to);
        return true;
    }

    public void clear(UUID player) {
        byTarget.remove(player);
        byTarget.values().removeIf(r -> r.from().equals(player));
    }
}
