package com.vylorq.anticheat.util;

/** Measures TPS from the time between server ticks (smoothed). */
public final class Tps {
    private static long last;
    private static double avgMs = 50;
    private static long tick;

    private Tps() {
    }

    public static void onTick() {
        long now = System.nanoTime();
        if (last != 0) {
            double ms = (now - last) / 1_000_000.0;
            avgMs = avgMs * 0.95 + ms * 0.05;
        }
        last = now;
        tick++;
    }

    public static double tps() {
        return Math.min(20.0, 1000.0 / Math.max(1.0, avgMs));
    }

    public static double mspt() {
        return avgMs;
    }

    /** Server tick counter (starts when the mod loads). */
    public static long tick() {
        return tick;
    }
}
