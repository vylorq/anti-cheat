package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Screen handler behind every {@link Menu}. All clicks are decided here on the server: control slots never give
 * items, and an out-of-range or impossible click is rejected (and counted as a bad packet).
 */
public final class MenuHandler extends GenericContainerScreenHandler {
    static final Set<MenuHandler> OPEN = Collections.newSetFromMap(new WeakHashMap<>());

    private final Menu menu;
    private final int menuSize;

    public MenuHandler(ScreenHandlerType<?> type, int syncId, PlayerInventory playerInv, Inventory inv, int rows, Menu menu) {
        super(type, syncId, playerInv, inv, rows);
        this.menu = menu;
        this.menuSize = rows * 9;
        OPEN.add(this);
    }

    public Menu menu() {
        return menu;
    }

    private static Menu.Click click(int button, SlotActionType action) {
        return switch (action) {
            case PICKUP -> button == 1 ? Menu.Click.RIGHT : Menu.Click.LEFT;
            case QUICK_MOVE -> button == 1 ? Menu.Click.SHIFT_RIGHT : Menu.Click.SHIFT_LEFT;
            case CLONE -> Menu.Click.MIDDLE;
            case THROW -> Menu.Click.DROP;
            default -> Menu.Click.OTHER;
        };
    }

    @Override
    public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity sp)) {
            return;
        }
        int totalSlots = this.slots.size();
        if (slot != -999 && (slot < -1 || slot >= totalSlots)) {
            // Impossible slot index: only a modified client sends this.
            PlayerSessionFlags.flag(sp, CheckType.BAD_PACKET, 1.0, "menu slot " + slot);
            syncState();
            return;
        }
        boolean inMenu = slot >= 0 && slot < menuSize;
        if (action == SlotActionType.PICKUP_ALL) {
            // Double-click collects from every slot, including control slots: never allowed.
            syncState();
            return;
        }
        if (action == SlotActionType.QUICK_CRAFT) {
            boolean ok = menu.allowPlayerInventory && (!inMenu || menu.isEditable(slot));
            if (!ok) {
                endQuickCraft();
                syncState();
                return;
            }
            super.onSlotClick(slot, button, action, player);
            if (inMenu && menu.onEdit != null) {
                menu.onEdit.accept(sp, slot);
            }
            return;
        }
        if (inMenu) {
            if (menu.isEditable(slot)) {
                if (action == SlotActionType.QUICK_MOVE) {
                    // Shift-click out of an editable slot back to the player's inventory.
                    super.onSlotClick(slot, button, action, player);
                } else {
                    super.onSlotClick(slot, button, action, player);
                }
                if (menu.onEdit != null) {
                    menu.onEdit.accept(sp, slot);
                }
                return;
            }
            syncState();
            if (menu.reserved.contains(slot)) {
                return;
            }
            Menu.Button b = menu.button(slot);
            if (b == null || b.handler() == null) {
                return;
            }
            if (b.perm() != null && !Perms.require(sp, b.perm())) {
                sp.closeHandledScreen();
                return;
            }
            try {
                b.handler().click(sp, click(button, action));
            } catch (Exception e) {
                Ac.LOG.error("Menu action failed", e);
            }
            return;
        }
        // Player inventory side (or clicking outside the window).
        if (!menu.allowPlayerInventory) {
            syncState();
            return;
        }
        if (action == SlotActionType.QUICK_MOVE && slot >= 0) {
            quickMoveToEditable(sp, slot);
            syncState();
            return;
        }
        super.onSlotClick(slot, button, action, player);
    }

    /** Shift-click from the player's inventory goes only into editable slots. */
    private void quickMoveToEditable(ServerPlayerEntity sp, int slot) {
        var from = this.slots.get(slot);
        ItemStack stack = from.getStack();
        if (stack.isEmpty() || menu.editable.isEmpty()) {
            return;
        }
        for (int target : menu.editable.stream().sorted().toList()) {
            if (stack.isEmpty()) {
                break;
            }
            ItemStack in = menu.inventory.getStack(target);
            if (in.isEmpty()) {
                menu.inventory.setStack(target, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.areItemsAndComponentsEqual(in, stack) && in.getCount() < in.getMaxCount()) {
                int move = Math.min(stack.getCount(), in.getMaxCount() - in.getCount());
                in.increment(move);
                stack.decrement(move);
            } else {
                continue;
            }
            if (menu.onEdit != null) {
                menu.onEdit.accept(sp, target);
            }
        }
        from.markDirty();
        menu.inventory.markDirty();
    }

    /** Shift-click out of an editable menu slot moves the stack into the player's inventory. */
    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        if (slot >= 0 && slot < menuSize && menu.isEditable(slot)) {
            var s = this.slots.get(slot);
            ItemStack stack = s.getStack();
            if (!stack.isEmpty()) {
                insertItem(stack, menuSize, this.slots.size(), true);
                s.markDirty();
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        OPEN.remove(this);
        if (player instanceof ServerPlayerEntity sp && menu.onClose != null) {
            try {
                menu.onClose.accept(sp);
            } catch (Exception e) {
                Ac.LOG.error("Menu close handler failed", e);
            }
        }
    }

    /** Called once a second: re-renders live menus. */
    public static void tickLive() {
        for (MenuHandler h : OPEN.toArray(new MenuHandler[0])) {
            if (h != null && h.menu.live) {
                h.menu.refresh();
            }
        }
    }
}
