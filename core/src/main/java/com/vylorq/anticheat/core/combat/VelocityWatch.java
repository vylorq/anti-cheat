package com.vylorq.anticheat.core.combat;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Anti-knockback: a hit on the ground throws a player upwards. A real game always rises within its lag time
 * unless something stops it (a ceiling, water, a cobweb, a ladder...). Missing the rise on most hits, while
 * clearly moving around, is anti-knockback.
 */
public final class VelocityWatch {
    private boolean pending;
    private double expectedUp;
    private long deadline;
    private boolean rose;
    private boolean excused;
    private int moves;
    private final Deque<Boolean> results = new ArrayDeque<>();

    /** The server pushed this player with upward speed {@code vy} (blocks per tick). */
    public void onVelocity(long nowMs, double vy, int latencyMs, boolean bedrock, boolean excusedNow) {
        if (vy < 0.3) {
            return;
        }
        pending = true;
        expectedUp = vy;
        int lag = Math.max(0, Math.min(latencyMs, 1000));
        deadline = nowMs + Math.max(1000, Math.min(3000, lag * 2L + (bedrock ? 1400 : 800)));
        rose = false;
        excused = excusedNow;
        moves = 0;
    }

    /** Every movement packet while waiting. */
    public void onMove(double dy, boolean excusedNow) {
        if (!pending) {
            return;
        }
        moves++;
        if (excusedNow) {
            excused = true;
        }
        if (dy >= Math.min(0.15, expectedUp * 0.4)) {
            rose = true;
        }
    }

    /** Teleports, deaths and the like: the push no longer applies. */
    public void cancel() {
        pending = false;
    }

    public boolean pending() {
        return pending;
    }

    public enum Result { NONE, OK, MISSED, FLAG }

    /** Called regularly: once the deadline passes, the push is judged. */
    public Result check(long nowMs) {
        if (!pending || nowMs < deadline) {
            return Result.NONE;
        }
        pending = false;
        if (rose) {
            push(false);
            return Result.OK;
        }
        // A ceiling, water, ladder... or hardly any movement packets (lag): can't judge, so it doesn't count.
        if (excused || moves < 5) {
            return Result.NONE;
        }
        push(true);
        int missed = 0;
        for (boolean b : results) {
            if (b) {
                missed++;
            }
        }
        if (missed >= 3) {
            results.clear();
            return Result.FLAG;
        }
        return Result.MISSED;
    }

    private void push(boolean missed) {
        results.addLast(missed);
        if (results.size() > 5) {
            results.pollFirst();
        }
    }
}
