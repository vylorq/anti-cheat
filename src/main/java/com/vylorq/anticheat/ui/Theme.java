package com.vylorq.anticheat.ui;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

/**
 * Vigil's look (section 34.1): brand colours, category colours and the status symbols. Java players see the hex
 * colours; Geyser turns them into the nearest legacy colour for Bedrock players, so every colour here was picked to
 * still read well as its fallback.
 */
public final class Theme {
    private Theme() {
    }

    /** Eclipse gold (light): the "Vigil" name and titles. Bedrock fallback §e. */
    public static final int GOLD_LIGHT = 0xF3E6B5;
    /** Eclipse gold: highlights. Bedrock fallback §6. */
    public static final int GOLD = 0xD9B866;
    /** Violet: the » separator, the Watcher, accents. Bedrock fallback §d. */
    public static final int VIOLET = 0x8A6FC4;
    /** Deep violet: secondary accents. Bedrock fallback §5. */
    public static final int DEEP_VIOLET = 0x4B3C80;
    /** Soft gray: descriptions. §7. */
    public static final int SOFT = 0xA9A3BF;
    /** Dim gray: click hints and fine print. §8. */
    public static final int DIM = 0x6E6885;

    public static final int GREEN = 0x5FD35F;
    public static final int RED = 0xE5534B;
    public static final int AQUA = 0x56C8E0;
    public static final int ORANGE = 0xE8A33D;
    public static final int WHITE = 0xFFFFFF;

    public static MutableText c(String s, int rgb) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withItalic(false));
    }

    /** "Vigil »" in brand colours. */
    public static MutableText prefix() {
        return c("Vigil", GOLD_LIGHT).append(c(" » ", VIOLET));
    }

    /** Menu title: {@code Vigil » Claims » Spawn}. The last part takes the category colour. */
    public static MutableText title(Category cat, String... path) {
        MutableText t = c("Vigil", GOLD);
        for (int i = 0; i < path.length; i++) {
            t.append(c(" » ", VIOLET));
            boolean last = i == path.length - 1;
            t.append(c(path[i], last && cat != null ? cat.color : DEEP_VIOLET));
        }
        return t;
    }

    /** Every area of the mod, with its colour, frame glass and icon (34.4). */
    public enum Category {
        VIGIL(GOLD, Items.PURPLE_STAINED_GLASS_PANE, Items.ENDER_EYE),
        REVIEW(0xE5534B, Items.RED_STAINED_GLASS_PANE, Items.WRITABLE_BOOK),
        PLAYERS(0x56C8E0, Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.SPYGLASS),
        WATCHLIST(0xE8A33D, Items.ORANGE_STAINED_GLASS_PANE, Items.OBSERVER),
        PUNISHMENTS(0xB02E26, Items.RED_STAINED_GLASS_PANE, Items.IRON_SWORD),
        REPORTS(0xF2D95C, Items.YELLOW_STAINED_GLASS_PANE, Items.PAPER),
        LOGS(0xC19A5B, Items.BROWN_STAINED_GLASS_PANE, Items.BOOK),
        CLAIMS(0x5FD35F, Items.LIME_STAINED_GLASS_PANE, Items.STICK),
        BARRIERS(0x3FC8C8, Items.CYAN_STAINED_GLASS_PANE, Items.GLASS),
        LOBBY(0xFFFFFF, Items.WHITE_STAINED_GLASS_PANE, Items.OAK_DOOR),
        JAIL(0xA0A0A0, Items.GRAY_STAINED_GLASS_PANE, Items.IRON_BARS),
        ARENAS(0xF08CC8, Items.PINK_STAINED_GLASS_PANE, Items.DIAMOND_SWORD),
        TRADERS(0xE8B83D, Items.YELLOW_STAINED_GLASS_PANE, Items.EMERALD),
        STAFF(0x5B8CFF, Items.BLUE_STAINED_GLASS_PANE, Items.COMPASS),
        DEATHS(0x6B6B6B, Items.BLACK_STAINED_GLASS_PANE, Items.SKELETON_SKULL),
        LAG(0xC8C8C8, Items.LIGHT_GRAY_STAINED_GLASS_PANE, Items.CLOCK),
        WAITING(0x4B5BD0, Items.BLUE_STAINED_GLASS_PANE, Items.BLACK_CANDLE),
        WATCHER(VIOLET, Items.PURPLE_STAINED_GLASS_PANE, Items.ENDER_EYE),
        SETTINGS(0xC8C8D0, Items.LIGHT_GRAY_STAINED_GLASS_PANE, Items.COMPARATOR),
        /** Player-facing menus (trades, traders, arenas). */
        PLAYER(GOLD_LIGHT, Items.PURPLE_STAINED_GLASS_PANE, Items.PLAYER_HEAD);

        public final int color;
        public final Item glass;
        public final Item icon;

        Category(int color, Item glass, Item icon) {
            this.color = color;
            this.glass = glass;
            this.icon = icon;
        }

        public String key() {
            return "cat." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Status symbols (34.6). They're always shown with a word, never alone. Bedrock gets plain stand-ins for the
     * ones its font may not have.
     */
    public enum Sym {
        CHECK("✔", "✔"),
        CROSS("✖", "✖"),
        DOT("●", "●"),
        ARROW("▸", ">"),
        CLOCK("⏱", ""),
        WARN("⚠", "!"),
        CHANGED("●", "*");

        private final String java;
        private final String bedrock;

        Sym(String java, String bedrock) {
            this.java = java;
            this.bedrock = bedrock;
        }

        public String get() {
            return Viewer.bedrock() ? bedrock : java;
        }

        /** Symbol followed by a space, or nothing when the fallback is empty. */
        public String sp() {
            String s = get();
            return s.isEmpty() ? "" : s + " ";
        }
    }
}
