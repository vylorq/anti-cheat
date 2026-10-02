package com.vylorq.anticheat.gui;

import com.google.gson.reflect.TypeToken;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Inventory snapshots: a saved copy of a player's inventory and ender chest, taken by staff or automatically,
 * that can be looked at and restored later. Kept as one small file per player.
 */
public final class Snapshots {
    private Snapshots() {
    }

    public static final class Snap {
        public long time;
        public String by;
        public String note;
        public boolean auto;
        public int items;
        public List<String> inv = new ArrayList<>();
        public List<String> ender = new ArrayList<>();
    }

    static Path file(UUID player) {
        return Ac.get().dir.resolve("snapshots").resolve(player + ".json");
    }

    public static List<Snap> list(UUID player) {
        Path f = file(player);
        if (!Files.exists(f)) {
            return new ArrayList<>();
        }
        try {
            List<Snap> l = ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Snap>>() { }.getType());
            return l == null ? new ArrayList<>() : l;
        } catch (Exception e) {
            Ac.LOG.warn("Could not read snapshots of {}", player, e);
            return new ArrayList<>();
        }
    }

    static void write(UUID player, List<Snap> all) {
        try {
            Path f = file(player);
            Files.createDirectories(f.getParent());
            Files.writeString(f, ConfigManager.GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save snapshots of {}", player, e);
        }
    }

    static List<String> encode(Inventory inv) {
        List<String> out = new ArrayList<>();
        for (int s = 0; s < inv.size(); s++) {
            if (!inv.getStack(s).isEmpty()) {
                out.add(ItemConv.encodeSlot(s, inv.getStack(s)));
            }
        }
        return out;
    }

    /** Takes a snapshot now. Keeps the newest {@code keep} (automatic ones are dropped first). */
    public static Snap take(UUID player, InventoryTools.Invs i, String by, String note, boolean auto) {
        Snap s = new Snap();
        s.time = System.currentTimeMillis();
        s.by = by;
        s.note = note == null ? "" : note;
        s.auto = auto;
        s.inv = encode(i.inv());
        s.ender = encode(i.ender());
        s.items = s.inv.size() + s.ender.size();
        List<Snap> all = list(player);
        all.add(0, s);
        int keep = Math.max(1, Ac.config().staff.snapshotsKept);
        while (all.size() > keep) {
            int idx = -1;
            for (int k = all.size() - 1; k >= 0; k--) {
                if (all.get(k).auto) {
                    idx = k;
                    break;
                }
            }
            all.remove(idx >= 0 ? idx : all.size() - 1);
        }
        write(player, all);
        return s;
    }

    /** Replaces the inventory and ender chest with a snapshot (the current ones are snapshotted first). */
    public static void restore(ServerPlayerEntity admin, UUID player, InventoryTools.Invs i, Snap s) {
        take(player, i, admin == null ? "server" : admin.getGameProfile().name(), Msg.tr("snap.before-restore"), false);
        i.inv().clear();
        i.ender().clear();
        for (String e : s.inv) {
            int slot = ItemConv.slotOf(e);
            if (slot >= 0 && slot < i.inv().size()) {
                i.inv().setStack(slot, ItemConv.decodeSlot(e));
            }
        }
        for (String e : s.ender) {
            int slot = ItemConv.slotOf(e);
            if (slot >= 0 && slot < i.ender().size()) {
                i.ender().setStack(slot, ItemConv.decodeSlot(e));
            }
        }
        i.save();
        if (admin != null) {
            Staff.log(admin, "snapshot-restore", player, InventoryTools.name(player), date(s.time));
        }
        if (i.online() != null) {
            Msg.send(i.online(), "snap.told-restore");
        }
    }

    static String date(long t) {
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(t));
    }

    private static long lastAuto;

    /** Every minute: automatic snapshots of everyone online, every configured number of minutes. */
    public static void tickMinute() {
        int every = Ac.config().staff.autoSnapshotMinutes;
        long now = System.currentTimeMillis();
        if (every <= 0 || now - lastAuto < every * 60_000L) {
            return;
        }
        lastAuto = now;
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            take(p.getUuid(), InventoryTools.of(p), "auto", "", true);
        }
    }

    // ---------------------------------------------------------------- menus

    public static void open(ServerPlayerEntity admin, UUID player) {
        if (!Perms.require(admin, Perm.INSPECT_EDIT)) {
            return;
        }
        Menu m = Menu.std(Category.PLAYERS, Msg.trFor(admin, "cat.players"), InventoryTools.name(player), Msg.trFor(admin, "snap.title"))
                .perm(Perm.INSPECT_EDIT);
        m.renderer(menu -> {
            List<Snap> all = list(player);
            menu.info(Btn.head(player, InventoryTools.name(player)).name(Category.PLAYERS, Msg.tr("snap.title")).desc(Msg.tr("snap.desc"))
                    .line(Msg.tr("snap.count", all.size())).build());
            List<Integer> idx = new ArrayList<>();
            for (int k = 0; k < all.size(); k++) {
                idx.add(k);
            }
            menu.list(idx, k -> {
                Snap s = all.get(k);
                return Btn.of(s.auto ? Items.CLOCK : Items.CHEST).color(s.auto ? Theme.SOFT : Theme.GOLD_LIGHT)
                        .name(date(s.time) + (s.note.isEmpty() ? "" : " · " + s.note))
                        .line(Msg.tr("snap.by", s.by)).line(Msg.tr("snap.items", s.items))
                        .left(Msg.tr("snap.view")).right(Msg.tr("snap.restore")).shift(Msg.tr("panel.action.delete")).build();
            }, k -> (pl, c) -> {
                Snap s = all.get(k);
                if (c.isShift()) {
                    List<Snap> now = list(player);
                    now.removeIf(x -> x.time == s.time);
                    write(player, now);
                    menu.refresh();
                } else if (c.isRight()) {
                    Confirm.open(pl, Category.PLAYERS, Msg.trFor(pl, "snap.confirm-restore", date(s.time)), Msg.trFor(pl, "snap.confirm-restore-detail"),
                            new ItemStack(Items.CHEST), () -> {
                                InventoryTools.Invs i = InventoryTools.get(player);
                                if (i == null) {
                                    Msg.send(pl, "inspect.no-data");
                                    return;
                                }
                                restore(pl, player, i, s);
                                Msg.send(pl, "snap.restored", InventoryTools.name(player), date(s.time));
                                menu.reopen(pl);
                            });
                } else {
                    view(pl, player, s, menu);
                }
            }, k -> all.get(k).note, List.of(), Msg.tr("snap.none"), Msg.tr("snap.none-hint"));
            menu.set(48, Btn.of(Items.WRITABLE_BOOK).color(Theme.GREEN).name(Msg.tr("snap.take")).desc(Msg.tr("snap.take-desc"))
                    .left(Msg.tr("snap.take-action")).build(), null, (pl, c) -> Input.text(pl, Msg.trFor(pl, "snap.note"), "", note -> {
                InventoryTools.Invs i = InventoryTools.get(player);
                if (i == null) {
                    Msg.send(pl, "inspect.no-data");
                    return;
                }
                take(player, i, pl.getGameProfile().name(), note == null ? "" : note.trim(), false);
                Staff.log(pl, "snapshot", player, InventoryTools.name(player), note == null ? "" : note);
                Msg.send(pl, "snap.taken", InventoryTools.name(player));
                open(pl, player);
            }));
        });
        m.open(admin);
    }

    /** Read-only look at what a snapshot holds (inventory, then ender chest). */
    static void view(ServerPlayerEntity admin, UUID player, Snap s, Menu parent) {
        Menu m = Menu.std(Category.PLAYERS, InventoryTools.name(player), date(s.time));
        m.parent(parent);
        m.renderer(menu -> {
            List<ItemStack> items = new ArrayList<>();
            for (String e : s.inv) {
                items.add(ItemConv.decodeSlot(e));
            }
            for (String e : s.ender) {
                ItemStack st = ItemConv.decodeSlot(e);
                st.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(
                        List.of(net.minecraft.text.Text.literal("§5" + Msg.tr("invtools.in-ender", st.getCount())))));
                items.add(st);
            }
            menu.list(items, st -> st, null, null, List.of(), Msg.tr("invtools.empty"), "");
        });
        m.open(admin);
    }
}
