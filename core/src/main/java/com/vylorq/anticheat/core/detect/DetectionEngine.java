package com.vylorq.anticheat.core.detect;

import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.evidence.EvidenceClip;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.evidence.EvidenceRecorder;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.core.review.ReviewManager;
import com.vylorq.anticheat.core.stats.AcStats;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Central flag pipeline (section 6). Checks call {@link #flag}; the engine applies exempt/watch rules,
 * adds violation points, captures evidence, opens review cases, and decides on warnings and alerts.
 * It never kicks, bans or mutes: those are left to admins.
 */
public final class DetectionEngine {
    /** Implemented by the Minecraft layer to carry out side effects. */
    public interface Listener {
        default void onFlag(Flag flag) {
        }

        default void onAlert(Flag flag, boolean instant) {
        }

        default void onWarn(UUID player) {
        }

        default void onCaseOpened(ReviewCase c) {
        }

        default void onCaseUpdated(ReviewCase c) {
        }

        default void onAutoWatch(UUID player, String name, String reason) {
        }

        default void onClip(EvidenceClip clip) {
        }
    }

    public enum Outcome { IGNORED, SILENT, RECORDED }

    public record Flag(UUID player, String name, CheckType check, double points, double checkPoints,
                       int suspicion, String detail, boolean watched, boolean exempt, long time) {
    }

    private final Supplier<AcConfig> config;
    private final Clock clock;
    private final ViolationTracker violations;
    private final WarningPolicy warnings;
    private final Watchlist watchlist;
    private final ExemptList exempt;
    private final ShadowMode shadow;
    private final ReviewManager reviews;
    private final EvidenceRecorder evidence;
    private final AcStats stats;
    private Listener listener = new Listener() {
    };
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();

    public DetectionEngine(Supplier<AcConfig> config, Clock clock, ViolationTracker violations, WarningPolicy warnings,
                           Watchlist watchlist, ExemptList exempt, ShadowMode shadow, ReviewManager reviews,
                           EvidenceRecorder evidence, AcStats stats) {
        this.config = config;
        this.clock = clock;
        this.violations = violations;
        this.warnings = warnings;
        this.watchlist = watchlist;
        this.exempt = exempt;
        this.shadow = shadow;
        this.reviews = reviews;
        this.evidence = evidence;
        this.stats = stats;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public ViolationTracker violations() {
        return violations;
    }

    public Outcome flag(UUID player, String name, CheckType check, double basePoints, String detail, boolean bedrock) {
        AcConfig cfg = config.get();
        double sensitivity = cfg.sensitivity(check.id());
        if (sensitivity <= 0 || basePoints <= 0) {
            return Outcome.IGNORED;
        }
        long now = clock.nowMillis();
        boolean isExempt = exempt.isExempt(player);
        if (isExempt && !cfg.exempt.recordFlags) {
            return Outcome.IGNORED;
        }
        boolean watched = watchlist.isWatched(player);
        double points = basePoints * sensitivity * (watched ? cfg.watchlist.pointMultiplier : 1.0);
        double checkPoints = violations.add(player, check, points);
        int suspicion = violations.suspicion(player);
        stats.recordFlag(player, name, check.id(), now);
        evidence.record(player, new EvidenceEvent(now, EvidenceEvent.Type.FLAG, 0, 0, 0, 0, 0,
                "flagged " + check.displayName() + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")")));

        Flag flag = new Flag(player, name, check, points, checkPoints, suspicion, detail, watched, isExempt, now);
        listener.onFlag(flag);

        if (isExempt) {
            // Recorded silently: no case, warning, alert or auto-watch for exempt players.
            return Outcome.SILENT;
        }

        EvidenceClip clip = evidence.capture(player, name, check.displayName(), watched);

        // Auto-watch.
        if (cfg.detection.autoWatchEnabled && !watched) {
            int recent = violations.recentFlagCount(player, cfg.detection.autoWatchWindowMinutes * Durations.MINUTE);
            if (suspicion >= cfg.detection.autoWatchScore || recent >= cfg.detection.autoWatchFlagCount) {
                String reason = "Auto: suspicion " + suspicion + ", " + recent + " recent flags";
                watchlist.add(player, name, reason, "system", Durations.PERMANENT, true);
                watched = true;
                listener.onAutoWatch(player, name, reason);
            }
        }

        // Review case.
        int threshold = watched ? cfg.watchlist.reviewThreshold : cfg.detection.reviewThreshold;
        ReviewCase open = reviews.openCaseFor(player);
        if (open != null) {
            updateCase(open, player, suspicion, clip, now);
            listener.onCaseUpdated(open);
        } else if (suspicion >= threshold) {
            ReviewCase c = reviews.create(player, name, bedrock, suspicion);
            if (clip == null) {
                // Always attach the moments leading up to the case.
                clip = evidence.capture(player, name, check.displayName(), watched, true);
            }
            updateCase(c, player, suspicion, clip, now);
            listener.onCaseOpened(c);
        }

        // Admin alerts: instant for watched players, otherwise grouped by cooldown once the score is notable.
        if (watched) {
            listener.onAlert(flag, true);
        } else if (suspicion >= cfg.detection.alertScore) {
            String key = player + ":" + check.id();
            Long last = lastAlert.get(key);
            if (last == null || now - last >= cfg.detection.alertCooldownSeconds * 1000L) {
                lastAlert.put(key, now);
                listener.onAlert(flag, false);
            }
        }

        // Generic warning to the player.
        if (suspicion >= cfg.warnings.warnScore
                && warnings.shouldWarn(player, cfg.warnings.enabled, watched, shadow.isShadowed(player), false,
                cfg.warnings.cooldownMinutes * Durations.MINUTE, cfg.warnings.maxPerSession)) {
            ReviewCase c = reviews.openCaseFor(player);
            if (c != null) {
                c.warnings.add(now);
            }
            listener.onWarn(player);
        }
        if (clip != null) {
            // After case handling so the clip is saved with its pinned flag.
            listener.onClip(clip);
        }
        return Outcome.RECORDED;
    }

    private void updateCase(ReviewCase c, UUID player, int suspicion, EvidenceClip clip, long now) {
        c.suspicion = suspicion;
        c.updatedAt = now;
        for (Map.Entry<CheckType, Integer> e : violations.flagCounts(player).entrySet()) {
            c.flagCounts.put(e.getKey().id(), e.getValue());
        }
        for (Map.Entry<CheckType, Double> e : violations.snapshot(player).entrySet()) {
            c.points.put(e.getKey().id(), Math.round(e.getValue() * 10) / 10.0);
        }
        if (clip != null) {
            clip.pinned = true;
            c.clipIds.add(clip.id);
            if (c.clipIds.size() > 20) {
                c.clipIds.remove(0);
            }
        }
    }

    /** Records an admin decision and updates the per-check dismissal stats. */
    public ReviewCase decide(long caseId, ReviewCase.Decision decision, String by, String detail) {
        ReviewCase c = reviews.decide(caseId, decision, by, detail);
        if (c == null) {
            return null;
        }
        if (decision == ReviewCase.Decision.DISMISS) {
            stats.recordDismissal(c.topChecks(3));
            violations.reset(c.player);
        } else if (decision != ReviewCase.Decision.WATCH && decision != ReviewCase.Decision.SHADOW) {
            stats.recordActioned(c.topChecks(3));
        }
        return c;
    }
}
