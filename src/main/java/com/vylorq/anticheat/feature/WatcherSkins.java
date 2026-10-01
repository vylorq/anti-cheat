package com.vylorq.anticheat.feature;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a signed skin for the Watcher (Minecraft clients only show signed skins on player figures):
 * <ul>
 * <li>from a Minecraft account name: that account's current skin (Mojang's servers);</li>
 * <li>from a MineSkin link (mineskin.org): upload any skin picture there and use its link.</li>
 * </ul>
 * Runs on a background thread.
 */
public final class WatcherSkins {
    private WatcherSkins() {
    }

    public record Skin(String value, String signature) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern MINESKIN = Pattern.compile("mineskin\\.org/(?:skins/)?([A-Za-z0-9-]+)");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Vigil").GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " from " + URI.create(url).getHost());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static String str(JsonObject o, String... path) {
        JsonElement e = o;
        for (String k : path) {
            if (e == null || !e.isJsonObject() || !e.getAsJsonObject().has(k)) {
                return null;
            }
            e = e.getAsJsonObject().get(k);
        }
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** @param what a Minecraft account name or a mineskin.org link */
    public static Skin find(String what) throws IOException, InterruptedException {
        String w = what.trim();
        Matcher m = MINESKIN.matcher(w);
        if (m.find()) {
            String id = m.group(1);
            try {
                JsonObject o = getJson("https://api.mineskin.org/v2/skins/" + id);
                String value = str(o, "skin", "texture", "data", "value");
                String sig = str(o, "skin", "texture", "data", "signature");
                if (value != null && sig != null) {
                    return new Skin(value, sig);
                }
            } catch (IOException e) {
                // older links: try the old API below
            }
            JsonObject o = getJson("https://api.mineskin.org/get/uuid/" + id);
            String value = str(o, "data", "texture", "value");
            String sig = str(o, "data", "texture", "signature");
            if (value == null || sig == null) {
                throw new IOException("MineSkin didn't return a skin for that link");
            }
            return new Skin(value, sig);
        }
        if (!NAME.matcher(w).matches()) {
            throw new IOException("give a Minecraft account name or a mineskin.org link");
        }
        JsonObject profile = getJson("https://api.mojang.com/users/profiles/minecraft/" + w);
        String uuid = str(profile, "id");
        if (uuid == null) {
            throw new IOException("no Minecraft account called " + w);
        }
        JsonObject session = getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
        if (session.has("properties")) {
            for (JsonElement p : session.getAsJsonArray("properties")) {
                JsonObject prop = p.getAsJsonObject();
                if ("textures".equals(str(prop, "name"))) {
                    String value = str(prop, "value");
                    String sig = str(prop, "signature");
                    if (value != null && sig != null) {
                        return new Skin(value, sig);
                    }
                }
            }
        }
        throw new IOException(w + " has no skin");
    }
}
