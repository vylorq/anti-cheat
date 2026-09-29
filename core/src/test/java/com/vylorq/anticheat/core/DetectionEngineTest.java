package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.detect.*;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.evidence.EvidenceRecorder;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.core.review.ReviewManager;
import com.vylorq.anticheat.core.stats.AcStats;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DetectionEngineTest {
    Clock.Manual clock;
    AcConfig cfg;
    ViolationTracker vt;
    Watchlist watch;
    ExemptList exempt;
    ShadowMode shadow;
    ReviewManager reviews;
    EvidenceRecorder rec;
    AcStats stats;
    DetectionEngine engine;
    WarningPolicy warnings;
    final List<String> events = new ArrayList<>();
    final UUID p = UUID.randomUUID();

    @BeforeEach
    void setup() {
        clock = new Clock.Manual(1_000_000_000L);
        cfg = new AcConfig().normalize();
        cfg.detection.autoWatchEnabled = false;
        vt = new ViolationTracker(clock, cfg.detection.decayPerMinute, cfg.detection.suspicionScale);
        watch = new Watchlist(null, clock);
        exempt = new ExemptList(null);
        shadow = new ShadowMode(null);
        reviews = new ReviewManager(null, clock);
        rec = new EvidenceRecorder(clock, 30, 60, 20);
        stats = new AcStats(null);
        warnings = new WarningPolicy(clock);
        engine = new DetectionEngine(() -> cfg, clock, vt, warnings, watch, exempt, shadow, reviews, rec, stats);
        engine.setListener(new DetectionEngine.Listener() {
            @Override
            public void onWarn(UUID player) {
                events.add("warn");
            }

            @Override
            public void onCaseOpened(ReviewCase c) {
                events.add("case");
            }

            @Override
            public void onAutoWatch(UUID player, String name, String reason) {
                events.add("autowatch");
            }

            @Override
            public void onAlert(DetectionEngine.Flag flag, boolean instant) {
                events.add(instant ? "instant-alert" : "alert");
            }
        });
    }

    void flagMany(int n, double pts) {
        for (int i = 0; i < n; i++) {
            rec.record(p, EvidenceEvent.Type.MOVE, i, 64, 0, 0, 0, null);
            engine.flag(p, "Steve", CheckType.SPEED, pts, "test", false);
            clock.advance(100);
        }
    }

    @Test
    void singleFlagDoesNothingDrastic() {
        flagMany(1, 1);
        assertFalse(events.contains("case"));
        assertFalse(events.contains("warn"));
        assertEquals(0, reviews.openCount());
    }

    @Test
    void sustainedFlagsOpenOneCaseAndNeverPunish() {
        flagMany(60, 2);
        assertEquals(1, reviews.openCount(), "exactly one open case");
        assertEquals(1, events.stream().filter("case"::equals).count());
        ReviewCase c = reviews.open().get(0);
        assertEquals(ReviewCase.Status.OPEN, c.status);
        assertTrue(c.flagCounts.get("speed") >= 60);
        assertFalse(c.clipIds.isEmpty(), "evidence attached");
    }

    @Test
    void warningsAreRateLimited() {
        flagMany(200, 3);
        assertEquals(1, events.stream().filter("warn"::equals).count(), "one warning per 15 minutes");
        clock.advance(16 * Durations.MINUTE);
        flagMany(50, 3);
        clock.advance(16 * Durations.MINUTE);
        flagMany(50, 3);
        clock.advance(16 * Durations.MINUTE);
        flagMany(50, 3);
        assertEquals(3, events.stream().filter("warn"::equals).count(), "max 3 per session");
        warnings.startSession(p);
        clock.advance(16 * Durations.MINUTE);
        flagMany(50, 3);
        assertEquals(4, events.stream().filter("warn"::equals).count(), "new session resets");
    }

    @Test
    void watchedPlayersAreNeverWarnedButAlertedInstantly() {
        watch.add(p, "Steve", "test", "admin", Durations.PERMANENT, false);
        flagMany(100, 3);
        assertFalse(events.contains("warn"));
        assertTrue(events.contains("instant-alert"));
    }

    @Test
    void shadowedPlayersAreNeverWarned() {
        shadow.set(p, true);
        flagMany(100, 3);
        assertFalse(events.contains("warn"));
    }

    @Test
    void exemptPlayersRecordedSilently() {
        exempt.add(p, "Steve", "owner", 0);
        flagMany(200, 3);
        assertTrue(events.isEmpty(), "no case, warning, alert: " + events);
        assertTrue(vt.suspicion(p) > 0, "flags still recorded");
        cfg.exempt.recordFlags = false;
        vt.reset(p);
        flagMany(10, 3);
        assertEquals(0, vt.suspicion(p));
    }

    @Test
    void autoWatch() {
        cfg.detection.autoWatchEnabled = true;
        flagMany(40, 2);
        assertTrue(events.contains("autowatch"));
        assertTrue(watch.isWatched(p));
    }

    @Test
    void pointsDecay() {
        flagMany(10, 2);
        int before = vt.suspicion(p);
        clock.advance(30 * Durations.MINUTE);
        assertTrue(vt.suspicion(p) < before);
        clock.advance(10 * Durations.HOUR);
        assertEquals(0, vt.suspicion(p));
    }

    @Test
    void dismissCountsFalseFlag() {
        flagMany(60, 2);
        ReviewCase c = reviews.open().get(0);
        engine.decide(c.id, ReviewCase.Decision.DISMISS, "admin", null);
        assertEquals(ReviewCase.Status.DISMISSED, c.status);
        assertEquals(1, stats.data().dismissalsPerCheck.get("speed"));
        assertEquals(0, vt.suspicion(p));
    }

    @Test
    void disabledCheckIgnored() {
        cfg.detection.disabledChecks.add("speed");
        assertEquals(DetectionEngine.Outcome.IGNORED, engine.flag(p, "Steve", CheckType.SPEED, 5, "", false));
    }
}
