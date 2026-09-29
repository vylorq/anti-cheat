package com.vylorq.anticheat.core.extras;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Sends alerts to a Discord channel by webhook (section 28). Never blocks the server thread. */
public final class DiscordWebhook {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Strips Minecraft colour codes and @everyone/@here pings. */
    public static String clean(String s) {
        return s.replaceAll("§.", "").replace("@everyone", "@​everyone").replace("@here", "@​here");
    }

    public static String payload(String title, String body, int color) {
        String t = escape(clean(title));
        String b = escape(clean(body.length() > 3900 ? body.substring(0, 3900) + "..." : body));
        return "{\"username\":\"Anti-Cheat\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":\"" + t
                + "\",\"description\":\"" + b + "\",\"color\":" + color + "}]}";
    }

    public CompletableFuture<Integer> send(String url, String title, String body, int color) {
        if (url == null || url.isBlank() || !url.startsWith("https://")) {
            return CompletableFuture.completedFuture(0);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(title, body, color)))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenApply(HttpResponse::statusCode)
                .exceptionally(e -> -1);
    }
}
