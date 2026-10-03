package com.vylorq.anticheat.core.packets;

/**
 * Packets per second for one connection. A normal client sends a few dozen a second (even with inventory mods,
 * a few hundred); crash and lag exploits send thousands.
 */
public final class PacketRate {
    private long windowStart;
    private int count;

    /** Counts one packet. @return true when this second's packets went over {@code limit} */
    public synchronized boolean add(long nowMillis, int limit) {
        if (nowMillis - windowStart >= 1000) {
            windowStart = nowMillis;
            count = 0;
        }
        count++;
        return limit > 0 && count > limit;
    }

    public synchronized int current() {
        return count;
    }
}
