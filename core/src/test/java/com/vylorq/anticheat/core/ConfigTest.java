package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @Test
    void createsDefaultsAndSurvivesPartialAndBrokenFiles(@TempDir Path dir) throws Exception {
        ConfigManager m = new ConfigManager(dir);
        assertNull(m.load());
        assertTrue(Files.exists(dir.resolve("config.json")));
        assertEquals(70, m.get().detection.reviewThreshold);

        Files.writeString(dir.resolve("config.json"), "{\"detection\":{\"reviewThreshold\":80}}");
        assertNull(m.load());
        assertEquals(80, m.get().detection.reviewThreshold);
        assertNotNull(m.get().traders, "missing section filled with defaults");
        assertEquals(15, m.get().warnings.cooldownMinutes);

        Files.writeString(dir.resolve("config.json"), "{ not json");
        assertNotNull(m.load());
        assertEquals(80, m.get().detection.reviewThreshold, "previous config kept");
        assertTrue(Files.exists(dir.resolve("config.broken.json")));
    }

    @Test
    void oldDefaultsMoveToSaferOnes(@TempDir Path dir) throws Exception {
        ConfigManager m = new ConfigManager(dir);
        Files.writeString(dir.resolve("config.json"), "{\"configVersion\":1,\"detection\":{\"reviewThreshold\":60,\"alertScore\":30},"
                + "\"combat\":{\"maxCps\":20},\"movement\":{\"bedrockLeniency\":1.6},\"permissions\":{\"flagUnauthorizedActions\":true}}");
        assertNull(m.load());
        assertEquals(70, m.get().detection.reviewThreshold, "old default moved");
        assertEquals(30, m.get().detection.alertScore, "admin's own value kept");
        assertEquals(28, m.get().combat.maxCps);
        assertEquals(2.5, m.get().movement.bedrockLeniency);
        assertFalse(m.get().permissions.flagUnauthorizedActions);
        assertEquals(2, m.get().configVersion);
        assertNull(m.load());
        assertEquals(30, m.get().detection.alertScore, "only migrated once");
    }
}
