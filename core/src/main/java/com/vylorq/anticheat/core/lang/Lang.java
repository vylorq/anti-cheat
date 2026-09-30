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
    /** Every bundled language. Players see their own game language when it's one of these. */
    public static final java.util.List<String> LANGUAGES = java.util.List.of("en_us", "ar_sa");

    private volatile Map<String, String> active = new HashMap<>();
    private volatile Map<String, String> english = new HashMap<>();
    private volatile Map<String, Map<String, String>> all = new HashMap<>();
    private volatile String defaultLanguage = "en_us";

    public void load(String language, Path overrideDir) {
        Map<String, Map<String, String>> loaded = new HashMap<>();
        for (String l : LANGUAGES) {
            loaded.put(l, read(l, overrideDir));
        }
        if (!loaded.containsKey(language)) {
            loaded.put(language, read(language, overrideDir));
        }
        all = loaded;
        english = loaded.get("en_us");
        active = loaded.get(language);
        defaultLanguage = language;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    /** Whether a language (e.g. a player's game language "ar_sa") has a translation. */
    public boolean supports(String language) {
        return language != null && all.containsKey(language) && !all.get(language).isEmpty();
    }

    /** Text in a given language, falling back to the server default and then English. */
    public String get(String language, String key, Object[] args) {
        Map<String, String> m = language == null ? null : all.get(language);
        String s = m == null ? null : m.get(key);
        if (s == null) {
            s = active.get(key);
        }
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
