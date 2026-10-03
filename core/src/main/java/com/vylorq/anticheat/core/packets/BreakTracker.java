package com.vylorq.anticheat.core.packets;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fast break: the game finishes a block break only once enough mining time has passed. A client that says
 * "done" too early is overruled and the block stays. Lag can make that happen now and then; a fast-break cheat
 * makes it happen on most blocks.
 */
public final class BreakTracker {
    private final Deque<Boolean> recent = new ArrayDeque<>();
    private static final int WINDOW = 20;

    /** @param early the client finished this block before it could have; @return true when the pattern is clear */
    public boolean onFinish(boolean early) {
        recent.addLast(early);
        if (recent.size() > WINDOW) {
            recent.pollFirst();
        }
        int n = 0;
        for (boolean b : recent) {
            if (b) {
                n++;
            }
        }
        if (n >= 8 && n >= recent.size() * 0.6) {
            recent.clear();
            return true;
        }
        return false;
    }
}
