package com.vylorq.anticheat.core.combat;

/**
 * Auto-totem: after a totem of undying pops, how quickly does a new one appear in the offhand?
 *
 * <p>Right after the pop the server also sends a ping. The game answers it the moment it handles the pop, so
 * the answer's arrival marks "the player's game has seen the pop", with this moment's lag already included.
 * Everything after that is the player's own reaction. A person needs well over 150 ms to notice, open the
 * inventory and move a totem (usually 400 ms or more); an auto-totem does it in the next game tick.
 *
 * <p>All times are in nanoseconds on the server's clock, taken when packets reach the network thread.
 */
public final class TotemWatch {
    /** Refills faster than this after the game saw the pop can't be done by hand. */
    public static final long HUMAN_MIN_NANOS = 150_000_000L;
    /** How long after a pop a refill still counts. */
    public static final long WINDOW_NANOS = 3_000_000_000L;

    private long popAt;
    private int pingId;
    private long pongAt;
    private boolean watching;
    private final long[] fast = new long[4];
    private int fastCount;

    /** A totem just popped; {@code pingId} is the ping sent with it. */
    public synchronized void onPop(long now, int pingId) {
        this.popAt = now;
        this.pingId = pingId;
        this.pongAt = 0;
        this.watching = true;
    }

    /** The answer to a ping arrived. */
    public synchronized void onPong(long now, int id) {
        if (watching && id == pingId && pongAt == 0) {
            pongAt = now;
        }
    }

    public synchronized boolean watching(long now) {
        if (watching && now - popAt > WINDOW_NANOS) {
            watching = false;
        }
        return watching;
    }

    public record Verdict(long reactionMs, boolean fast, int fastInRow) {
    }

    /**
     * A totem appeared in the offhand because of a packet that arrived at {@code arrivedAt}.
     *
     * @param latencyMs the game's own ping estimate, only used if the ping answer never came
     * @return null when no pop is being watched
     */
    public synchronized Verdict onRefill(long arrivedAt, int latencyMs) {
        if (!watching(arrivedAt)) {
            return null;
        }
        watching = false;
        long seen;
        if (pongAt != 0) {
            seen = pongAt;
        } else {
            // No answer yet: the refill came before the game even answered the ping that was sent with the pop.
            // Fall back to the ping estimate, which only ever makes this more lenient (it's an average).
            seen = popAt + latencyMs * 1_000_000L;
        }
        long reaction = arrivedAt - seen;
        boolean isFast = reaction < HUMAN_MIN_NANOS;
        if (isFast) {
            fast[fastCount % fast.length] = arrivedAt;
            fastCount++;
        }
        // How many of the recent fast refills fall within the last 30 minutes.
        int recent = 0;
        for (int i = 0; i < Math.min(fastCount, fast.length); i++) {
            if (arrivedAt - fast[i] < 30L * 60 * 1_000_000_000L) {
                recent++;
            }
        }
        return new Verdict(reaction / 1_000_000L, isFast, isFast ? recent : 0);
    }
}
