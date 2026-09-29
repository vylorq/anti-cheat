package com.vylorq.anticheat.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vylorq.anticheat.core.lang.Lang;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LangTest {
    static Map<String, String> read(String lang) throws Exception {
        try (var in = Lang.class.getResourceAsStream("/anticheat/lang/" + lang + ".json")) {
            assertNotNull(in, lang + " missing");
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() { }.getType());
        }
    }

    static Set<String> placeholders(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("\\{\\d}").matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    void englishAndArabicMatch() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ar = read("ar_sa");
        assertEquals(en.keySet(), ar.keySet());
        for (String k : en.keySet()) {
            assertEquals(placeholders(en.get(k)), placeholders(ar.get(k)), "placeholders for " + k);
        }
    }

    @Test
    void formatting() {
        Lang l = new Lang();
        l.load("ar_sa", null);
        assertTrue(l.get("general.not-online", "Steve").contains("Steve"));
        assertEquals("missing.key", l.get("missing.key"));
    }

    /** Every literal message key used by the Minecraft layer exists in the English file. */
    @Test
    void everyKeyUsedInModCodeExists() throws Exception {
        Path src = Path.of("..", "src", "main", "java");
        if (!Files.isDirectory(src)) {
            return;
        }
        Map<String, String> en = read("en_us");
        Pattern p = Pattern.compile("Msg\\.(?:tr|send|ok|err)\\([^;]*?\"([a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]*[a-z0-9])+)\"");
        Set<String> missing = new HashSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) {
                    if (!en.containsKey(m.group(1))) {
                        missing.add(m.group(1) + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "missing keys: " + missing);
    }
}
