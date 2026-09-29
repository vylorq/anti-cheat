package com.vylorq.anticheat.core.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A player who passed the review threshold (section 6.2). The system never punishes; an admin decides. */
public final class ReviewCase {
    public enum Status { OPEN, ACTIONED, DISMISSED }

    public enum Decision { BAN, TEMPBAN, KICK, WARN, MUTE, JAIL, WATCH, SHADOW, DISMISS }

    public static final class DecisionRecord {
        public Decision decision;
        public String by;
        public long at;
        public String detail;
    }

    public long id;
    public UUID player;
    public String playerName;
    public boolean bedrock;
    public long createdAt;
    public long updatedAt;
    public Status status = Status.OPEN;
    public int suspicionAtOpen;
    public int suspicion;
    public Map<String, Integer> flagCounts = new LinkedHashMap<>();
    public Map<String, Double> points = new LinkedHashMap<>();
    public List<String> clipIds = new ArrayList<>();
    public List<Long> warnings = new ArrayList<>();
    public List<DecisionRecord> decisions = new ArrayList<>();

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    /** The checks responsible for most points, used to count dismissals per check. */
    public List<String> topChecks(int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(flagCounts.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, list.size()); i++) {
            out.add(list.get(i).getKey());
        }
        return out;
    }
}
