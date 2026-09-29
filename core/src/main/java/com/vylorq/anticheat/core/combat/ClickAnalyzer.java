package com.vylorq.anticheat.core.combat;

import com.vylorq.anticheat.core.util.Stats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Strict autoclicker detection (section 8.2). Measures clicks (arm swings / attacks) and flags:
 * sustained CPS above the limit, intervals that are too consistent, missing natural randomness
 * (kurtosis/distinct values), repeating patterns, sudden switches to perfectly steady clicking,
 * and impossible double clicks in the same tick.
 *
 * <p>Jitter and butterfly clicking are fast but irregular (high variation, many duplicate-tick pairs
 * spread randomly), which is how they are told apart from macros.
 */
public final class ClickAnalyzer {
    public record Settings(int maxCps, double minCv, double strictness) {
    }

    public record Finding(String reason, double points) {
    }

    private final Deque<Long> clicks = new ArrayDeque<>();
    private final Deque<Double> intervals = new ArrayDeque<>();
    private final Deque<Double> cvHistory = new ArrayDeque<>();
    private long lastClickTick = -1;
    private int sameTickClicks;
    private int clicksThisTick;
    private int tickCount;
    private static final int INTERVAL_WINDOW = 50;

    /**
     * @param timeMillis wall time of the click
     * @param serverTick server tick the click arrived in
     * @return findings (empty when normal)
     */
    public List<Finding> onClick(long timeMillis, long serverTick, Settings s) {
        List<Finding> out = new ArrayList<>();
        if (!clicks.isEmpty()) {
            double interval = timeMillis - clicks.peekLast();
            if (interval < 1000) {
                intervals.addLast(interval);
                if (intervals.size() > INTERVAL_WINDOW) {
                    intervals.pollFirst();
                }
            } else {
                // A pause: new burst, but keep the interval history for pattern analysis.
                cvHistory.clear();
            }
        }
        clicks.addLast(timeMillis);
        while (!clicks.isEmpty() && clicks.peekFirst() < timeMillis - 1000) {
            clicks.pollFirst();
        }

        // Two clicks in one tick happen with butterfly clicking (two fingers). Three or more, repeatedly, don't.
        if (serverTick == lastClickTick) {
            clicksThisTick++;
            if (clicksThisTick == 3) {
                sameTickClicks++;
            }
        } else {
            clicksThisTick = 1;
        }
        lastClickTick = serverTick;
        tickCount++;
        if (tickCount >= 40) {
            if (sameTickClicks >= 3) {
                out.add(new Finding("3+ clicks in one tick x" + sameTickClicks, 1.0 * s.strictness()));
            }
            sameTickClicks = 0;
            tickCount = 0;
        }

        int cps = clicks.size();
        if (cps > s.maxCps()) {
            out.add(new Finding("cps " + cps, (0.5 + (cps - s.maxCps()) * 0.2) * s.strictness()));
        }

        if (intervals.size() >= 20 && cps >= 8) {
            double[] iv = Stats.toArray(intervals);
            double cv = Stats.cv(iv);
            double kurt = Stats.kurtosis(iv);
            double distinct = Stats.distinctRatio(iv, 0);
            if (cv < s.minCv()) {
                out.add(new Finding(String.format("consistency cv=%.3f", cv), 1.0 * s.strictness()));
            } else if (distinct < 0.15 && cps >= 10) {
                out.add(new Finding(String.format("few distinct intervals %.2f", distinct), 0.8 * s.strictness()));
            } else if (kurt < -1.1 && cps >= 10 && narrowRange(iv)) {
                // Uniform random delays in a narrow band (a common "randomized" clicker) have kurtosis near -1.2.
                // Butterfly clicking is also flat/bimodal but spread over a wide range, so it isn't matched.
                out.add(new Finding(String.format("uniform randomness k=%.2f", kurt), 0.5 * s.strictness()));
            }
            if (hasRepeatingPattern(iv)) {
                out.add(new Finding("repeating pattern", 0.8 * s.strictness()));
            }
            // Sudden change: was humanly varied, now perfectly steady.
            cvHistory.addLast(cv);
            if (cvHistory.size() > 10) {
                cvHistory.pollFirst();
            }
            if (cvHistory.size() == 10) {
                double first = cvHistory.peekFirst();
                if (first > 0.25 && cv < s.minCv() * 1.5) {
                    out.add(new Finding(String.format("switched to steady clicking %.2f -> %.3f", first, cv),
                            0.8 * s.strictness()));
                }
            }
        }
        return out;
    }

    public int cps() {
        return clicks.size();
    }

    private static boolean narrowRange(double[] iv) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double d : iv) {
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        double mean = Stats.mean(iv);
        return mean > 0 && (max - min) / mean < 0.8;
    }

    /** Detects an interval sequence that repeats with a short period (2..8). */
    static boolean hasRepeatingPattern(double[] iv) {
        int n = iv.length;
        if (n < 24) {
            return false;
        }
        for (int period = 2; period <= 8; period++) {
            int matches = 0;
            int total = 0;
            for (int i = period; i < n; i++) {
                total++;
                if (Math.abs(iv[i] - iv[i - period]) <= 1.0) {
                    matches++;
                }
            }
            // Must not simply be constant (that is the consistency check's job).
            if (total > 0 && matches / (double) total > 0.9 && Stats.cv(iv) > 0.1) {
                return true;
            }
        }
        return false;
    }
}
