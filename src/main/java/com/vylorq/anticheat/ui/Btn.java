package com.vylorq.anticheat.ui;

import com.mojang.authlib.GameProfile;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Builds every menu button the same way (34.6):
 * <pre>
 * Name                      category colour
 * Short description.        soft gray, wrapped for phones
 *
 * ● Enabled   ⏱ 2d left     status lines
 *
 * ▸ Left-click to open      dim gray hints: left, right, shift
 * </pre>
 * No italics, no vanilla tooltip clutter, glint only for things that need attention.
 */
public final class Btn {
    /** Characters per description line (phone friendly). */
    public static final int LINE = 30;

    private final ItemStack stack;
    private MutableText name;
    private final List<Text> desc = new ArrayList<>();
    private final List<Text> status = new ArrayList<>();
    private final List<Text> hints = new ArrayList<>();
    private boolean glint;
    private int color = Theme.GOLD_LIGHT;

    private Btn(ItemStack stack) {
        this.stack = stack.copy();
    }

    public static Btn of(ItemConvertible item) {
        return new Btn(new ItemStack(item));
    }

    public static Btn of(ItemStack stack) {
        return new Btn(stack);
    }

    public static Btn head(UUID id, String playerName) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        String n = playerName == null ? "" : playerName;
        s.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(new GameProfile(id, n.length() > 16 ? n.substring(0, 16) : n)));
        return new Btn(s);
    }

    public Btn color(int rgb) {
        this.color = rgb;
        return this;
    }

    public Btn color(Theme.Category cat) {
        return color(cat.color);
    }

    /** Button name (plain text; § codes are kept if present). */
    public Btn name(String n) {
        this.name = Theme.c(n, color);
        return this;
    }

    public Btn name(Theme.Category cat, String n) {
        return color(cat).name(n);
    }

    /** Description, wrapped to short lines. */
    public Btn desc(String text) {
        if (text == null || text.isEmpty()) {
            return this;
        }
        for (String paragraph : text.split("\n")) {
            for (String line : wrap(paragraph, LINE)) {
                desc.add(Theme.c(line, Theme.SOFT));
            }
        }
        return this;
    }

    /** "● Enabled" (green) / "● Disabled" (red). */
    public Btn onOff(boolean on) {
        return status(on ? Theme.GREEN : Theme.RED, Theme.Sym.DOT.sp() + Msg.tr(on ? "ui.status.on" : "ui.status.off"));
    }

    /** A status line in its own colour. */
    public Btn status(int rgb, String text) {
        status.add(Theme.c(text, rgb));
        return this;
    }

    /** A plain status/info line (white). */
    public Btn line(String text) {
        status.add(Theme.c(text, Theme.WHITE));
        return this;
    }

    public Btn lines(List<String> texts) {
        for (String t : texts) {
            if (t != null && !t.isEmpty()) {
                line(t);
            }
        }
        return this;
    }

    public Btn left(String action) {
        return hint(Msg.tr("ui.hint.left", action));
    }

    public Btn right(String action) {
        return hint(Msg.tr("ui.hint.right", action));
    }

    public Btn shift(String action) {
        return hint(Msg.tr("ui.hint.shift", action));
    }

    public Btn shiftRight(String action) {
        return hint(Msg.tr("ui.hint.shift-right", action));
    }

    /** A click hint line: "▸ ..." in dim gray. */
    public Btn hint(String text) {
        hints.add(Theme.c(Theme.Sym.ARROW.sp() + text, Theme.DIM));
        return this;
    }

    public Btn glint(boolean on) {
        this.glint = on;
        return this;
    }

    /** "Name (3)" with a glint when something is waiting. */
    public Btn count(int waiting) {
        if (waiting > 0 && name != null) {
            name.append(Theme.c(" (" + waiting + ")", Theme.GOLD_LIGHT));
            glint = true;
        }
        return this;
    }

    public Btn amount(int n) {
        stack.setCount(Math.max(1, Math.min(stack.getMaxCount(), n)));
        return this;
    }

    public ItemStack build() {
        ItemStack s = stack;
        if (name != null) {
            s.set(DataComponentTypes.CUSTOM_NAME, name);
        }
        List<Text> lore = new ArrayList<>(desc);
        if (!status.isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add(Text.empty());
            }
            lore.addAll(status);
        }
        if (!hints.isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add(Text.empty());
            }
            lore.addAll(hints);
        }
        if (!lore.isEmpty()) {
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
        }
        if (glint) {
            s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        hideClutter(s);
        return s;
    }

    private static final List<ComponentType<?>> CLUTTER = List.of(DataComponentTypes.ATTRIBUTE_MODIFIERS,
            DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS, DataComponentTypes.UNBREAKABLE,
            DataComponentTypes.DYED_COLOR, DataComponentTypes.TRIM, DataComponentTypes.JUKEBOX_PLAYABLE,
            DataComponentTypes.POTION_CONTENTS, DataComponentTypes.WRITABLE_BOOK_CONTENT, DataComponentTypes.BANNER_PATTERNS,
            DataComponentTypes.CONTAINER, DataComponentTypes.BLOCK_ENTITY_DATA);

    /** Hides vanilla tooltip lines (attributes, enchantments, "When in main hand"...) on a display item. */
    public static void hideClutter(ItemStack s) {
        LinkedHashSet<ComponentType<?>> hidden = new LinkedHashSet<>(CLUTTER);
        s.set(DataComponentTypes.TOOLTIP_DISPLAY, new TooltipDisplayComponent(false, hidden));
    }

    /** A frame pane with no tooltip at all. */
    public static ItemStack pane(net.minecraft.item.Item glass) {
        ItemStack s = new ItemStack(glass);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        s.set(DataComponentTypes.TOOLTIP_DISPLAY, new TooltipDisplayComponent(true, new LinkedHashSet<>()));
        return s;
    }

    /** Word-wraps to lines of at most {@code width} characters (colour codes don't count). */
    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int visible = 0;
        for (String word : text.split(" ")) {
            int len = visibleLength(word);
            if (visible > 0 && visible + 1 + len > width) {
                out.add(line.toString());
                line.setLength(0);
                visible = 0;
            }
            if (visible > 0) {
                line.append(' ');
                visible++;
            }
            line.append(word);
            visible += len;
        }
        if (line.length() > 0 || out.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    private static int visibleLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '§' && i + 1 < s.length()) {
                i++;
            } else {
                n++;
            }
        }
        return n;
    }
}
