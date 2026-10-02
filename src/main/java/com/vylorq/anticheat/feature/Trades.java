package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.core.items.IllegalItems;
import com.vylorq.anticheat.core.trade.SecureTrade;
import com.vylorq.anticheat.core.trade.TradeRequests;
import com.vylorq.anticheat.core.trader.Enchants;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secure player trading (section 24). Both players see one shared window; each can only put items in their own
 * side. Items in the window are held in escrow (saved to disk) until the trade completes or is cancelled, so a
 * crash can never lose or duplicate them. The swap happens in one server-thread step.
 */
public final class Trades {
    /** Rows 2-5, left four columns: the first player's items (as stored). */
    private static final int[] SIDE_A = {9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39};
    /** Rows 2-5, right four columns: the second player's items (as stored). */
    private static final int[] SIDE_B = {14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44};

    static boolean isItemSlot(int slot) {
        int r = slot / 9;
        int c = slot % 9;
        return r >= 1 && r <= 4 && c != 4;
    }

    /**
     * What one player sees (34.9): their own items on the left, the other's on the right. Item slots go to the
     * shared trade inventory (mirrored left-right for the second player); every other slot holds that viewer's
     * own buttons, so each player's controls can say "you" and "them".
     */
    static final class TradeView implements net.minecraft.inventory.Inventory {
        private final net.minecraft.inventory.Inventory shared;
        private final boolean mirror;
        private final ItemStack[] icons = new ItemStack[54];

        TradeView(net.minecraft.inventory.Inventory shared, boolean mirror) {
            this.shared = shared;
            this.mirror = mirror;
        }

        private int map(int slot) {
            return mirror ? (slot / 9) * 9 + (8 - slot % 9) : slot;
        }

        @Override
        public int size() {
            return 54;
        }

        @Override
        public boolean isEmpty() {
            return shared.isEmpty();
        }

        @Override
        public ItemStack getStack(int slot) {
            if (isItemSlot(slot)) {
                return shared.getStack(map(slot));
            }
            ItemStack i = icons[slot];
            return i == null ? ItemStack.EMPTY : i;
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            return isItemSlot(slot) ? shared.removeStack(map(slot), amount) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeStack(int slot) {
            return isItemSlot(slot) ? shared.removeStack(map(slot)) : ItemStack.EMPTY;
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            if (isItemSlot(slot)) {
                shared.setStack(map(slot), stack);
            } else {
                icons[slot] = stack;
            }
        }

        @Override
        public void markDirty() {
            shared.markDirty();
        }

        @Override
        public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity player) {
            return true;
        }

        @Override
        public void clear() {
            java.util.Arrays.fill(icons, ItemStack.EMPTY);
        }
    }

    /** One running trade. */
    static final class Session {
        final SecureTrade trade;
        final SimpleInventory items = new SimpleInventory(54);
        final Menu menuA;
        final Menu menuB;
        boolean finishing;

        Session(SecureTrade trade, Menu a, Menu b) {
            this.trade = trade;
            this.menuA = a;
            this.menuB = b;
        }
    }

    private static final Map<UUID, Session> BY_PLAYER = new ConcurrentHashMap<>();
    private static final TradeRequests REQUESTS = new TradeRequests();

    private Trades() {
    }

    public static boolean inTrade(ServerPlayerEntity p) {
        return BY_PLAYER.containsKey(p.getUuid());
    }

    /** Where trading isn't allowed at all. */
    private static String blocked(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        if (ac.jail.isJailed(p.getUuid())) {
            return "trade.blocked.jail";
        }
        if (ac.staff.isFrozen(p.getUuid())) {
            return "trade.blocked.frozen";
        }
        if (WaitingRoomFeature.waiting(p)) {
            return "trade.blocked.waiting";
        }
        if (Arenas.inMatch(p) || Arenas.isSpectating(p) || ac.arenas.hasPending(p.getUuid())) {
            return "trade.blocked.arena";
        }
        if (inTrade(p)) {
            return "trade.blocked.busy";
        }
        return null;
    }

    private static String distanceProblem(ServerPlayerEntity a, ServerPlayerEntity b) {
        if (a.getEntityWorld() != b.getEntityWorld()) {
            return "trade.too-far";
        }
        if (a.squaredDistanceTo(b) > Math.pow(Ac.config().playerTrade.maxDistance, 2)) {
            return "trade.too-far";
        }
        return null;
    }

    public static void request(ServerPlayerEntity from, ServerPlayerEntity to) {
        if (from == to) {
            Msg.send(from, "trade.self");
            return;
        }
        for (ServerPlayerEntity p : List.of(from, to)) {
            String why = blocked(p);
            if (why != null) {
                Msg.send(from, why);
                return;
            }
        }
        String far = distanceProblem(from, to);
        if (far != null) {
            Msg.send(from, far, Ac.config().playerTrade.maxDistance);
            return;
        }
        REQUESTS.request(from.getUuid(), to.getUuid(), System.currentTimeMillis());
        Msg.send(from, "trade.sent", to.getGameProfile().name());
        String n = Msg.q(from.getGameProfile().name());
        to.sendMessage(Msg.prefixed(Msg.tr("trade.received", from.getGameProfile().name())).append(Text.literal(" "))
                .append(Msg.button("§a[Accept]", "/trade accept " + n, "Open the trade window")).append(Text.literal(" "))
                .append(Msg.button("§c[Decline]", "/trade deny " + n, "Decline")));
        com.vylorq.anticheat.ui.BedrockPrompt.ask(to, Msg.trFor(to, "trade.title", from.getGameProfile().name()),
                Msg.trFor(to, "trade.received", from.getGameProfile().name()),
                List.of(Msg.trFor(to, "ui.accept"), Msg.trFor(to, "ui.decline")), List.of("trade accept " + n, "trade deny " + n));
    }

    public static void deny(ServerPlayerEntity to, ServerPlayerEntity from) {
        if (REQUESTS.take(to.getUuid(), from.getUuid(), System.currentTimeMillis(), Ac.config().playerTrade.requestSeconds * 1000L)) {
            Msg.send(from, "trade.declined", to.getGameProfile().name());
        }
    }

    public static void accept(ServerPlayerEntity to, ServerPlayerEntity from) {
        if (!REQUESTS.take(to.getUuid(), from.getUuid(), System.currentTimeMillis(), Ac.config().playerTrade.requestSeconds * 1000L)) {
            Msg.send(to, "trade.no-request");
            return;
        }
        for (ServerPlayerEntity p : List.of(from, to)) {
            String why = blocked(p);
            if (why != null) {
                Msg.send(to, why);
                Msg.send(from, why);
                return;
            }
        }
        String far = distanceProblem(from, to);
        if (far != null) {
            Msg.send(to, far, Ac.config().playerTrade.maxDistance);
            return;
        }
        open(from, to);
    }

    private static boolean isA(Session s, UUID p) {
        return s.trade.a().equals(p);
    }

    private static int[] sideOf(Session s, UUID p) {
        return isA(s, p) ? SIDE_A : SIDE_B;
    }

    private static Set<Integer> set(int[] slots) {
        Set<Integer> out = new HashSet<>();
        for (int i : slots) {
            out.add(i);
        }
        return out;
    }

    private static void open(ServerPlayerEntity a, ServerPlayerEntity b) {
        SecureTrade t = new SecureTrade(a.getUuid(), b.getUuid(), Ac.config().playerTrade.countdownSeconds * 1000L);
        Menu ma = new Menu("", 6);
        Menu mb = new Menu("", 6);
        Session s = new Session(t, ma, mb);
        ma.titleText(title(a, b.getGameProfile().name(), -1));
        mb.titleText(title(b, a.getGameProfile().name(), -1));
        ma.backedBy(new TradeView(s.items, false)).allowPlayerInventory(true);
        mb.backedBy(new TradeView(s.items, true)).allowPlayerInventory(true);
        // In each player's own view their items are on the left (for the second player the view is mirrored).
        ma.editable(set(SIDE_A), (p, slot) -> changed(s, p)).reserved(set(SIDE_B));
        mb.editable(set(SIDE_A), (p, slot) -> changed(s, p)).reserved(set(SIDE_B));
        ma.renderer(m -> render(s, m, t.a()));
        mb.renderer(m -> render(s, m, t.b()));
        ma.onClose(p -> closedBy(s, p));
        mb.onClose(p -> closedBy(s, p));
        BY_PLAYER.put(a.getUuid(), s);
        BY_PLAYER.put(b.getUuid(), s);
        ma.open(a);
        mb.open(b);
        Ac.get().logs.trade(System.currentTimeMillis(), "player-trade-open", a.getUuid(), a.getGameProfile().name(),
                b.getUuid(), b.getGameProfile().name(), "");
    }

    private static net.minecraft.text.Text title(ServerPlayerEntity viewer, String other, int secs) {
        String t = Msg.trFor(viewer, "trade.title", other);
        if (secs >= 0) {
            t += " · " + Msg.trFor(viewer, "trade.in-seconds", secs);
        }
        return Theme.title(Theme.Category.PLAYER, t);
    }

    private static String name(UUID id) {
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
        return p == null ? "?" : p.getGameProfile().name();
    }

    private static void render(Session s, Menu m, UUID me) {
        SecureTrade t = s.trade;
        UUID them = me.equals(t.a()) ? t.b() : t.a();
        String myName = name(me);
        String theirName = name(them);
        ItemStack glass = Btn.pane(Theme.Category.PLAYER.glass);
        for (int i = 0; i < 54; i++) {
            if (!isItemSlot(i)) {
                m.icon(i, glass);
            }
        }
        for (int r = 1; r <= 4; r++) {
            m.icon(r * 9 + 4, Btn.of(Items.IRON_BARS).color(Theme.SOFT).name(Msg.tr("trade.divider")).line("« " + Msg.tr("trade.you") + "   " + theirName + " »").build());
        }
        m.icon(1, head(t, me, myName, Msg.tr("trade.you")));
        m.icon(7, head(t, them, theirName, theirName));
        int secs = t.secondsLeft(System.currentTimeMillis());
        m.icon(4, Btn.of(secs >= 0 ? Items.CLOCK : Items.EMERALD).color(Theme.GOLD_LIGHT).name(secs >= 0 ? Msg.tr("trade.in-seconds", secs) : Msg.tr("trade.how"))
                .desc(Msg.tr(secs >= 0 ? "trade.countdown-desc" : "trade.how-desc")).glint(secs >= 0).build());
        // Shulker boxes: show what's inside so nobody gets scammed with an empty box.
        int preview = 50;
        for (UUID owner : List.of(me, them)) {
            for (ItemStack st : side(s, owner)) {
                if (preview > 52) {
                    break;
                }
                if (st.contains(DataComponentTypes.CONTAINER)) {
                    m.icon(preview++, contentsIcon(st, Msg.tr("trade.contents", owner.equals(me) ? Msg.tr("trade.you") : theirName, st.getName().getString())));
                }
            }
        }
        m.icon(49, valueMeter(side(s, me), side(s, them), theirName));
        int seen = t.revision();
        boolean ready = t.isReady(me);
        boolean confirmed = t.isConfirmed(me);
        Btn action;
        if (confirmed) {
            action = Btn.of(Items.EMERALD_BLOCK).color(Theme.GREEN).name(Theme.Sym.CHECK.sp() + Msg.tr("trade.confirmed")).desc(Msg.tr("trade.waiting-for", theirName));
        } else if (t.step() == SecureTrade.Step.BOTH_READY) {
            action = Btn.of(Items.LIME_CONCRETE).color(Theme.GREEN).name(Msg.tr("trade.confirm")).desc(Msg.tr("trade.confirm-desc"))
                    .left(Msg.tr("trade.action.confirm")).glint(true);
        } else if (ready) {
            action = Btn.of(Items.YELLOW_CONCRETE).color(Theme.GOLD).name(Theme.Sym.CHECK.sp() + Msg.tr("trade.ready")).desc(Msg.tr("trade.waiting-for", theirName))
                    .left(Msg.tr("trade.action.unready"));
        } else {
            action = Btn.of(Items.GRAY_CONCRETE).color(Theme.SOFT).name(Msg.tr("trade.not-ready")).desc(Msg.tr("trade.ready-desc"))
                    .left(Msg.tr("trade.action.ready"));
        }
        m.set(47, action.amount(1).build(), (p, c) -> {
            if (s.trade.step() == SecureTrade.Step.BOTH_READY && !s.trade.isConfirmed(p.getUuid())) {
                if (!s.trade.confirm(p.getUuid(), seen, System.currentTimeMillis())) {
                    Msg.send(p, "trade.changed");
                }
            } else if (s.trade.step() == SecureTrade.Step.OPEN || s.trade.step() == SecureTrade.Step.BOTH_READY) {
                s.trade.toggleReady(p.getUuid());
            }
            refresh(s);
        });
        m.set(45, Btn.of(Items.BARRIER).color(Theme.RED).name(Msg.tr("trade.cancel")).desc(Msg.tr("trade.cancel-desc"))
                .left(Msg.tr("ui.cancel").toLowerCase(java.util.Locale.ROOT)).build(), (p, c) -> cancel(s, SecureTrade.CancelReason.CLOSED));
        ServerPlayerEntity viewer = Ac.server().getPlayerManager().getPlayer(me);
        if (viewer != null) {
            m.retitle(viewer, title(viewer, theirName, secs));
        }
    }

    /** What each side is worth (trader values), item by item, and whether the trade looks fair. */
    private static ItemStack valueMeter(List<ItemStack> mine, List<ItemStack> theirs, String theirName) {
        double give = 0;
        double get = 0;
        List<String> lines = new ArrayList<>();
        for (ItemStack st : mine) {
            double v = Traders.stackValue(st);
            give += v;
            lines.add("§7" + Msg.tr("trade.you") + ": §f" + st.getCount() + "x " + st.getName().getString() + " §8» §e" + Traders.fmt(v));
        }
        for (ItemStack st : theirs) {
            double v = Traders.stackValue(st);
            get += v;
            lines.add("§7" + theirName + ": §f" + st.getCount() + "x " + st.getName().getString() + " §8» §e" + Traders.fmt(v));
        }
        String verdict;
        int color;
        if (give == 0 && get == 0) {
            verdict = Msg.tr("trade.value.empty");
            color = Theme.SOFT;
        } else if (Math.abs(give - get) <= Math.max(give, get) * 0.15) {
            verdict = "§a" + Msg.tr("trade.value.fair");
            color = Theme.GREEN;
        } else if (give > get) {
            verdict = "§c" + Msg.tr("trade.value.you-give-more", Traders.fmt(give - get));
            color = Theme.RED;
        } else {
            verdict = "§a" + Msg.tr("trade.value.you-get-more", Traders.fmt(get - give));
            color = Theme.GOLD;
        }
        Btn b = Btn.of(Items.GOLD_INGOT).color(color).name(Msg.tr("trade.value.title"))
                .line(Msg.tr("trade.value.give", Traders.fmt(give)))
                .line(Msg.tr("trade.value.get", Traders.fmt(get)))
                .line(verdict);
        for (int i = 0; i < Math.min(14, lines.size()); i++) {
            b.line(lines.get(i));
        }
        return b.build();
    }

    private static ItemStack head(SecureTrade t, UUID who, String name, String label) {
        Btn b = Btn.head(who, name).color(Theme.GOLD_LIGHT).name(label);
        if (t.isConfirmed(who)) {
            b.status(Theme.GREEN, Theme.Sym.CHECK.sp() + Msg.tr("trade.confirmed"));
        } else if (t.isReady(who)) {
            b.status(Theme.GOLD, Theme.Sym.DOT.sp() + Msg.tr("trade.ready"));
        } else {
            b.status(Theme.RED, Theme.Sym.DOT.sp() + Msg.tr("trade.not-ready"));
        }
        return b.build();
    }

    private static void refresh(Session s) {
        s.menuA.refresh();
        s.menuB.refresh();
    }

    /** Items on one side changed: everybody back to not-ready; escrow updated; illegal items bounced. */
    private static void changed(Session s, ServerPlayerEntity p) {
        if (s.finishing) {
            return;
        }
        for (int slot : sideOf(s, p.getUuid())) {
            ItemStack st = s.items.getStack(slot);
            if (!st.isEmpty()) {
                String illegal = IllegalItems.check(ItemConv.info(st), Ac.config().illegalItems.bannedItems, Enchants.MAX_LEVELS);
                if (illegal != null) {
                    s.items.setStack(slot, ItemStack.EMPTY);
                    Illegal.handle(p, st, illegal);
                }
            }
        }
        s.trade.changed();
        saveEscrow(s);
        refresh(s);
    }

    private static List<ItemStack> side(Session s, UUID owner) {
        List<ItemStack> out = new ArrayList<>();
        for (int slot : sideOf(s, owner)) {
            ItemStack st = s.items.getStack(slot);
            if (!st.isEmpty()) {
                out.add(st);
            }
        }
        return out;
    }

    private static void saveEscrow(Session s) {
        for (UUID owner : List.of(s.trade.a(), s.trade.b())) {
            List<String> enc = new ArrayList<>();
            for (ItemStack st : side(s, owner)) {
                enc.add(ItemConv.encode(st));
            }
            Ac.get().setEscrow(owner, "trade", enc);
        }
    }

    /** Every tick: distance, countdown and completion. */
    public static void tick() {
        long now = System.currentTimeMillis();
        for (Session s : new HashSet<>(BY_PLAYER.values())) {
            if (s.trade.isFinished()) {
                continue;
            }
            ServerPlayerEntity a = Ac.server().getPlayerManager().getPlayer(s.trade.a());
            ServerPlayerEntity b = Ac.server().getPlayerManager().getPlayer(s.trade.b());
            if (a == null || b == null) {
                cancel(s, SecureTrade.CancelReason.DISCONNECTED);
                continue;
            }
            if (distanceProblem(a, b) != null) {
                cancel(s, SecureTrade.CancelReason.TOO_FAR);
                continue;
            }
            if (a.isDead() || b.isDead()) {
                cancel(s, SecureTrade.CancelReason.DIED);
                continue;
            }
            int before = s.trade.secondsLeft(now);
            if (s.trade.tick(now)) {
                complete(s, a, b);
            } else if (before >= 0 && refreshTicks++ % 20 == 0) {
                refresh(s);
            }
        }
    }

    private static int refreshTicks;

    /** Counts empty slots and partial stacks to see whether items fit (never drop on the ground). */
    private static boolean fits(ServerPlayerEntity p, List<ItemStack> incoming, List<ItemStack> leaving) {
        var inv = p.getInventory();
        List<ItemStack> main = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            main.add(inv.getStack(i).copy());
        }
        for (ItemStack in : incoming) {
            ItemStack left = in.copy();
            for (ItemStack slot : main) {
                if (left.isEmpty()) {
                    break;
                }
                if (!slot.isEmpty() && ItemStack.areItemsAndComponentsEqual(slot, left) && slot.getCount() < slot.getMaxCount()) {
                    int move = Math.min(left.getCount(), slot.getMaxCount() - slot.getCount());
                    slot.increment(move);
                    left.decrement(move);
                }
            }
            if (!left.isEmpty()) {
                boolean placed = false;
                for (int i = 0; i < main.size(); i++) {
                    if (main.get(i).isEmpty()) {
                        main.set(i, left);
                        placed = true;
                        break;
                    }
                }
                if (!placed) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void complete(Session s, ServerPlayerEntity a, ServerPlayerEntity b) {
        List<ItemStack> fromA = side(s, a.getUuid());
        List<ItemStack> fromB = side(s, b.getUuid());
        if (!fits(a, fromB, fromA) || !fits(b, fromA, fromB)) {
            Msg.send(a, "trade.inventory-full");
            Msg.send(b, "trade.inventory-full");
            s.trade.changed();
            refresh(s);
            return;
        }
        s.finishing = true;
        // Atomic: both sides move in this one step on the server thread.
        for (int slot : SIDE_A) {
            s.items.setStack(slot, ItemStack.EMPTY);
        }
        for (int slot : SIDE_B) {
            s.items.setStack(slot, ItemStack.EMPTY);
        }
        for (ItemStack st : fromB) {
            a.getInventory().insertStack(st.copy());
        }
        for (ItemStack st : fromA) {
            b.getInventory().insertStack(st.copy());
        }
        Ac.get().setEscrow(a.getUuid(), "trade", List.of());
        Ac.get().setEscrow(b.getUuid(), "trade", List.of());
        BY_PLAYER.remove(a.getUuid());
        BY_PLAYER.remove(b.getUuid());
        String detail = describe(fromA) + " <-> " + describe(fromB);
        Ac.get().logs.trade(System.currentTimeMillis(), "player-trade", a.getUuid(), a.getGameProfile().name(),
                b.getUuid(), b.getGameProfile().name(), detail);
        Ac.get().dupeWatch.legitGain(a.getUuid(), Dupes.value(fromB), System.currentTimeMillis());
        Ac.get().dupeWatch.legitGain(b.getUuid(), Dupes.value(fromA), System.currentTimeMillis());
        if (!fromA.isEmpty() && !fromB.isEmpty()) {
            Teams.xpForTrade(a);
            Teams.xpForTrade(b);
        }
        a.closeHandledScreen();
        b.closeHandledScreen();
        Msg.send(a, "trade.done");
        Msg.send(b, "trade.done");
        Mc.sound(a, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
        Mc.sound(b, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
    }

    static String describe(List<ItemStack> items) {
        if (items.isEmpty()) {
            return "nothing";
        }
        List<String> parts = new ArrayList<>();
        for (ItemStack s : items) {
            parts.add(ItemConv.info(s).describe());
        }
        return String.join(", ", parts);
    }

    private static void closedBy(Session s, ServerPlayerEntity p) {
        if (!s.finishing && !s.trade.isFinished()) {
            cancel(s, SecureTrade.CancelReason.CLOSED);
        }
    }

    /** Cancels and returns every item to its owner. */
    public static void cancel(Session s, SecureTrade.CancelReason reason) {
        if (s.finishing || s.trade.isFinished()) {
            return;
        }
        s.trade.cancel(reason);
        s.finishing = true;
        for (UUID owner : List.of(s.trade.a(), s.trade.b())) {
            List<ItemStack> items = side(s, owner);
            for (int slot : sideOf(s, owner)) {
                s.items.setStack(slot, ItemStack.EMPTY);
            }
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(owner);
            if (p != null) {
                giveBack(p, items);
                Ac.get().setEscrow(owner, "trade", List.of());
                Msg.send(p, "trade.cancelled", reason.name().toLowerCase().replace('_', ' '));
                if (p.currentScreenHandler != p.playerScreenHandler) {
                    p.closeHandledScreen();
                }
            }
            // Offline owner: escrow stays saved and is returned at next login.
            BY_PLAYER.remove(owner);
        }
    }

    /** Returns items: inventory first, then ender chest, only then the ground at their feet. */
    public static void giveBack(ServerPlayerEntity p, List<ItemStack> items) {
        for (ItemStack st : items) {
            ItemStack left = st.copy();
            p.getInventory().insertStack(left);
            if (!left.isEmpty()) {
                left = p.getEnderChestInventory().addStack(left);
            }
            if (!left.isEmpty()) {
                p.dropItem(left, false);
            }
        }
    }

    public static void cancelFor(ServerPlayerEntity p, SecureTrade.CancelReason reason) {
        Session s = BY_PLAYER.get(p.getUuid());
        if (s != null) {
            cancel(s, reason);
        }
    }

    public static void onDisconnect(ServerPlayerEntity p) {
        cancelFor(p, SecureTrade.CancelReason.DISCONNECTED);
        REQUESTS.clear(p.getUuid());
    }

    /** A click on the other player's side or a control slot is refused; an odd one gets flagged. */
    static void badClick(ServerPlayerEntity p) {
        PlayerSessionFlags.flag(p, CheckType.BAD_PACKET, 0.5, "trade window click");
    }

    /** A paper icon listing a shulker box's contents (works for Bedrock too, where the tooltip doesn't show it). */
    public static ItemStack contentsIcon(ItemStack box, String title) {
        ContainerComponent c = box.get(DataComponentTypes.CONTAINER);
        ItemStack s = Btn.of(Items.PAPER).color(Theme.GOLD_LIGHT).name(title).build();
        if (c == null) {
            return s;
        }
        List<String> lines = new ArrayList<>();
        int n = 0;
        for (ItemStack inner : c.iterateNonEmpty()) {
            ItemInfo info = ItemConv.info(inner);
            lines.add(info.describe());
            n++;
        }
        if (n == 0) {
            lines.add("§c" + Msg.tr("trade.empty-box"));
        }
        return Icons.withLore(s, lines);
    }
}
