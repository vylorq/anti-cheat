package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Sounds;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Viewer;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A server-side chest menu. Works on Java and on Bedrock through Geyser (section 4). Buttons carry the
 * permission they need; it is checked again every time a click arrives.
 * <p>
 * Menus made with {@link #std} use Vigil's standard layout (34.5): category-coloured frame, an info item at the
 * top, 28 content slots, and the same bottom row everywhere: Back, Prev, Search, Next, Filter, Close. Back returns
 * to the menu this one was opened from, on the same page with the same filter.
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

    /** A filter/sort choice for {@link #list}. */
    public record Filter<T>(String name, Predicate<T> keep, Comparator<T> order) {
        public static <T> Filter<T> of(String name, Predicate<T> keep) {
            return new Filter<>(name, keep, null);
        }

        public static <T> Filter<T> sort(String name, Comparator<T> order) {
            return new Filter<>(name, t -> true, order);
        }
    }

    /** The 28 content slots of the standard layout (rows 2-5, columns 2-8). */
    public static final int[] CONTENT = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    public static final int INFO = 4;
    public static final int BACK = 45;
    public static final int PREV = 47;
    public static final int SEARCH = 49;
    public static final int NEXT = 51;
    public static final int FILTER = 52;
    public static final int CLOSE = 53;

    protected String title;
    protected Text titleText;
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
    /** Standard layout colour (null = free layout). */
    protected Theme.Category category;
    /** The menu this one was opened from (Back goes there). */
    protected Menu parent;
    protected ServerPlayerEntity viewer;
    /** List state kept across Back and refreshes. */
    protected int page;
    protected int filter;
    protected String query = "";
    private boolean goingBack;

    public Menu(String title, int rows) {
        this.title = title;
        this.rows = Math.max(1, Math.min(6, rows));
        this.inventory = new SimpleInventory(this.rows * 9);
    }

    /** A standard-layout menu titled {@code Vigil » path...}. */
    public static Menu std(Theme.Category cat, int rows, String... path) {
        Menu m = new Menu(String.join(" » ", path), rows);
        m.category = cat;
        m.titleText = Theme.title(cat, path);
        return m;
    }

    public static Menu std(Theme.Category cat, String... path) {
        return std(cat, 6, path);
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
        this.titleText = null;
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

    public Menu parent(Menu m) {
        this.parent = m;
        return this;
    }

    public Theme.Category category() {
        return category;
    }

    public ServerPlayerEntity viewer() {
        return viewer;
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

    public boolean has(int slot) {
        return buttons.containsKey(slot);
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

    private void render() {
        clear();
        Viewer.with(viewer, () -> {
            if (renderer != null) {
                renderer.accept(this);
            }
            if (category != null) {
                frame();
            }
        });
    }

    /** Standard frame: coloured glass, info item, Back and Close. Slots already set are left alone. */
    private void frame() {
        int last = (rows - 1) * 9;
        if (parent != null && !has(last)) {
            set(last, Btn.of(Items.ARROW).color(Theme.SOFT).name(Msg.tr("ui.back")).left(Msg.tr("ui.action.back")).build(),
                    null, (p, c) -> back(p));
        }
        if (rows > 1 && !has(last + 8)) {
            set(last + 8, Btn.of(Items.BARRIER).color(Theme.RED).name(Msg.tr("ui.close")).build(), null,
                    (p, c) -> p.closeHandledScreen());
        }
        ItemStack glass = Btn.pane(category.glass);
        for (int i = 0; i < size(); i++) {
            int r = i / 9;
            int c = i % 9;
            if ((r == 0 || r == rows - 1 || c == 0 || c == 8) && !buttons.containsKey(i) && !isContent(i)) {
                icon(i, glass);
            }
        }
    }

    /** Top-centre summary item. */
    public Menu info(ItemStack icon) {
        return icon(INFO, icon);
    }

    public void refresh() {
        if (renderer != null || category != null) {
            render();
        }
        if (handler != null) {
            handler.sendContentUpdates();
        }
    }

    public void open(ServerPlayerEntity p) {
        // Remember where we came from so Back can return there (on the same page, same filter).
        if (!goingBack && p.currentScreenHandler instanceof MenuHandler h && h.menu() != this && parent == null
                && !h.menu().hasAncestor(this)) {
            parent = h.menu();
        }
        goingBack = false;
        viewer = p;
        render();
        ScreenHandlerType<?> type = switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 3 -> ScreenHandlerType.GENERIC_9X3;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            default -> ScreenHandlerType.GENERIC_9X6;
        };
        Menu self = this;
        Text name = titleText != null ? titleText : Text.literal(title);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInv, player) -> {
            MenuHandler h = new MenuHandler(type, syncId, playerInv, self.inventory, self.rows, self);
            self.handler = h;
            return h;
        }, name));
        Sounds.play(p, Sounds.Ui.OPEN);
    }

    private boolean hasAncestor(Menu m) {
        for (Menu x = parent; x != null; x = x.parent) {
            if (x == m) {
                return true;
            }
        }
        return false;
    }

    /** Back to the previous menu, or close when there is none. */
    public void back(ServerPlayerEntity p) {
        if (parent == null) {
            p.closeHandledScreen();
            return;
        }
        parent.goingBack = true;
        parent.open(p);
    }

    public boolean isOpenFor(ServerPlayerEntity p) {
        return handler != null && p.currentScreenHandler == handler;
    }

    // ---- lists (34.5): pages, search, filters, empty state ----

    /**
     * Fills the 28 content slots with a page of entries and sets up Prev/Next, Search and Filter.
     *
     * @param searchText text to match a search against, or null for no Search button
     * @param filters    filter/sort choices (the first is the default), or empty for no Filter button
     * @param emptyTitle shown in the middle when nothing matches
     * @param emptyHint  what to do about it
     */
    public <T> void list(List<T> all, Function<T, ItemStack> icon, Function<T, Handler> click, Function<T, String> searchText,
                         List<Filter<T>> filters, String emptyTitle, String emptyHint) {
        List<T> items = new ArrayList<>(all);
        if (!filters.isEmpty()) {
            filter = Math.floorMod(filter, filters.size());
            Filter<T> f = filters.get(filter);
            items.removeIf(t -> !f.keep().test(t));
            if (f.order() != null) {
                items.sort(f.order());
            }
        }
        if (searchText != null && !query.isEmpty()) {
            String q = query.toLowerCase(Locale.ROOT);
            items.removeIf(t -> !String.valueOf(searchText.apply(t)).toLowerCase(Locale.ROOT).contains(q));
        }
        int per = CONTENT.length;
        int pages = Math.max(1, (items.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= items.size()) {
                break;
            }
            T t = items.get(idx);
            set(CONTENT[i], icon.apply(t), click == null ? null : click.apply(t));
        }
        if (items.isEmpty()) {
            boolean searching = searchText != null && !query.isEmpty();
            icon(22, Btn.of(Items.LIGHT_GRAY_DYE).color(Theme.SOFT)
                    .name(searching ? Msg.tr("ui.search.none", query) : emptyTitle)
                    .desc(searching ? Msg.tr("ui.search.none-hint") : emptyHint).build());
        }
        String pageText = Msg.tr("ui.page", page + 1, pages);
        if (page > 0) {
            set(PREV, Btn.of(Items.ARROW).color(Theme.GOLD_LIGHT).name(Msg.tr("ui.prev")).line(pageText).build(), null, (p, c) -> {
                page--;
                Sounds.play(p, Sounds.Ui.PAGE);
                refresh();
            });
        }
        if (page < pages - 1) {
            set(NEXT, Btn.of(Items.ARROW).color(Theme.GOLD_LIGHT).name(Msg.tr("ui.next")).line(pageText).build(), null, (p, c) -> {
                page++;
                Sounds.play(p, Sounds.Ui.PAGE);
                refresh();
            });
        }
        if (searchText != null) {
            Btn b = Btn.of(Items.NAME_TAG).color(Theme.GOLD_LIGHT).name(Msg.tr("ui.search"))
                    .line(query.isEmpty() ? Msg.tr("ui.search.all") : Msg.tr("ui.search.current", query))
                    .left(Msg.tr("ui.action.search"));
            if (!query.isEmpty()) {
                b.right(Msg.tr("ui.action.clear-search")).glint(true);
            }
            set(SEARCH, b.build(), null, (p, c) -> {
                if (c.isRight()) {
                    query = "";
                    page = 0;
                    refresh();
                    return;
                }
                Menu self = this;
                Input.text(p, Msg.trFor(p, "ui.search"), query, txt -> {
                    self.query = txt == null ? "" : txt.trim();
                    self.page = 0;
                    self.goingBack = true;
                    self.open(p);
                });
            });
        }
        if (filters.size() > 1) {
            Btn b = Btn.of(Items.HOPPER).color(Theme.GOLD_LIGHT).name(Msg.tr("ui.filter"));
            for (int i = 0; i < filters.size(); i++) {
                String n = filters.get(i).name();
                if (i == filter) {
                    b.status(Theme.GOLD_LIGHT, Theme.Sym.ARROW.sp() + n);
                } else {
                    b.status(Theme.SOFT, "  " + n);
                }
            }
            b.left(Msg.tr("ui.action.next-filter")).right(Msg.tr("ui.action.prev-filter"));
            int count = filters.size();
            set(FILTER, b.build(), null, (p, c) -> {
                filter = Math.floorMod(filter + (c.isRight() ? -1 : 1), count);
                page = 0;
                Sounds.play(p, Sounds.Ui.CLICK);
                refresh();
            });
        }
    }

    // ---- common widgets ----

    public static ItemStack back() {
        return Btn.of(Items.ARROW).color(Theme.SOFT).name(Msg.tr("ui.back")).left(Msg.tr("ui.action.back")).build();
    }

    public static ItemStack close() {
        return Btn.of(Items.BARRIER).color(Theme.RED).name(Msg.tr("ui.close")).build();
    }

    /** Lays out a page of entries in the top rows and prev/next arrows in the bottom row (free-layout menus). */
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
        String pageText = Msg.tr("ui.page", pg + 1, pages);
        if (pg > 0) {
            set(base + 3, Btn.of(Items.ARROW).color(Theme.GOLD_LIGHT).name(Msg.tr("ui.prev")).line(pageText).build(), (pl, c) -> goTo.accept(pg - 1));
        }
        if (pg < pages - 1) {
            set(base + 5, Btn.of(Items.ARROW).color(Theme.GOLD_LIGHT).name(Msg.tr("ui.next")).line(pageText).build(), (pl, c) -> goTo.accept(pg + 1));
        }
        icon(base + 4, Btn.of(Items.PAPER).color(Theme.WHITE).name(pageText).line(Msg.tr("ui.entries", items.size())).build());
    }
}
