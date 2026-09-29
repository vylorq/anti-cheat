package com.vylorq.anticheat.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.vylorq.anticheat.core.util.AtomicFiles;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads and saves {@link AcConfig}. A broken file is backed up and replaced by defaults instead of crashing. */
public final class ConfigManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private volatile AcConfig config = new AcConfig();

    public ConfigManager(Path dir) {
        this.file = dir.resolve("config.json");
    }

    public AcConfig get() {
        return config;
    }

    public Path file() {
        return file;
    }

    /** @return null on success, or an error message (the previous config is kept). */
    public String load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                config = new AcConfig().normalize();
                save();
                return null;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                AcConfig loaded = GSON.fromJson(r, AcConfig.class);
                if (loaded == null) {
                    loaded = new AcConfig();
                }
                config = loaded.normalize();
            }
            // Re-save so new options appear in the file.
            save();
            return null;
        } catch (JsonParseException e) {
            try {
                Files.copy(file, file.resolveSibling("config.broken.json"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best effort
            }
            return "config.json is not valid JSON (" + e.getMessage() + "); kept previous settings";
        } catch (IOException e) {
            return "could not read config.json: " + e.getMessage();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.writeString(file, GSON.toJson(config));
        } catch (IOException e) {
            throw new RuntimeException("Could not save config", e);
        }
    }
}
