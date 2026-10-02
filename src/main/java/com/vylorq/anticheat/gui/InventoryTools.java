package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.command.Args;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.Trades;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Staff inventory tools: reset a player's whole inventory, or pick item types and delete just those. Works on
 * offline players too. The last wipe can be undone. Everything goes in the staff log and the player is told.
 */
public final class InventoryTools {
    private InventoryTools() {
    }

    /** The player's inventories: live if online, else loaded from their save file. */
    public record Invs(Inventory inv, Inventory ender, OfflineInventory offline, ServerPlayerEntity online) {
        void save() {
            if (offline != null) {
                offline.save();
            } else {
                inv.markDirty();
                ender.markDirty();
            }
        }
    }

    static Invs get(UUID target) {
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(target);
        if (p != null) {
            return new Invs(p.getInventory(), p.getEnderChestInventory(), null, p);
        }
        OfflineInventory off = OfflineInventory.load(target);
        return off == null ? null : new Invs(off.inventory, off.ender, off, null);
    }

    /** The inventories of a player in front of us (also used by tests). */
    public static Invs of(ServerPlayerEntity p) {
        return new Invs(p.getInventory(), p.getEnderChestInventory(), null, p);
    }

    static String id(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).toString();
    }

    static String name(UUID target) {
        return Args.nameOf(target, target.toString().substring(0, 8));
    }

    public static void open(ServerPlayerEntity admin, UUID target) {
        if (!Perms.require(admin, Perm.INSPECT_EDIT)) {
            return;
        }
        if (get(target) == null) {
            Msg.send(admin, "inspect.no-data");
            return;
        }
        Set<String> selected = new LinkedHashSet<>();
        boolean[] ender = {false};
        Menu m = Menu.std(Category.PLAYERS, Msg.trFor(admin, "cat.players"), name(target), Msg.trFor(admin, "invtools.title")).perm(Perm.INSPECT_EDIT);
        m.renderer(menu -> {
            Invs i = get(target);
            if (i == null) {
                return;
            }
            // Every kind of item they have, with how many in the inventory and in the ender chest.
            Map<String, int[]> counts = new LinkedHashMap<>();
            Map<String, ItemStack> samples = new LinkedHashMap<>();
            for (int s = 0; s < i.inv().size(); s++) {
                ItemStack st = i.inv().getStack(s);
                if (!st.isEmpty()) {
                    counts.computeIfAbsent(id(st), k -> new int[2])[0] += st.getCount();
                    samples.putIfAbsent(id(st), st);
                }
            }
            for (int s = 0; s < i.ender().size(); s++) {
                ItemStack st = i.ender().getStack(s);
                if (!st.isEmpty()) {
                    counts.computeIfAbsent(id(st), k -> new int[2])[1] += st.getCount();
                    samples.putIfAbsent(id(st), st);
                }
            }
            selected.retainAll(counts.keySet());
            List<String> ids = new ArrayList<>(counts.keySet());
            ids.sort((a, b) -> Integer.compare(counts.get(b)[0] + counts.get(b)[1], counts.get(a)[0] + counts.get(a)[1]));
            menu.info(Btn.head(target, name(target)).name(Category.PLAYERS, Msg.tr("invtools.title"))
                    .desc(Msg.tr("invtools.desc")).line(Msg.tr(i.online() != null ? "ui.online" : "ui.offline")).build());
            menu.list(ids, id -> {
                ItemStack s = samples.get(id).copyWithCount(1);
                boolean sel = selected.contains(id);
                int[] c = counts.get(id);
                Btn b = Btn.of(s).color(sel ? Theme.RED : Theme.WHITE).name(s.getName().getString())
                        .line(Msg.tr("invtools.in-inv", c[0])).line(Msg.tr("invtools.in-ender", c[1]));
                b.status(sel ? Theme.RED : Theme.SOFT, sel ? Theme.Sym.CROSS.sp() + Msg.tr("invtools.marked") : Msg.tr("invtools.not-marked"));
                return b.left(Msg.tr(sel ? "invtools.unmark" : "invtools.mark")).glint(sel).amount(Math.min(64, Math.max(1, c[0] + c[1]))).build();
            }, id -> (pl, c) -> {
                if (!selected.remove(id)) {
                    selected.add(id);
                }
                menu.refresh();
            }, id -> id, List.of(), Msg.tr("invtools.empty"), Msg.tr("invtools.empty-hint"));
            menu.set(46, Btn.of(Items.ENDER_CHEST).name(Category.PLAYERS, Msg.tr("invtools.include-ender")).desc(Msg.tr("invtools.include-ender-desc"))
                    .onOff(ender[0]).left(Msg.tr(ender[0] ? "ui.action.turn-off" : "ui.action.turn-on")).build(), null, (pl, c) -> {
                ender[0] = !ender[0];
                menu.refresh();
            });
            menu.set(48, Btn.of(selected.isEmpty() ? Items.GRAY_DYE : Items.LAVA_BUCKET).color(selected.isEmpty() ? Theme.SOFT : Theme.RED)
                    .name(Msg.tr("invtools.delete-marked", selected.size())).desc(Msg.tr("invtools.delete-marked-desc"))
                    .left(Msg.tr("panel.action.delete")).build(), null, (pl, c) -> {
                if (selected.isEmpty()) {
                    Msg.send(pl, "invtools.none-marked");
                    return;
                }
                Confirm.open(pl, Category.PLAYERS, Msg.trFor(pl, "invtools.confirm-delete", selected.size(), name(target)),
                        Msg.trFor(pl, ender[0] ? "invtools.with-ender" : "invtools.without-ender"), new ItemStack(Items.LAVA_BUCKET), () -> {
                            delete(pl, target, new LinkedHashSet<>(selected), ender[0]);
                            selected.clear();
                            menu.reopen(pl);
                        });
            });
            menu.set(50, Btn.of(Items.TNT).color(Theme.RED).name(Msg.tr("invtools.reset")).desc(Msg.tr("invtools.reset-desc"))
                    .left(Msg.tr("invtools.reset-action")).build(), null,
                    (pl, c) -> Confirm.open(pl, Category.PUNISHMENTS, Msg.trFor(pl, "invtools.confirm-reset", name(target)),
                            Msg.trFor(pl, ender[0] ? "invtools.with-ender" : "invtools.without-ender"), new ItemStack(Items.TNT), () -> {
                                reset(pl, target, ender[0]);
                                menu.reopen(pl);
                            }));
            boolean canUndo = Ac.get().misc.inventoryBackups.containsKey(target);
            menu.set(52, Btn.of(canUndo ? Items.CLOCK : Items.GRAY_DYE).color(canUndo ? Theme.GOLD_LIGHT : Theme.SOFT)
                    .name(Msg.tr("invtools.undo")).desc(Msg.tr(canUndo ? "invtools.undo-desc" : "invtools.undo-none")).left(Msg.tr("invtools.undo-action"))
                    .build(), null, (pl, c) -> {
                undo(pl, target);
                menu.refresh();
            });
        });
        m.open(admin);
    }

    /** Saves everything (inventory + ender chest) so the next wipe can be undone. */
    static void backup(UUID target, Invs i) {
        List<String> saved = new ArrayList<>();
        for (int s = 0; s < i.inv().size(); s++) {
            if (!i.inv().getStack(s).isEmpty()) {
                saved.add(ItemConv.encodeSlot(s, i.inv().getStack(s)));
            }
        }
        for (int s = 0; s < i.ender().size(); s++) {
            if (!i.ender().getStack(s).isEmpty()) {
                saved.add(ItemConv.encodeSlot(1000 + s, i.ender().getStack(s)));
            }
        }
        Ac.get().misc.inventoryBackups.put(target, saved);
        Ac.markDirty("misc");
    }

    static int removeFrom(Inventory inv, Set<String> ids) {
        int n = 0;
        for (int s = 0; s < inv.size(); s++) {
            ItemStack st = inv.getStack(s);
            if (!st.isEmpty() && (ids == null || ids.contains(id(st)))) {
                n += st.getCount();
                inv.setStack(s, ItemStack.EMPTY);
            }
        }
        return n;
    }

    /** Deletes every item of the chosen kinds. */
    public static void delete(ServerPlayerEntity admin, UUID target, Set<String> ids, boolean ender) {
        delete(admin, target, get(target), ids, ender);
    }

    public static void delete(ServerPlayerEntity admin, UUID target, Invs i, Set<String> ids, boolean ender) {
        if (i == null) {
            Msg.send(admin, "inspect.no-data");
            return;
        }
        backup(target, i);
        int n = removeFrom(i.inv(), ids) + (ender ? removeFrom(i.ender(), ids) : 0);
        i.save();
        Msg.send(admin, "invtools.deleted", n, name(target));
        Staff.log(admin, "inventory-delete", target, name(target), n + " items: " + String.join(", ", ids) + (ender ? " (+ender)" : ""));
        if (i.online() != null) {
            Msg.send(i.online(), "invtools.told-delete");
        }
    }

    /** Empties the whole inventory (armour and offhand too), and the ender chest if asked. */
    public static void reset(ServerPlayerEntity admin, UUID target, boolean ender) {
        reset(admin, target, get(target), ender);
    }

    public static void reset(ServerPlayerEntity admin, UUID target, Invs i, boolean ender) {
        if (i == null) {
            Msg.send(admin, "inspect.no-data");
            return;
        }
        backup(target, i);
        int n = removeFrom(i.inv(), null) + (ender ? removeFrom(i.ender(), null) : 0);
        i.save();
        Msg.send(admin, "invtools.was-reset", name(target), n);
        Staff.log(admin, "inventory-reset", target, name(target), n + " items" + (ender ? " (+ender)" : ""));
        if (i.online() != null) {
            Msg.send(i.online(), "invtools.told-reset");
        }
    }

    /** Puts back what the last wipe removed (into empty slots; the rest goes in the inventory or at their feet). */
    public static void undo(ServerPlayerEntity admin, UUID target) {
        undo(admin, target, get(target));
    }

    public static void undo(ServerPlayerEntity admin, UUID target, Invs i) {
        List<String> saved = Ac.get().misc.inventoryBackups.get(target);
        if (saved == null) {
            Msg.send(admin, "invtools.undo-none");
            return;
        }
        if (i == null) {
            Msg.send(admin, "inspect.no-data");
            return;
        }
        List<String> leftover = new ArrayList<>();
        List<ItemStack> extra = new ArrayList<>();
        int n = 0;
        for (String e : saved) {
            int slot = ItemConv.slotOf(e);
            ItemStack st = ItemConv.decodeSlot(e);
            Inventory inv = slot >= 1000 ? i.ender() : i.inv();
            int s = slot >= 1000 ? slot - 1000 : slot;
            if (s >= 0 && s < inv.size() && inv.getStack(s).isEmpty()) {
                inv.setStack(s, st);
                n += st.getCount();
            } else if (i.online() != null) {
                extra.add(st);
                n += st.getCount();
            } else {
                leftover.add(e);
            }
        }
        if (!extra.isEmpty()) {
            Trades.giveBack(i.online(), extra);
        }
        i.save();
        if (leftover.isEmpty()) {
            Ac.get().misc.inventoryBackups.remove(target);
        } else {
            Ac.get().misc.inventoryBackups.put(target, leftover);
        }
        Ac.markDirty("misc");
        Msg.send(admin, leftover.isEmpty() ? "invtools.undone" : "invtools.undone-part", n, name(target));
        Staff.log(admin, "inventory-undo", target, name(target), n + " items");
        if (i.online() != null) {
            Msg.send(i.online(), "invtools.told-undo");
        }
    }
}
