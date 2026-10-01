package com.vylorq.anticheat.core.watcher;

import java.util.Locale;

/**
 * Every Watcher effect (section 33.5). Pooled effects are picked by weight on each scheduled event; rare effects
 * have their own small chances; triggered effects happen on a game event (bed) instead of the schedule.
 */
public enum WatcherEffect {
    APPEAR(Kind.POOLED, 15),
    DOPPELGANGER(Kind.POOLED, 8),
    MESSAGE(Kind.POOLED, 10),
    FOOTSTEPS(Kind.POOLED, 8),
    /** Heartbeat speeds up, then it's right in their face, screaming, and gone. */
    JUMPSCARE(Kind.POOLED, 8),
    /** "turn around..." and breathing behind them; when they turn, it's in their face. */
    BEHIND_YOU(Kind.POOLED, 6),
    /** Far away; every time they look away it's closer. When it's close and they look... */
    CLOSER(Kind.POOLED, 5),
    /** The screen goes black, something breathes around them; when it clears it's standing right there. */
    BLACKOUT(Kind.POOLED, 4),
    TURN_AROUND(Kind.POOLED, 6),
    MIRRORING(Kind.POOLED, 5),
    FLICKER(Kind.POOLED, 5),
    SIGN(Kind.POOLED, 3),
    WHISPER(Kind.POOLED, 3),
    OWN_VOICE(Kind.POOLED, 3),
    SILENCE(Kind.POOLED, 3),
    KNOCKING(Kind.POOLED, 2),
    ANIMALS_STARE(Kind.POOLED, 2),
    STORM(Kind.POOLED, 2),
    WRONG_COMPASS(Kind.POOLED, 1),
    /** Chest note or pocket gift (one pool entry for both). */
    GIFT(Kind.POOLED, 1),

    /** "ɪ ꜱᴇᴇ ʏᴏᴜ" instead of the normal message. */
    GLITCH_TEXT(Kind.RARE, 0),
    FAKE_JOIN(Kind.RARE, 0),
    /** Off by default. */
    RUSH(Kind.RARE, 0),
    BEDSIDE(Kind.RARE, 0),

    SLEEP_WELL(Kind.TRIGGERED, 0);

    public enum Kind { POOLED, RARE, TRIGGERED }

    public final Kind kind;
    public final int defaultWeight;

    WatcherEffect(Kind kind, int defaultWeight) {
        this.kind = kind;
        this.defaultWeight = defaultWeight;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WatcherEffect byId(String id) {
        if (id == null) {
            return null;
        }
        String n = id.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (WatcherEffect e : values()) {
            if (e.name().equals(n)) {
                return e;
            }
        }
        return null;
    }
}
