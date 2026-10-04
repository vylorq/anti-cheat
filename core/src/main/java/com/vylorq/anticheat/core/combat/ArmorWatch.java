package com.vylorq.anticheat.core.combat;

/**
 * Auto-armor: putting on three or more armor pieces within a tenth of a second. By hand, each piece needs the
 * mouse moved to it and clicked.
 */
public final class ArmorWatch {
    private final long[] equips = new long[4];
    private int n;
    private long lastBurst = Long.MIN_VALUE / 2;
    private int bursts;

    /** An armor slot went from empty to filled by a click that arrived at {@code at} (nanoseconds). @return true to flag */
    public boolean onEquip(long at) {
        equips[n % equips.length] = at;
        n++;
        int close = 0;
        for (int i = 0; i < Math.min(n, equips.length); i++) {
            if (at - equips[i] <= 100_000_000L) {
                close++;
            }
        }
        if (close < 3) {
            return false;
        }
        n = 0;
        // Twice within ten minutes.
        bursts = at - lastBurst < 600_000_000_000L ? bursts + 1 : 1;
        lastBurst = at;
        return bursts >= 2;
    }
}
