package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.combat.TotemWatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoTotemTest {
    static final long MS = 1_000_000L;

    @Test
    void cheatRefillsInTheNextTick() {
        TotemWatch w = new TotemWatch();
        long t = 5_000 * MS;
        // 120 ms ping: the answer comes back 120 ms after the pop, the refill one game tick (50 ms) later.
        w.onPop(t, 7);
        w.onPong(t + 120 * MS, 7);
        TotemWatch.Verdict v = w.onRefill(t + 170 * MS, 120);
        assertTrue(v.fast(), "auto-totem not caught: " + v);
        assertEquals(50, v.reactionMs());
        assertEquals(1, v.fastInRow());
        // Second pop a minute later: same again, now counted twice.
        long t2 = t + 60_000 * MS;
        w.onPop(t2, 8);
        w.onPong(t2 + 110 * MS, 8);
        assertEquals(2, w.onRefill(t2 + 115 * MS, 120).fastInRow());
    }

    @Test
    void peopleAreNeverFast() {
        TotemWatch w = new TotemWatch();
        long t = 1_000 * MS;
        for (int i = 0; i < 50; i++) {
            long pop = t + i * 30_000 * MS;
            w.onPop(pop, i);
            int ping = 20 + (i * 37) % 400;
            w.onPong(pop + ping * MS, i);
            // The quickest hand: 200 ms from seeing the pop to the totem being in place.
            TotemWatch.Verdict v = w.onRefill(pop + ping * MS + 200 * MS, ping);
            assertFalse(v.fast(), "a person was flagged: " + v);
        }
    }

    @Test
    void lagSpikeAfterThePopOnlyMakesItMoreLenient() {
        TotemWatch w = new TotemWatch();
        long t = 1_000 * MS;
        w.onPop(t, 1);
        // The answer itself is held up by a lag spike: the refill is measured from when the answer arrived.
        w.onPong(t + 900 * MS, 1);
        assertFalse(w.onRefill(t + 1_200 * MS, 80).fast());
    }

    @Test
    void refillBeforeTheAnswerUsesThePingEstimate() {
        TotemWatch w = new TotemWatch();
        long t = 1_000 * MS;
        w.onPop(t, 1);
        // No answer yet (a cheat can be quicker than the game's own ping reply): measured against the ping estimate.
        assertTrue(w.onRefill(t + 60 * MS, 50).fast());
        TotemWatch h = new TotemWatch();
        h.onPop(t, 2);
        // The answer never comes (an old client), a person refills after 500 ms on 100 ms ping: fine.
        assertFalse(h.onRefill(t + 600 * MS, 100).fast());
    }

    @Test
    void onlyRefillsRightAfterAPopCount() {
        TotemWatch w = new TotemWatch();
        long t = 1_000 * MS;
        assertNull(w.onRefill(t, 50), "no pop: nothing to judge");
        w.onPop(t, 1);
        w.onPong(t + 50 * MS, 1);
        assertNull(w.onRefill(t + 4_000 * MS, 50), "4 seconds later isn't a reaction to the pop");
        w.onPop(t + 10_000 * MS, 2);
        w.onPong(t + 10_050 * MS, 2);
        assertNotNull(w.onRefill(t + 10_400 * MS, 50));
        assertNull(w.onRefill(t + 10_450 * MS, 50), "one refill per pop");
        // An answer to some other ping doesn't count.
        w.onPop(t + 20_000 * MS, 3);
        w.onPong(t + 20_010 * MS, 99);
        assertFalse(w.onRefill(t + 20_300 * MS, 50).fast());
    }
}
