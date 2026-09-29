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
        assertEquals(60, m.get().detection.reviewThreshold);

        Files.writeString(dir.resolve("config.json"), "{\"detection\":{\"reviewThreshold\":70}}");
        assertNull(m.load());
        assertEquals(70, m.get().detection.reviewThreshold);
        assertNotNull(m.get().traders, "missing section filled with defaults");
        assertEquals(15, m.get().warnings.cooldownMinutes);

        Files.writeString(dir.resolve("config.json"), "{ not json");
        assertNotNull(m.load());
        assertEquals(70, m.get().detection.reviewThreshold, "previous config kept");
        assertTrue(Files.exists(dir.resolve("config.broken.json")));
    }
}
