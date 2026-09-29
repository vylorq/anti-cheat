package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.extras.DiscordWebhook;
import com.vylorq.anticheat.core.extras.RestartScheduler;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtrasTest {
    @Test
    void discordPayloadIsSafe() {
        String p = DiscordWebhook.payload("§cBan \"x\"", "line1\nline2 @everyone", 0xff0000);
        assertTrue(p.contains("\\\"x\\\""));
        assertTrue(p.contains("\\n"));
        assertFalse(p.contains("§"));
        assertFalse(p.contains("@everyone"));
        assertTrue(p.contains("\"parse\":[]"));
    }

    @Test
    void restartSchedule() {
        long midnight = 1_700_006_400_000L - (1_700_006_400_000L % 86_400_000L);
        long next = RestartScheduler.next(List.of("05:00"), midnight + 6 * 3_600_000L, ZoneOffset.UTC);
        assertEquals(midnight + 86_400_000L + 5 * 3_600_000L, next);
        assertEquals(5, RestartScheduler.warningDue(next, next - 5 * 60_000 - 10, next - 5 * 60_000 + 10, List.of(15, 5, 1)));
        assertEquals(-1, RestartScheduler.warningDue(next, next - 4 * 60_000, next - 3 * 60_000, List.of(15, 5, 1)));
    }
}
