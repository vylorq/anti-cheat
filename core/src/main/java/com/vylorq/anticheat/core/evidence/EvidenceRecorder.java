package com.vylorq.anticheat.core.evidence;

import com.vylorq.anticheat.core.util.Clock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a rolling buffer of each player's recent activity and turns it into {@link EvidenceClip}s when flagged.
 * Watched players keep a longer buffer and get clips more often.
 */
public final class EvidenceRecorder {
    private static final class Buffer {
        final Deque<EvidenceEvent> events = new ArrayDeque<>();
        long lastClip = Long.MIN_VALUE / 2;
    }

    private final Map<UUID, Buffer> buffers = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile long windowMillis;
    private volatile long watchedWindowMillis;
    private volatile long clipCooldownMillis;

    public EvidenceRecorder(Clock clock, int clipSeconds, int watchedClipSeconds, int cooldownSeconds) {
        this.clock = clock;
        configure(clipSeconds, watchedClipSeconds, cooldownSeconds);
    }

    public void configure(int clipSeconds, int watchedClipSeconds, int cooldownSeconds) {
        this.windowMillis = clipSeconds * 1000L;
        this.watchedWindowMillis = Math.max(clipSeconds, watchedClipSeconds) * 1000L;
        this.clipCooldownMillis = cooldownSeconds * 1000L;
    }

    public void record(UUID id, EvidenceEvent e) {
        Buffer b = buffers.computeIfAbsent(id, k -> new Buffer());
        synchronized (b) {
            b.events.addLast(e);
            long cutoff = clock.nowMillis() - watchedWindowMillis;
            while (!b.events.isEmpty() && b.events.peekFirst().time < cutoff) {
                b.events.pollFirst();
            }
            // Hard cap so a flood of events can't use unbounded memory.
            while (b.events.size() > 5000) {
                b.events.pollFirst();
            }
        }
    }

    public void record(UUID id, EvidenceEvent.Type type, double x, double y, double z, float yaw, float pitch, String text) {
        record(id, new EvidenceEvent(clock.nowMillis(), type, x, y, z, yaw, pitch, text));
    }

    /**
     * Captures a clip if the player hasn't had one recently.
     *
     * @return the clip, or null when on cooldown or nothing is buffered.
     */
    public EvidenceClip capture(UUID id, String name, String trigger, boolean watched) {
        return capture(id, name, trigger, watched, false);
    }

    /** @param force ignore the cooldown (used when a review case opens). */
    public EvidenceClip capture(UUID id, String name, String trigger, boolean watched, boolean force) {
        Buffer b = buffers.get(id);
        if (b == null) {
            return null;
        }
        long now = clock.nowMillis();
        synchronized (b) {
            long cooldown = watched ? clipCooldownMillis / 4 : clipCooldownMillis;
            if ((!force && now - b.lastClip < cooldown) || b.events.isEmpty()) {
                return null;
            }
            b.lastClip = now;
            long cutoff = now - (watched ? watchedWindowMillis : windowMillis);
            EvidenceClip clip = new EvidenceClip();
            clip.id = UUID.randomUUID().toString().substring(0, 8);
            clip.player = id;
            clip.playerName = name;
            clip.createdAt = now;
            clip.trigger = trigger;
            clip.events = new ArrayList<>();
            for (EvidenceEvent e : b.events) {
                if (e.time >= cutoff) {
                    clip.events.add(e);
                }
            }
            return clip;
        }
    }

    public void forget(UUID id) {
        buffers.remove(id);
    }

    public int bufferedCount(UUID id) {
        Buffer b = buffers.get(id);
        return b == null ? 0 : b.events.size();
    }
}
