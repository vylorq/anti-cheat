package com.vylorq.anticheat.core.util;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats the durations used by commands, e.g. {@code 30m}, {@code 2h}, {@code 1d12h}, {@code permanent}.
 */
public final class Durations {
    /** Returned by {@link #parse} for "permanent". */
    public static final long PERMANENT = -1L;

    public static final long SECOND = 1000L;
    public static final long MINUTE = 60 * SECOND;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;
    public static final long WEEK = 7 * DAY;

    private static final Pattern PART = Pattern.compile("(\\d+)(s|m|h|d|w)");

    private Durations() {
    }

    /**
     * @return the duration in millis, {@link #PERMANENT} for permanent, or empty if the text is not a duration.
     */
    public static OptionalLong parse(String text) {
        if (text == null) {
            return OptionalLong.empty();
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.equals("permanent") || s.equals("perm") || s.equals("forever")) {
            return OptionalLong.of(PERMANENT);
        }
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return OptionalLong.empty();
            }
            end = m.end();
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
            long unit = switch (m.group(2)) {
                case "s" -> SECOND;
                case "m" -> MINUTE;
                case "h" -> HOUR;
                case "d" -> DAY;
                default -> WEEK;
            };
            if (n > Long.MAX_VALUE / unit / 2) {
                return OptionalLong.empty();
            }
            total += n * unit;
        }
        if (end != s.length() || total <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total);
    }

    public static boolean isDuration(String text) {
        return parse(text).isPresent();
    }

    /** Formats like {@code 24h 0m}, {@code 3d 4h}, {@code 12m 5s}; "permanent" for {@link #PERMANENT}. */
    public static String format(long millis) {
        if (millis == PERMANENT) {
            return "permanent";
        }
        if (millis < 0) {
            millis = 0;
        }
        long d = millis / DAY;
        long h = (millis % DAY) / HOUR;
        long m = (millis % HOUR) / MINUTE;
        long s = (millis % MINUTE) / SECOND;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    /** Formats the time between now and an expiry (never negative). */
    public static String formatRemaining(long expiresAt, long now) {
        if (expiresAt == PERMANENT) {
            return "permanent";
        }
        return format(Math.max(0, expiresAt - now));
    }

    /** @return {@code now + duration}, or {@link #PERMANENT} if duration is permanent. */
    public static long expiryFrom(long now, long duration) {
        return duration == PERMANENT ? PERMANENT : now + duration;
    }

    public static boolean isExpired(long expiresAt, long now) {
        return expiresAt != PERMANENT && now >= expiresAt;
    }
}
