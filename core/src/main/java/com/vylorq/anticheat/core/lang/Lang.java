package com.vylorq.anticheat.core.lang;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side translations (section 27). Messages are looked up by key; {@code {0}}, {@code {1}}... are replaced
 * by arguments. English is always loaded as the fallback. Files in {@code config/vigil/lang/} override the
 * bundled ones so owners can edit wording.
 */
public final class Lang {
    private static final Gson GSON = new Gson();
    private volatile Map<String, String> active = new HashMap<>();
    private volatile Map<String, String> english = new HashMap<>();

    public void load(String language, Path overrideDir) {
        Map<String, String> en = read("en_us", overrideDir);
        Map<String, String> lang = language.equals("en_us") ? en : read(language, overrideDir);
        english = en;
        active = lang;
    }

    private static Map<String, String> read(String language, Path overrideDir) {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = Lang.class.getResourceAsStream("/vigil/lang/" + language + ".json")) {
            if (in != null) {
                Map<String, String> m = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() { }.getType());
                if (m != null) {
                    out.putAll(m);
                }
            }
        } catch (Exception ignored) {
            // missing bundled file
        }
        if (overrideDir != null) {
            Path f = overrideDir.resolve(language + ".json");
            if (Files.exists(f)) {
                try {
                    Map<String, String> m = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                            new TypeToken<Map<String, String>>() { }.getType());
                    if (m != null) {
                        out.putAll(m);
                    }
                } catch (Exception ignored) {
                    // broken override: keep bundled
                }
            }
        }
        return out;
    }

    public String get(String key, Object... args) {
        String s = active.get(key);
        if (s == null) {
            s = english.get(key);
        }
        if (s == null) {
            s = key;
        }
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    public boolean has(String key) {
        return english.containsKey(key) || active.containsKey(key);
    }

    public Map<String, String> english() {
        return english;
    }
}
