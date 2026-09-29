package com.vylorq.anticheat.core.stats;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Anti-cheat statistics (section 28): flags per check per day, most-flagged players, dismissals per check. */
public final class AcStats {
    public static final class Data {
        /** day (yyyy-mm-dd) -> check -> count */
        public TreeMap<String, Map<String, Integer>> flagsPerDay = new TreeMap<>();
        public Map<String, Integer> dismissalsPerCheck = new LinkedHashMap<>();
        public Map<String, Integer> actionedPerCheck = new LinkedHashMap<>();
        public Map<UUID, Integer> flagsPerPlayer = new LinkedHashMap<>();
        public Map<UUID, String> names = new LinkedHashMap<>();
        public int caughtCount;
    }

    private final Data data;

    public AcStats(Data data) {
        this.data = data == null ? new Data() : data;
        if (this.data.flagsPerDay == null) {
            this.data.flagsPerDay = new TreeMap<>();
        }
    }

    public Data data() {
        return data;
    }

    public static String day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    public synchronized void recordFlag(UUID player, String name, String check, long now) {
        data.flagsPerDay.computeIfAbsent(day(now), k -> new LinkedHashMap<>()).merge(check, 1, Integer::sum);
        data.flagsPerPlayer.merge(player, 1, Integer::sum);
        data.names.put(player, name);
        // keep ~60 days
        while (data.flagsPerDay.size() > 60) {
            String first = data.flagsPerDay.firstKey();
            data.flagsPerDay.remove(first);
        }
    }

    public synchronized void recordDismissal(List<String> checks) {
        for (String c : checks) {
            data.dismissalsPerCheck.merge(c, 1, Integer::sum);
        }
    }

    public synchronized void recordActioned(List<String> checks) {
        for (String c : checks) {
            data.actionedPerCheck.merge(c, 1, Integer::sum);
        }
    }

    public synchronized int incrementCaught() {
        return ++data.caughtCount;
    }

    public synchronized int caught() {
        return data.caughtCount;
    }

    /** Fraction of decided cases for a check that were dismissed. High = likely false flags, tune it. */
    public synchronized double dismissalRate(String check) {
        int d = data.dismissalsPerCheck.getOrDefault(check, 0);
        int a = data.actionedPerCheck.getOrDefault(check, 0);
        return d + a == 0 ? 0 : d / (double) (d + a);
    }

    /** Checks sorted by dismissal count, most first. */
    public synchronized List<Map.Entry<String, Integer>> mostDismissed() {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(data.dismissalsPerCheck.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        return list;
    }

    public synchronized List<Map.Entry<UUID, Integer>> mostFlagged(int n) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(data.flagsPerPlayer.entrySet());
        list.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
        return list.subList(0, Math.min(n, list.size()));
    }

    public synchronized Map<String, Integer> flagsOn(String day) {
        return new LinkedHashMap<>(data.flagsPerDay.getOrDefault(day, Map.of()));
    }

    public synchronized String nameOf(UUID id) {
        return data.names.getOrDefault(id, id.toString());
    }

}
