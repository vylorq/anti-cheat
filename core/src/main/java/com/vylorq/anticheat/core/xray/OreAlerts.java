package com.vylorq.anticheat.core.xray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Groups ore mining into one admin alert per player per window (section 9: "grouped, not spammed"). */
public final class OreAlerts {
    public record Alert(UUID player, String name, String ore, int count, long spanMillis) {
    }

    private static final class Group {
        String name;
        String ore;
        int count;
        long first;
        long last;
    }

    private final Map<String, Group> groups = new HashMap<>();

    public synchronized void onMine(UUID player, String name, String ore, long now) {
        String key = player + "|" + ore;
        Group g = groups.get(key);
        if (g == null) {
            g = new Group();
            g.name = name;
            g.ore = ore;
            g.first = now;
            groups.put(key, g);
        }
        g.count++;
        g.last = now;
    }

    /** Alerts for groups idle longer than the window. */
    public synchronized List<Alert> flush(long now, long windowMillis) {
        List<Alert> out = new ArrayList<>();
        groups.entrySet().removeIf(e -> {
            Group g = e.getValue();
            if (now - g.first >= windowMillis) {
                UUID id = UUID.fromString(e.getKey().substring(0, e.getKey().indexOf('|')));
                out.add(new Alert(id, g.name, g.ore, g.count, g.last - g.first));
                return true;
            }
            return false;
        });
        return out;
    }
}
