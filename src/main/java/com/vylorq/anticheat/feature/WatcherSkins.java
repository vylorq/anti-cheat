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

    /** Version of the skin bundled with the mod (black, glowing eyes, sharp teeth). */
    public static final int BUILT_IN_VERSION = 2;

    /** The bundled skin picture. */
    public static byte[] builtInPng() throws IOException {
        try (var in = WatcherSkins.class.getResourceAsStream("/vigil/watcher_skin.png")) {
            if (in == null) {
                throw new IOException("the built-in skin is missing from the mod");
            }
            return in.readAllBytes();
        }
    }

    /**
     * Uploads a skin picture to MineSkin, which signs it so Minecraft clients will show it. Tries the current API
     * (with the API key if one is set), then the older one.
     */
    public static Skin upload(byte[] png, String apiKey) throws IOException, InterruptedException {
        IOException last = null;
        try {
            JsonObject o = postPng("https://api.mineskin.org/v2/generate", png, apiKey, true);
            String value = str(o, "skin", "texture", "data", "value");
            String sig = str(o, "skin", "texture", "data", "signature");
            if (value != null && sig != null) {
                return new Skin(value, sig);
            }
            last = new IOException("MineSkin gave no texture");
        } catch (IOException e) {
            last = e;
        }
        try {
            JsonObject o = postPng("https://api.mineskin.org/generate/upload", png, apiKey, false);
            String value = str(o, "data", "texture", "value");
            String sig = str(o, "data", "texture", "signature");
            if (value != null && sig != null) {
                return new Skin(value, sig);
            }
        } catch (IOException e) {
            last = e;
        }
        throw last;
    }

    private static JsonObject postPng(String url, byte[] png, String apiKey, boolean v2) throws IOException, InterruptedException {
        String boundary = "----vigil" + System.nanoTime();
        var out = new java.io.ByteArrayOutputStream();
        java.util.function.BiConsumer<String, String> field = (k, v) -> out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + k + "\"\r\n\r\n" + v + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        field.accept("variant", "classic");
        field.accept("name", "vigil-watcher");
        field.accept("visibility", v2 ? "unlisted" : "1");
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"watcher.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.writeBytes(png);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Vigil/1.0")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IOException("MineSkin said HTTP " + res.statusCode());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    /**
     * Uploads the built-in skin and makes it the Watcher's (on a background thread). {@code done} gets null on
     * success or the reason it failed, on the server thread.
     */
    public static void applyBuiltIn(java.util.function.Consumer<String> done) {
        var server = com.vylorq.anticheat.Ac.server();
        String key = com.vylorq.anticheat.Ac.config().watcher.mineskinApiKey;
        Thread t = new Thread(() -> {
            try {
                Skin skin = upload(builtInPng(), key);
                server.execute(() -> {
                    var cfg = com.vylorq.anticheat.Ac.config().watcher;
                    cfg.skinValue = skin.value();
                    cfg.skinSignature = skin.signature();
                    cfg.builtInSkinVersion = BUILT_IN_VERSION;
                    com.vylorq.anticheat.Ac.get().configManager.save();
                    done.accept(null);
                });
            } catch (Exception e) {
                String why = String.valueOf(e.getMessage());
                server.execute(() -> done.accept(why));
            }
        }, "Vigil watcher skin upload");
        t.setDaemon(true);
        t.start();
    }

    /** On server start: if a newer built-in skin came with an update, put it on the Watcher once. */
    public static void onServerStarted() {
        var cfg = com.vylorq.anticheat.Ac.config().watcher;
        if (cfg.builtInSkinVersion >= BUILT_IN_VERSION) {
            return;
        }
        applyBuiltIn(err -> {
            if (err == null) {
                com.vylorq.anticheat.Ac.LOG.info("The Watcher has its new skin.");
            } else {
                com.vylorq.anticheat.Ac.LOG.warn("Couldn't upload the Watcher's new skin ({}). Upload docs/watcher-skin.png to mineskin.org and use /watcher skin <link>.", err);
            }
        });
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
