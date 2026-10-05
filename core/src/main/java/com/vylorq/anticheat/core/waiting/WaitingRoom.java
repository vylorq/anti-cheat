package com.vylorq.anticheat.core.waiting;

import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** New-player verification (section 25). */
public final class WaitingRoom {
    public static final String[] QUESTIONS = {
            "How did you join the server?",
            "What's your name?",
            "Who invited you?"
    };

    public enum Status { ANSWERING, PENDING, ACCEPTED, DENIED }

    public static final class Request {
        public UUID player;
        public String name;
        public boolean bedrock;
        public long joinedAt;
        public long submittedAt;
        public String ip;
        public Status status = Status.ANSWERING;
        public String[] answers = new String[QUESTIONS.length];
        public int step;
        public String decidedBy;
        public long decidedAt;
    }

    public static final class Data {
        public Location spawn;
        public Area area;
        public Set<UUID> accepted = new LinkedHashSet<>();
        public Map<UUID, Request> requests = new LinkedHashMap<>();
        public Map<UUID, Integer> denials = new LinkedHashMap<>();
    }

    private final Data data;
    private final Clock clock;

    public WaitingRoom(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public boolean isSet() {
        return data.spawn != null;
    }

    public synchronized boolean isAccepted(UUID id) {
        return data.accepted.contains(id);
    }

    /** Existing players when the feature is first switched on are accepted automatically by the caller. */
    public synchronized void accept(UUID id) {
        data.accepted.add(id);
    }

    /**
     * Staff sent an already accepted player back: they wait again, with a request ready for staff to accept (no
     * questions to answer).
     */
    public synchronized Request sendBack(UUID id, String name, boolean bedrock, String ip, String by) {
        data.accepted.remove(id);
        Request r = new Request();
        r.player = id;
        r.name = name;
        r.bedrock = bedrock;
        r.ip = ip;
        r.joinedAt = clock.nowMillis();
        r.submittedAt = r.joinedAt;
        r.status = Status.PENDING;
        java.util.Arrays.fill(r.answers, "(sent back by " + by + ")");
        r.step = QUESTIONS.length;
        data.requests.put(id, r);
        return r;
    }

    /** A new player who must wait: not accepted and not staff. */
    public synchronized boolean mustWait(UUID id, boolean staff, boolean enabled) {
        return enabled && isSet() && !staff && !data.accepted.contains(id);
    }

    /** Starts (or restarts) the three questions. */
    public synchronized Request start(UUID id, String name, boolean bedrock, String ip) {
        Request r = data.requests.get(id);
        if (r != null && r.status == Status.PENDING) {
            return r;
        }
        r = new Request();
        r.player = id;
        r.name = name;
        r.bedrock = bedrock;
        r.ip = ip;
        r.joinedAt = clock.nowMillis();
        data.requests.put(id, r);
        return r;
    }

    public synchronized Request request(UUID id) {
        return data.requests.get(id);
    }

    /** Stores a typed chat answer. @return the next question, or null when all are answered (request is sent). */
    public synchronized String answer(UUID id, String text) {
        Request r = data.requests.get(id);
        if (r == null || r.status != Status.ANSWERING) {
            return null;
        }
        String clean = text.length() > 120 ? text.substring(0, 120) : text;
        r.answers[r.step++] = clean.strip();
        if (r.step >= QUESTIONS.length) {
            r.status = Status.PENDING;
            r.submittedAt = clock.nowMillis();
            return null;
        }
        return QUESTIONS[r.step];
    }

    /** All answers at once (Bedrock form). */
    public synchronized void answerAll(UUID id, String[] answers) {
        Request r = data.requests.get(id);
        if (r == null) {
            return;
        }
        for (int i = 0; i < QUESTIONS.length; i++) {
            String a = i < answers.length && answers[i] != null ? answers[i] : "";
            r.answers[i] = a.length() > 120 ? a.substring(0, 120) : a.strip();
        }
        r.step = QUESTIONS.length;
        r.status = Status.PENDING;
        r.submittedAt = clock.nowMillis();
    }

    public synchronized List<Request> pending() {
        List<Request> out = new ArrayList<>();
        for (Request r : data.requests.values()) {
            if (r.status == Status.PENDING) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized Request decide(UUID id, boolean accept, String by) {
        Request r = data.requests.get(id);
        if (r == null || r.status != Status.PENDING) {
            return null;
        }
        r.status = accept ? Status.ACCEPTED : Status.DENIED;
        r.decidedBy = by;
        r.decidedAt = clock.nowMillis();
        if (accept) {
            data.accepted.add(id);
            data.requests.remove(id);
        } else {
            data.denials.merge(id, 1, Integer::sum);
        }
        return r;
    }

    /** Ban length for this player's latest denial, escalating 24h → 3d → 7d when enabled. */
    public synchronized long denyDuration(UUID id, boolean escalate, List<String> steps, long base) {
        int n = data.denials.getOrDefault(id, 1);
        if (!escalate || steps.isEmpty()) {
            return base;
        }
        String step = steps.get(Math.min(n, steps.size()) - 1);
        return Durations.parse(step).orElse(base);
    }

    /** After a denial ban ends the player comes back and may request again. */
    public synchronized void resetAfterBan(UUID id) {
        Request r = data.requests.get(id);
        if (r != null && r.status == Status.DENIED) {
            data.requests.remove(id);
        }
    }
}
