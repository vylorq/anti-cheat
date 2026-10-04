package com.vylorq.anticheat.core.packets;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Nuker: every block that isn't instantly broken needs a minimum number of game ticks of mining. Added up over the
 * last ten seconds, that can't be more than the time that actually passed (with room for lag bunching). Works the
 * same for Java and Bedrock, since it only looks at which blocks broke and when.
 */
public final class MiningTime {
    private record Break(long time, int ticks) {
    }

    private final Deque<Break> recent = new ArrayDeque<>();
    private static final long WINDOW_MS = 10_000;

    /**
     * @param delta the block's breaking progress per tick for this player and tool (1 or more = instant)
     * @return the ticks of mining needed in the window when that's impossible, else 0
     */
    public int onBreak(long nowMs, float delta) {
        if (delta >= 1.0f || delta <= 0f) {
            return 0;
        }
        recent.addLast(new Break(nowMs, (int) Math.ceil(1.0 / delta)));
        while (!recent.isEmpty() && recent.peekFirst().time() < nowMs - WINDOW_MS) {
            recent.pollFirst();
        }
        int needed = 0;
        for (Break b : recent) {
            needed += b.ticks();
        }
        int allowed = (int) (WINDOW_MS / 50 * 1.6) + 10;
        if (needed > allowed) {
            recent.clear();
            return needed;
        }
        return 0;
    }
}
