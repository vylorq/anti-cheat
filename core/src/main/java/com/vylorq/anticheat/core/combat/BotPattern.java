package com.vylorq.anticheat.core.combat;

import com.vylorq.anticheat.core.util.Stats;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Mining bots (Baritone and similar): they turn the camera instantly onto the next block and hold it there, with
 * turns that have no mouse step at all. People flick too, but a mouse always moves in fixed steps.
 */
public final class BotPattern {
    private final Deque<Double> pitchSteps = new ArrayDeque<>();
    private final Deque<Long> snaps = new ArrayDeque<>();
    private long pendingSnap = -1;
    private int stillAfter;

    /**
     * Every movement packet. {@code dYaw}/{@code dPitch} are 0 when the camera didn't move.
     *
     * @param mining a block was broken in the last two seconds
     * @return true when the pattern is clear
     */
    public boolean onMove(long nowMs, double dYaw, double dPitch, boolean mining) {
        double dp = Math.abs(dPitch);
        if (dp > 0.01 && dp < 20) {
            pitchSteps.addLast(dp);
            if (pitchSteps.size() > 60) {
                pitchSteps.pollFirst();
            }
        }
        boolean turned = Math.abs(dYaw) > 0.0001 || dp > 0.0001;
        if (pendingSnap >= 0) {
            if (turned) {
                pendingSnap = -1;
            } else if (++stillAfter >= 3) {
                // Snapped and then held perfectly still: count it.
                snaps.addLast(pendingSnap);
                pendingSnap = -1;
            }
        }
        if (mining && (Math.abs(dYaw) > 30 || dp > 30)) {
            pendingSnap = nowMs;
            stillAfter = 0;
        }
        while (!snaps.isEmpty() && snaps.peekFirst() < nowMs - 600_000) {
            snaps.pollFirst();
        }
        if (snaps.size() >= 15 && noMouseStep()) {
            snaps.clear();
            return true;
        }
        return false;
    }

    private boolean noMouseStep() {
        if (pitchSteps.size() < 30) {
            return false;
        }
        double g = 0;
        int counted = 0;
        for (double d : pitchSteps) {
            g = counted == 0 ? d : Stats.gcd(g, d, 1.0E-4);
            counted++;
        }
        // The smallest real mouse step (lowest sensitivity) is about 0.0096 degrees.
        return g < 0.0075;
    }
}
