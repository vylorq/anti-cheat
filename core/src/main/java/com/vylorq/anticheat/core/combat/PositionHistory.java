package com.vylorq.anticheat.core.combat;

import com.vylorq.anticheat.core.util.Box;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Recent positions of an entity, so hits can be checked against where the target was on the attacker's screen. */
public final class PositionHistory {
    public record Sample(long time, Vec3 pos, double width, double height) {
        public Box box() {
            return Box.around(pos, width, height);
        }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final long keepMillis;

    public PositionHistory(long keepMillis) {
        this.keepMillis = keepMillis;
    }

    public synchronized void add(long time, Vec3 pos, double width, double height) {
        samples.addLast(new Sample(time, pos, width, height));
        while (!samples.isEmpty() && samples.peekFirst().time() < time - keepMillis) {
            samples.pollFirst();
        }
    }

    /** Samples from {@code from} to {@code to} (inclusive), plus the one just before for interpolation. */
    public synchronized List<Sample> between(long from, long to) {
        List<Sample> out = new ArrayList<>();
        Sample before = null;
        for (Sample s : samples) {
            if (s.time() < from) {
                before = s;
            } else if (s.time() <= to) {
                out.add(s);
            }
        }
        if (before != null) {
            out.add(0, before);
        }
        if (out.isEmpty() && !samples.isEmpty()) {
            out.add(samples.peekLast());
        }
        return out;
    }

    public synchronized boolean isEmpty() {
        return samples.isEmpty();
    }
}
