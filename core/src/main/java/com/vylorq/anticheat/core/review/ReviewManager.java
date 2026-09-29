package com.vylorq.anticheat.core.review;

import com.vylorq.anticheat.core.util.Clock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Owns every review case. */
public final class ReviewManager {
    public static final class Data {
        public long nextId = 1;
        public Map<Long, ReviewCase> cases = new TreeMap<>();
    }

    private final Data data;
    private final Clock clock;

    public ReviewManager(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public synchronized ReviewCase openCaseFor(UUID player) {
        for (ReviewCase c : data.cases.values()) {
            if (c.player.equals(player) && c.isOpen()) {
                return c;
            }
        }
        return null;
    }

    public synchronized ReviewCase create(UUID player, String name, boolean bedrock, int suspicion) {
        ReviewCase c = new ReviewCase();
        c.id = data.nextId++;
        c.player = player;
        c.playerName = name;
        c.bedrock = bedrock;
        c.createdAt = clock.nowMillis();
        c.updatedAt = c.createdAt;
        c.suspicionAtOpen = suspicion;
        c.suspicion = suspicion;
        data.cases.put(c.id, c);
        return c;
    }

    public synchronized ReviewCase get(long id) {
        return data.cases.get(id);
    }

    public synchronized List<ReviewCase> open() {
        List<ReviewCase> out = new ArrayList<>();
        for (ReviewCase c : data.cases.values()) {
            if (c.isOpen()) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparingInt((ReviewCase c) -> c.suspicion).reversed());
        return out;
    }

    public synchronized List<ReviewCase> forPlayer(UUID player) {
        List<ReviewCase> out = new ArrayList<>();
        for (ReviewCase c : data.cases.values()) {
            if (c.player.equals(player)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Records an admin decision. DISMISS marks a false flag; the punishments close the case;
     * WATCH and SHADOW keep it open so the investigation can continue.
     */
    public synchronized ReviewCase decide(long id, ReviewCase.Decision decision, String by, String detail) {
        ReviewCase c = data.cases.get(id);
        if (c == null) {
            return null;
        }
        ReviewCase.DecisionRecord r = new ReviewCase.DecisionRecord();
        r.decision = decision;
        r.by = by;
        r.at = clock.nowMillis();
        r.detail = detail;
        c.decisions.add(r);
        c.updatedAt = r.at;
        switch (decision) {
            case DISMISS -> c.status = ReviewCase.Status.DISMISSED;
            case WATCH, SHADOW -> {
                // investigation continues
            }
            default -> c.status = ReviewCase.Status.ACTIONED;
        }
        return c;
    }

    public synchronized int openCount() {
        int n = 0;
        for (ReviewCase c : data.cases.values()) {
            if (c.isOpen()) {
                n++;
            }
        }
        return n;
    }

    /** Clip ids referenced by open or actioned (ban) cases; these must never be purged. */
    public synchronized List<String> protectedClipIds() {
        List<String> out = new ArrayList<>();
        for (ReviewCase c : data.cases.values()) {
            if (c.status != ReviewCase.Status.DISMISSED) {
                out.addAll(c.clipIds);
            }
        }
        return out;
    }
}
