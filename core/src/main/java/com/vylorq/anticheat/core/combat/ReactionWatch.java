package com.vylorq.anticheat.core.combat;

/**
 * Chest stealer: the first item taken out of a container, measured from when the player's game answered a ping
 * sent together with the window. A person needs time to see the items and move the mouse; a stealer takes items
 * the moment the window exists. Same timing idea as {@link TotemWatch}.
 */
public final class ReactionWatch {
    public static final long HUMAN_MIN_NANOS = 80_000_000L;

    private long openedAt;
    private int pingId;
    private long pongAt;
    private boolean watching;
    private final boolean[] recent = new boolean[5];
    private int count;

    public synchronized void onOpen(long now, int pingId) {
        this.openedAt = now;
        this.pingId = pingId;
        this.pongAt = 0;
        this.watching = true;
    }

    public synchronized void onPong(long now, int id) {
        if (watching && id == pingId && pongAt == 0) {
            pongAt = now;
        }
    }

    public synchronized void onClose() {
        watching = false;
    }

    /**
     * The first item was taken out. @return how many of the last five windows were emptied too fast to be
     * human, or -1 when nothing was being watched
     */
    public synchronized int onFirstTake(long arrivedAt, int latencyMs) {
        if (!watching) {
            return -1;
        }
        watching = false;
        long seen = pongAt != 0 ? pongAt : openedAt + latencyMs * 1_000_000L;
        boolean fast = arrivedAt - seen < HUMAN_MIN_NANOS;
        recent[count % recent.length] = fast;
        count++;
        int n = 0;
        for (int i = 0; i < Math.min(count, recent.length); i++) {
            if (recent[i]) {
                n++;
            }
        }
        return fast ? n : 0;
    }
}
