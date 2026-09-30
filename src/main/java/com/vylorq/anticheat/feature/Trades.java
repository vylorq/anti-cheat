package com.vylorq.anticheat.feature;

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
    private static final int[] SIDE_A = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    private static final int[] SIDE_B = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};

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
        Menu ma = new Menu(Msg.tr("trade.title", b.getGameProfile().name()), 6);
        Menu mb = new Menu(Msg.tr("trade.title", a.getGameProfile().name()), 6);
        Session s = new Session(t, ma, mb);
        ma.backedBy(s.items).allowPlayerInventory(true);
        mb.backedBy(s.items).allowPlayerInventory(true);
        ma.editable(set(SIDE_A), (p, slot) -> changed(s, p)).reserved(set(SIDE_B));
        mb.editable(set(SIDE_B), (p, slot) -> changed(s, p)).reserved(set(SIDE_A));
        // Both players look at the same inventory, so the controls are identical for both.
        ma.renderer(m -> render(s, m));
        mb.renderer(m -> render(s, m));
        ma.onClose(p -> closedBy(s, p));
        mb.onClose(p -> closedBy(s, p));
        BY_PLAYER.put(a.getUuid(), s);
        BY_PLAYER.put(b.getUuid(), s);
        ma.open(a);
        mb.open(b);
        Ac.get().logs.trade(System.currentTimeMillis(), "player-trade-open", a.getUuid(), a.getGameProfile().name(),
                b.getUuid(), b.getGameProfile().name(), "");
    }

    private static void render(Session s, Menu m) {
        SecureTrade t = s.trade;
        ServerPlayerEntity a = Ac.server().getPlayerManager().getPlayer(t.a());
        ServerPlayerEntity b = Ac.server().getPlayerManager().getPlayer(t.b());
        String an = a == null ? "?" : a.getGameProfile().name();
        String bn = b == null ? "?" : b.getGameProfile().name();
        for (int r = 0; r < 4; r++) {
            m.icon(r * 9 + 4, Icons.of(Items.IRON_BARS, "§7« " + an + " §8| §7" + bn + " »"));
        }
        for (int i = 36; i < 54; i++) {
            m.icon(i, Icons.filler());
        }
        m.icon(36, status(t, t.a(), an));
        m.icon(44, status(t, t.b(), bn));
        // Shulker boxes: show what's inside so nobody gets scammed with an empty box.
        int preview = 37;
        for (UUID owner : List.of(t.a(), t.b())) {
            for (ItemStack st : side(s, owner)) {
                if (preview > 43) {
                    break;
                }
                if (st.contains(DataComponentTypes.CONTAINER)) {
                    String who = owner.equals(t.a()) ? an : bn;
                    m.icon(preview++, contentsIcon(st, "§eContents of " + who + "'s " + st.getName().getString()));
                }
            }
        }
        int seen = t.revision();
        if (t.step() == SecureTrade.Step.OPEN || t.step() == SecureTrade.Step.BOTH_READY) {
            m.set(48, Icons.of(Items.LIME_CONCRETE, "§aReady / not ready", "Click to toggle your ready state"),
                    (p, c) -> {
                        s.trade.toggleReady(p.getUuid());
                        refresh(s);
                    });
        }
        if (t.step() == SecureTrade.Step.BOTH_READY) {
            m.set(50, Icons.of(Items.EMERALD, "§aConfirm trade", "Both players must confirm"),
                    (p, c) -> {
                        if (!s.trade.confirm(p.getUuid(), seen, System.currentTimeMillis())) {
                            Msg.send(p, "trade.changed");
                        }
                        refresh(s);
                    });
        }
        int secs = t.secondsLeft(System.currentTimeMillis());
        if (secs >= 0) {
            m.icon(49, Icons.of(Items.CLOCK, "§6Trading in " + secs + "...", "Any change cancels the countdown"));
        }
        m.set(53, Icons.of(Items.BARRIER, "§cCancel trade"), (p, c) -> cancel(s, SecureTrade.CancelReason.CLOSED));
    }

    private static ItemStack status(SecureTrade t, UUID who, String name) {
        if (t.isConfirmed(who)) {
            return Icons.of(Items.EMERALD_BLOCK, "§a" + name + ": confirmed");
        }
        if (t.isReady(who)) {
            return Icons.of(Items.LIME_WOOL, "§a" + name + ": ready");
        }
        return Icons.of(Items.RED_WOOL, "§c" + name + ": not ready");
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
        ItemStack s = Icons.of(Items.PAPER, title);
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
            lines.add("§c(empty)");
        }
        return Icons.withLore(s, lines);
    }
}
