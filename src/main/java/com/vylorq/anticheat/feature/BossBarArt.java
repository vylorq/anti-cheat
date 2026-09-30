package com.vylorq.anticheat.feature;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.ui.Viewer;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.TextCodecs;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fancy Ender Dragon, Wither and Raid boss bars. The bars themselves are untouched: only the title each player
 * receives is changed, per player.
 * <ul>
 * <li>Java players who accepted the server resource pack get a big ornament (a glyph from the pack's
 * {@code vigil:bossbar} font) drawn around the bar, with the name centred on top.</li>
 * <li>Bedrock players get small ornaments on both sides of the name, from the Bedrock pack Geyser sends them.</li>
 * <li>Java players without the pack see the normal bar.</li>
 * </ul>
 * The art comes from {@code scripts/make-bossbar-packs.py}.
 */
public final class BossBarArt {
    private BossBarArt() {
    }

    public enum Kind {
        DRAGON('', '', '', Formatting.LIGHT_PURPLE),
        WITHER('', '', '', Formatting.WHITE),
        RAID('', '', '', Formatting.GOLD);

        final char art;
        final char bedrockLeft;
        final char bedrockRight;
        final Formatting nameColor;

        Kind(char art, char bedrockLeft, char bedrockRight, Formatting nameColor) {
            this.art = art;
            this.bedrockLeft = bedrockLeft;
            this.bedrockRight = bedrockRight;
            this.nameColor = nameColor;
        }
    }

    public static final Identifier FONT = Identifier.of("vigil", "bossbar");
    /** Width of each art glyph in the Java pack. */
    public static final int ART_WIDTH = 256;

    /** Which bars are which (learned from their first packet; later packets only carry the name). */
    private static final Map<UUID, Kind> KINDS = new ConcurrentHashMap<>();
    /** Connections whose client loaded the server resource pack. */
    private static final Map<Object, Boolean> PACK = Collections.synchronizedMap(new WeakHashMap<>());

    public static void packLoaded(Object connection, boolean loaded) {
        PACK.put(connection, loaded);
    }

    public static boolean hasPack(Object connection) {
        return Boolean.TRUE.equals(PACK.get(connection));
    }

    private static String key(Text t) {
        TextContent c = t.getContent();
        return c instanceof TranslatableTextContent tr ? tr.getKey() : "";
    }

    static Kind detect(Text name, BossBar.Color color, boolean darkenSky, boolean dragonMusic) {
        String k = key(name);
        if (k.startsWith("event.minecraft.raid")) {
            return Kind.RAID;
        }
        if (dragonMusic || k.equals("entity.minecraft.ender_dragon")) {
            return Kind.DRAGON;
        }
        if (k.equals("entity.minecraft.wither") || (darkenSky && color == BossBar.Color.PURPLE)) {
            return Kind.WITHER;
        }
        return null;
    }

    /** Called for every packet sent to a player. @return the packet to send instead (or the same one). */
    public static Packet<?> rewrite(Packet<?> packet, ServerPlayerEntity player, boolean javaPack) {
        if (!(packet instanceof BossBarS2CPacket) || !Ac.running() || !Ac.config().fun.fancyBossBars) {
            return packet;
        }
        return rewrite(packet, Viewer.isBedrock(player), javaPack);
    }

    /** @param bedrock whether the player is on Bedrock; @param javaPack whether a Java player loaded the pack */
    public static Packet<?> rewrite(Packet<?> packet, boolean bedrock, boolean javaPack) {
        if (!(packet instanceof BossBarS2CPacket b)) {
            return packet;
        }
        Packet<?>[] out = {packet};
        b.accept(new BossBarS2CPacket.Consumer() {
            @Override
            public void add(UUID uuid, Text name, float percent, BossBar.Color color, BossBar.Style style, boolean darkenSky,
                            boolean dragonMusic, boolean thickenFog) {
                Kind kind = detect(name, color, darkenSky, dragonMusic);
                if (kind == null) {
                    return;
                }
                if (KINDS.size() > 1000) {
                    KINDS.clear();
                }
                KINDS.put(uuid, kind);
                if (!bedrock && !javaPack) {
                    return;
                }
                BossBar bar = new BossBar(uuid, decorate(kind, name, bedrock), color, style) {
                };
                bar.setPercent(percent);
                bar.setDarkenSky(darkenSky);
                bar.setDragonMusic(dragonMusic);
                bar.setThickenFog(thickenFog);
                out[0] = BossBarS2CPacket.add(bar);
            }

            @Override
            public void updateName(UUID uuid, Text name) {
                Kind kind = key(name).startsWith("event.minecraft.raid") ? Kind.RAID : KINDS.get(uuid);
                if (kind == null || (!bedrock && !javaPack)) {
                    return;
                }
                BossBar bar = new BossBar(uuid, decorate(kind, name, bedrock), BossBar.Color.WHITE, BossBar.Style.PROGRESS) {
                };
                out[0] = BossBarS2CPacket.updateName(bar);
            }
        });
        return out[0];
    }

    /** The decorated title. The name is resolved on the server (English) so its width is known for centring. */
    public static Text decorate(Kind kind, Text name, boolean bedrock) {
        String plain = name.getString();
        if (bedrock) {
            return Text.empty()
                    .append(Text.literal(kind.bedrockLeft + " ").formatted(Formatting.WHITE))
                    .append(Text.literal(plain).formatted(kind.nameColor))
                    .append(Text.literal(" " + kind.bedrockRight).formatted(Formatting.WHITE));
        }
        int w = width(plain);
        // The art is centred on the bar and takes no width, so the name stays centred too:
        // [move right a][art][move back] + name, with a = (name width - art width) / 2.
        int a = (w - ART_WIDTH) / 2;
        String art = spaces(a) + kind.art + spaces(-(a + ART_WIDTH + 1));
        MutableText t = Text.empty().append(artText(art));
        Style style = name.getStyle().getColor() == null ? name.getStyle().withColor(kind.nameColor) : name.getStyle();
        return t.append(Text.literal(plain).setStyle(style));
    }

    /** The art in the pack's font, white and without shadow (built from JSON, which stays the same across versions). */
    static Text artText(String art) {
        JsonObject o = new JsonObject();
        o.addProperty("text", art);
        o.addProperty("font", FONT.toString());
        o.addProperty("color", "white");
        o.addProperty("shadow_color", 0);
        return TextCodecs.CODEC.parse(JsonOps.INSTANCE, o).result().orElseGet(() -> Text.literal(art));
    }

    /** Characters from the pack's space provider: U+F801.. move left by 1, 2, 4...; U+F821.. move right. */
    static String spaces(int n) {
        StringBuilder sb = new StringBuilder();
        int m = Math.abs(n);
        for (int bit = 9; bit >= 0; bit--) {
            if ((m & (1 << bit)) != 0) {
                sb.append((char) ((n < 0 ? 0xF801 : 0xF821) + bit));
            }
        }
        return sb.toString();
    }

    /** Width in pixels of text in Minecraft's default font (ASCII; anything else counts as 6). */
    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += switch (c) {
                case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
                case 'l', '`' -> 3;
                case 'I', 't', '[', ']', ' ' -> 4;
                case 'f', 'k', '<', '>', '(', ')', '{', '}', '"', '*' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        }
        return w;
    }
}
