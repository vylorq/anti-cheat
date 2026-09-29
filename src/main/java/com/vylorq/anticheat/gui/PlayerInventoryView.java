package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.util.Icons;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * A 54-slot live view of a player's inventory for /inspect: rows 1-3 main inventory, row 4 hotbar,
 * row 5 armour + offhand. Row 6 is for menu buttons. Changes go straight to the real inventory.
 */
public final class PlayerInventoryView implements Inventory {
    /** Menu slot -> player inventory slot, or -1. */
    static final int[] MAP = new int[54];

    static {
        java.util.Arrays.fill(MAP, -1);
        for (int i = 0; i < 27; i++) {
            MAP[i] = 9 + i;
        }
        for (int i = 0; i < 9; i++) {
            MAP[27 + i] = i;
        }
        MAP[36] = 39; // helmet
        MAP[37] = 38; // chestplate
        MAP[38] = 37; // leggings
        MAP[39] = 36; // boots
        MAP[40] = 40; // offhand
    }

    private final Inventory target;
    private final ItemStack[] icons = new ItemStack[54];

    public PlayerInventoryView(Inventory target) {
        this.target = target;
    }

    public static boolean isContent(int menuSlot) {
        return menuSlot >= 0 && menuSlot < 54 && MAP[menuSlot] >= 0;
    }

    public static int targetSlot(int menuSlot) {
        return MAP[menuSlot];
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public boolean isEmpty() {
        return target.isEmpty();
    }

    @Override
    public ItemStack getStack(int slot) {
        if (isContent(slot)) {
            return target.getStack(MAP[slot]);
        }
        ItemStack i = icons[slot];
        return i == null ? ItemStack.EMPTY : i;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        if (!isContent(slot)) {
            return ItemStack.EMPTY;
        }
        return target.removeStack(MAP[slot], amount);
    }

    @Override
    public ItemStack removeStack(int slot) {
        if (!isContent(slot)) {
            return ItemStack.EMPTY;
        }
        return target.removeStack(MAP[slot]);
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (isContent(slot)) {
            target.setStack(MAP[slot], stack);
        } else {
            icons[slot] = stack;
        }
    }

    @Override
    public void markDirty() {
        target.markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void clear() {
        for (int i = 0; i < 54; i++) {
            if (!isContent(i)) {
                icons[i] = ItemStack.EMPTY;
            }
        }
    }

    static ItemStack label(String s) {
        return Icons.of(Items.BLACK_STAINED_GLASS_PANE, s);
    }
}
