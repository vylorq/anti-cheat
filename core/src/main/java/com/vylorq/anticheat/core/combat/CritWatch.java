package com.vylorq.anticheat.core.combat;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fake criticals: a critical hit needs a real fall. Cheats fake it with tiny hops, so the hit counts as critical
 * while the player barely left the ground (fell under 0.2 blocks and is still within a quarter block of it).
 * Stepping off a snow layer can do that once; doing it on most critical hits is a cheat.
 */
public final class CritWatch {
    private final Deque<Boolean> recent = new ArrayDeque<>();

    /** A critical hit landed. @return true to flag */
    public boolean onCrit(double fallDistance, boolean groundWithinQuarter) {
        boolean fake = fallDistance < 0.2 && groundWithinQuarter;
        recent.addLast(fake);
        if (recent.size() > 6) {
            recent.pollFirst();
        }
        int n = 0;
        for (boolean b : recent) {
            if (b) {
                n++;
            }
        }
        if (fake && n >= 4) {
            recent.clear();
            return true;
        }
        return false;
    }
}
