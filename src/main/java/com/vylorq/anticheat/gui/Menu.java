package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.util.Icons;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A server-side chest menu. Works on Java and on Bedrock through Geyser (section 4). Buttons carry the
 * permission they need; it is checked again every time a click arrives.
 */
public class Menu {
    public enum Click { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, MIDDLE, DROP, OTHER;

        public boolean isRight() {
            return this == RIGHT || this == SHIFT_RIGHT;
        }

        public boolean isShift() {
            return this == SHIFT_LEFT || this == SHIFT_RIGHT;
        }
    }

    public interface Handler {
        void click(ServerPlayerEntity player, Click click);
    }

    public record Button(ItemStack icon, Perm perm, Handler handler) {
    }

    protected String title;
    protected final int rows;
    protected final Map<Integer, Button> buttons = new HashMap<>();
    protected final Set<Integer> editable = new HashSet<>();
    /** Content slots this viewer can't touch and the menu must never overwrite (e.g. the other side of a trade). */
    protected final Set<Integer> reserved = new HashSet<>();
    protected Inventory inventory;
    protected boolean allowPlayerInventory;
    protected Consumer<ServerPlayerEntity> onClose;
    protected BiConsumer<ServerPlayerEntity, Integer> onEdit;
    /** Rebuilds the contents; called on open and by {@link #refresh()}. */
    protected Consumer<Menu> renderer;
    /** When set, the menu re-renders every second while open. */
    protected boolean live;
    protected MenuHandler handler;
    /** Default permission for every button in this menu. */
    protected Perm perm;

    public Menu(String title, int rows) {
        this.title = title;
        this.rows = Math.max(1, Math.min(6, rows));
        this.inventory = new SimpleInventory(this.rows * 9);
    }

    public int size() {
        return rows * 9;
    }

    public Menu perm(Perm p) {
        this.perm = p;
        return this;
    }

    public Menu title(String t) {
        this.title = t;
        return this;
    }

    public Menu live() {
        this.live = true;
        return this;
    }

    public Menu renderer(Consumer<Menu> r) {
        this.renderer = r;
        return this;
    }

    public Menu onClose(Consumer<ServerPlayerEntity> c) {
        this.onClose = c;
        return this;
    }

    /** Use an existing inventory (e.g. a live view of a player's inventory) as the menu contents. */
    public Menu backedBy(Inventory inv) {
        this.inventory = inv;
        return this;
    }

    public Menu allowPlayerInventory(boolean allow) {
        this.allowPlayerInventory = allow;
        return this;
    }

    public Menu editable(Set<Integer> slots, BiConsumer<ServerPlayerEntity, Integer> onEdit) {
        this.editable.clear();
        this.editable.addAll(slots);
        this.onEdit = onEdit;
        return this;
    }

    public Menu reserved(Set<Integer> slots) {
        this.reserved.clear();
        this.reserved.addAll(slots);
        return this;
    }

    /** Slots holding real items rather than icons. */
    public boolean isContent(int slot) {
        return editable.contains(slot) || reserved.contains(slot);
    }

    public Inventory inventory() {
        return inventory;
    }

    public boolean isEditable(int slot) {
        return editable.contains(slot);
    }

    public Button button(int slot) {
        return buttons.get(slot);
    }

    public Menu set(int slot, ItemStack icon, Handler h) {
        return set(slot, icon, perm, h);
    }

    public Menu set(int slot, ItemStack icon, Perm p, Handler h) {
        if (slot < 0 || slot >= size() || isContent(slot)) {
            return this;
        }
        buttons.put(slot, new Button(icon, p, h));
        inventory.setStack(slot, icon);
        return this;
    }

    /** Decoration without a click action. */
    public Menu icon(int slot, ItemStack icon) {
        return set(slot, icon, null, null);
    }

    public Menu fill(ItemStack filler) {
        for (int i = 0; i < size(); i++) {
            if (!buttons.containsKey(i) && !isContent(i)) {
                inventory.setStack(i, filler.copy());
            }
        }
        return this;
    }

    public Menu fillBorder() {
        for (int i = 0; i < size(); i++) {
            int r = i / 9;
            int c = i % 9;
            if ((r == 0 || r == rows - 1 || c == 0 || c == 8) && !buttons.containsKey(i) && !isContent(i)) {
                icon(i, Icons.filler());
            }
        }
        return this;
    }

    public Menu clear() {
        buttons.clear();
        for (int i = 0; i < size(); i++) {
            if (!isContent(i)) {
                inventory.setStack(i, ItemStack.EMPTY);
            }
        }
        return this;
    }

    public void refresh() {
        if (renderer != null) {
            clear();
            renderer.accept(this);
        }
        if (handler != null) {
            handler.sendContentUpdates();
        }
    }

    public void open(ServerPlayerEntity p) {
        clear();
        if (renderer != null) {
            renderer.accept(this);
        }
        ScreenHandlerType<?> type = switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 3 -> ScreenHandlerType.GENERIC_9X3;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            default -> ScreenHandlerType.GENERIC_9X6;
        };
        Menu self = this;
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInv, player) -> {
            MenuHandler h = new MenuHandler(type, syncId, playerInv, self.inventory, self.rows, self);
            self.handler = h;
            return h;
        }, Text.literal(title)));
    }

    public boolean isOpenFor(ServerPlayerEntity p) {
        return handler != null && p.currentScreenHandler == handler;
    }

    // ---- common widgets ----

    public static ItemStack back() {
        return Icons.of(Items.ARROW, "§7« Back");
    }

    public static ItemStack close() {
        return Icons.of(Items.BARRIER, "§cClose");
    }

    /** Lays out a page of entries in the top rows and prev/next arrows in the bottom row. */
    public <T> void page(List<T> items, int page, java.util.function.Function<T, ItemStack> icon,
                         java.util.function.Function<T, Handler> click, Consumer<Integer> goTo) {
        int perPage = (rows - 1) * 9;
        int pages = Math.max(1, (items.size() + perPage - 1) / perPage);
        int pg = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < perPage; i++) {
            int idx = pg * perPage + i;
            if (idx >= items.size()) {
                break;
            }
            T t = items.get(idx);
            set(i, icon.apply(t), click.apply(t));
        }
        int base = (rows - 1) * 9;
        for (int i = base; i < base + 9; i++) {
            icon(i, Icons.filler());
        }
        if (pg > 0) {
            set(base + 3, Icons.of(Items.ARROW, "§ePrevious page", "Page " + pg + "/" + pages), (pl, c) -> goTo.accept(pg - 1));
        }
        if (pg < pages - 1) {
            set(base + 5, Icons.of(Items.ARROW, "§eNext page", "Page " + (pg + 2) + "/" + pages), (pl, c) -> goTo.accept(pg + 1));
        }
        icon(base + 4, Icons.of(Items.PAPER, "§fPage " + (pg + 1) + "/" + pages, items.size() + " entries"));
    }
}
