package com.vylorq.anticheat.core.extras;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Scheduled restarts with countdown warnings (section 15). */
public final class RestartScheduler {
    private RestartScheduler() {
    }

    /** Next restart time after {@code now} from "HH:mm" entries, or -1. */
    public static long next(List<String> times, long now, ZoneId zone) {
        ZonedDateTime n = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone);
        long best = -1;
        for (String t : times) {
            LocalTime lt;
            try {
                lt = LocalTime.parse(t.trim());
            } catch (Exception e) {
                continue;
            }
            ZonedDateTime cand = LocalDateTime.of(n.toLocalDate(), lt).atZone(zone);
            if (!cand.isAfter(n)) {
                cand = cand.plusDays(1);
            }
            long ms = cand.toInstant().toEpochMilli();
            if (best == -1 || ms < best) {
                best = ms;
            }
        }
        return best;
    }

    /** Minutes-before-restart warning due between two ticks, or -1. */
    public static int warningDue(long restartAt, long lastTick, long now, List<Integer> warnMinutes) {
        for (int m : warnMinutes) {
            long at = restartAt - m * 60_000L;
            if (lastTick < at && now >= at) {
                return m;
            }
        }
        return -1;
    }
}
