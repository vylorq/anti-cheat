package com.vylorq.anticheat.core.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chat protection (section 9): spam and flood limits, repeated messages and advertising links. */
public final class ChatFilter {
    public record Settings(int maxPer10s, int minMillisBetween, int maxRepeats, int floodMaxLength, double maxCapsRatio,
                           boolean blockLinks, List<String> allowedDomains) {
    }

    public enum Verdict { OK, TOO_FAST, SPAM, REPEATED, FLOOD, CAPS, ADVERTISING }

    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b((?:https?://)?(?:[a-z0-9-]+(?:\\s*(?:\\.|\\(dot\\)|\\[dot\\])\\s*))+(?:com|net|org|gg|io|me|xyz|co|tk|ru|de|uk|us|info|club|fun|pw|to|cc|ly|sh|link|online|site|store|play|mc)\\b)"
                    + "|\\b\\d{1,3}(?:\\s*\\.\\s*\\d{1,3}){3}(?::\\d{2,5})?\\b");

    private static final class State {
        final Deque<Long> times = new ArrayDeque<>();
        String lastNormalized = "";
        int repeats;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public Verdict check(UUID player, String message, long now, Settings s) {
        State st = states.computeIfAbsent(player, k -> new State());
        synchronized (st) {
            if (!st.times.isEmpty() && now - st.times.peekLast() < s.minMillisBetween()) {
                return Verdict.TOO_FAST;
            }
            while (!st.times.isEmpty() && st.times.peekFirst() < now - 10_000) {
                st.times.pollFirst();
            }
            if (st.times.size() >= s.maxPer10s()) {
                return Verdict.SPAM;
            }
            if (message.length() > s.floodMaxLength() || hasCharFlood(message)) {
                return Verdict.FLOOD;
            }
            if (s.blockLinks() && containsAd(message, s.allowedDomains())) {
                return Verdict.ADVERTISING;
            }
            String norm = normalize(message);
            if (!norm.isEmpty() && norm.equals(st.lastNormalized)) {
                st.repeats++;
                if (st.repeats >= s.maxRepeats()) {
                    return Verdict.REPEATED;
                }
            } else {
                st.repeats = 0;
                st.lastNormalized = norm;
            }
            if (capsRatio(message) > s.maxCapsRatio()) {
                return Verdict.CAPS;
            }
            st.times.addLast(now);
            return Verdict.OK;
        }
    }

    static String normalize(String m) {
        return m.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static boolean hasCharFlood(String m) {
        int run = 1;
        for (int i = 1; i < m.length(); i++) {
            if (m.charAt(i) == m.charAt(i - 1) && !Character.isWhitespace(m.charAt(i))) {
                if (++run >= 12) {
                    return true;
                }
            } else {
                run = 1;
            }
        }
        return false;
    }

    static double capsRatio(String m) {
        int letters = 0;
        int upper = 0;
        for (char c : m.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters < 8 ? 0 : upper / (double) letters;
    }

    public static boolean containsAd(String m, List<String> allowed) {
        Matcher mt = LINK.matcher(m);
        while (mt.find()) {
            String found = mt.group().toLowerCase(Locale.ROOT).replaceAll("\\s|\\(dot\\)|\\[dot\\]", ".")
                    .replaceAll("\\.+", ".").replaceFirst("^https?://", "");
            boolean ok = false;
            for (String a : allowed) {
                if (found.equals(a) || found.endsWith("." + a) || found.startsWith(a + "/") || found.contains("." + a + "/")) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return true;
            }
        }
        return false;
    }

    public void forget(UUID player) {
        states.remove(player);
    }
}
