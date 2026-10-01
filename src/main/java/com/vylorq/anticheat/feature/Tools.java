package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.ItemConv;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Admin tools are ordinary items with a custom-data tag, so they work the same for Bedrock players (section 4).
 */
public final class Tools {
    public static final String KEY = "ac_tool";
    public static final String CLAIM_STICK = "claim_stick";
    public static final String TRADER_STICK = "trader_stick";
    public static final String INSPECTOR = "inspector";
    public static final String BUILDER_WAND = "builder_wand";
    public static final String BUILDER_MENU = "builder_menu";
    public static final String BUILDER_BRUSH = "builder_brush";

    private Tools() {
    }

    public static ItemStack claimStick() {
        ItemStack s = Icons.glint(Icons.of(Items.STICK, "§6Claim Stick",
                "Right-click a block: first corner", "Right-click again: opposite corner",
                "Used for claims, barriers, the lobby, the waiting room and arenas"));
        ItemConv.setTag(s, KEY, CLAIM_STICK);
        return s;
    }

    public static ItemStack traderStick() {
        ItemStack s = Icons.glint(Icons.of(Items.STICK, "§aTrader Stick",
                "Right-click a block: place a trader", "Right-click a trader: edit",
                "Sneak + right-click a trader: remove"));
        ItemConv.setTag(s, KEY, TRADER_STICK);
        return s;
    }

    public static ItemStack inspector() {
        ItemStack s = Icons.glint(Icons.of(Items.SPYGLASS, "§bBlock Inspector",
                "Right-click or left-click a block", "to see who placed or broke it"));
        ItemConv.setTag(s, KEY, INSPECTOR);
        return s;
    }

    public static ItemStack builderWand() {
        ItemStack s = Icons.glint(Icons.of(Items.GOLDEN_AXE, "§6Builder Wand",
                "Left-click a block: first corner", "Right-click a block: second corner",
                "Then use the Builder Menu or /build"));
        ItemConv.setTag(s, KEY, BUILDER_WAND);
        return s;
    }

    public static ItemStack builderMenu() {
        ItemStack s = Icons.glint(Icons.of(Items.NETHER_STAR, "§bBuilder Menu",
                "Right-click: set, replace, walls, copy, paste,", "rotate, undo and saved builds"));
        ItemConv.setTag(s, KEY, BUILDER_MENU);
        return s;
    }

    public static ItemStack builderBrush() {
        ItemStack s = Icons.glint(Icons.of(Items.BLAZE_ROD, "§dBuilder Brush",
                "Right-click: use the brush where you look", "(up to 120 blocks away)", "Change it in the Builder Menu"));
        ItemConv.setTag(s, KEY, BUILDER_BRUSH);
        return s;
    }

    public static String toolOf(ItemStack s) {
        return s.isEmpty() ? null : ItemConv.tag(s, KEY);
    }

    public static boolean is(ItemStack s, String tool) {
        return tool.equals(toolOf(s));
    }
}
