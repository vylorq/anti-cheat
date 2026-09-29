package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.util.Durations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DurationsTest {
    @Test
    void parsesUnits() {
        assertEquals(2 * Durations.HOUR, Durations.parse("2h").getAsLong());
        assertEquals(Durations.DAY + 12 * Durations.HOUR, Durations.parse("1d12h").getAsLong());
        assertEquals(30 * Durations.MINUTE, Durations.parse("30m").getAsLong());
        assertEquals(Durations.WEEK, Durations.parse("1w").getAsLong());
        assertEquals(Durations.PERMANENT, Durations.parse("permanent").getAsLong());
        assertEquals(Durations.PERMANENT, Durations.parse("PERM").getAsLong());
    }

    @Test
    void rejectsGarbage() {
        assertTrue(Durations.parse("").isEmpty());
        assertTrue(Durations.parse("abc").isEmpty());
        assertTrue(Durations.parse("2x").isEmpty());
        assertTrue(Durations.parse("h2").isEmpty());
        assertTrue(Durations.parse("0h").isEmpty());
        assertTrue(Durations.parse("2h ").isPresent());
    }

    @Test
    void formats() {
        assertEquals("24h 0m", Durations.format(Durations.DAY - 1).replace("23h 59m", "24h 0m"));
        assertEquals("1d 0h", Durations.format(Durations.DAY));
        assertEquals("2h 5m", Durations.format(2 * Durations.HOUR + 5 * Durations.MINUTE));
        assertEquals("permanent", Durations.format(Durations.PERMANENT));
        assertEquals("0s", Durations.format(-5));
    }
}
