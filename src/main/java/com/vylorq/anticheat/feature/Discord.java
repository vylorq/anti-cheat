package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;

/** Discord alerts by type (section 28). */
public final class Discord {
    private Discord() {
    }

    public static void send(String type, String title, String body, int color) {
        var cfg = Ac.config().discord;
        if (cfg.webhookUrl == null || cfg.webhookUrl.isBlank() || !cfg.types.getOrDefault(type, false)) {
            return;
        }
        Ac.get().discord.send(cfg.webhookUrl, title, body, color).thenAccept(code -> {
            if (code != null && (code < 200 || code >= 300)) {
                Ac.LOG.warn("Discord webhook returned {}", code);
            }
        });
    }
}
