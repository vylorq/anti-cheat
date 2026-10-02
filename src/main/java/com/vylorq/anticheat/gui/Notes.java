package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Private staff notes on a player ("warned for spam", "trusted builder"...). Players never see them. */
public final class Notes {
    private Notes() {
    }

    public static final class Note {
        public long time;
        public String by;
        public String text;
    }

    public static List<Note> of(UUID player) {
        return Ac.get().misc.notes.getOrDefault(player, List.of());
    }

    public static void add(ServerPlayerEntity by, UUID player, String text) {
        Note n = new Note();
        n.time = System.currentTimeMillis();
        n.by = by == null ? "Console" : by.getGameProfile().name();
        n.text = text.length() > 200 ? text.substring(0, 200) : text;
        Ac.get().misc.notes.computeIfAbsent(player, k -> new ArrayList<>()).add(0, n);
        Ac.markDirty("misc");
        Staff.log(by, "note-add", player, InventoryTools.name(player), n.text);
    }

    public static void remove(ServerPlayerEntity by, UUID player, Note n) {
        List<Note> l = Ac.get().misc.notes.get(player);
        if (l != null && l.remove(n)) {
            if (l.isEmpty()) {
                Ac.get().misc.notes.remove(player);
            }
            Ac.markDirty("misc");
            Staff.log(by, "note-remove", player, InventoryTools.name(player), n.text);
        }
    }

    static String date(long t) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(t));
    }

    public static void open(ServerPlayerEntity admin, UUID player) {
        if (!Perms.require(admin, Perm.INSPECT)) {
            return;
        }
        Menu m = Menu.std(Category.PLAYERS, Msg.trFor(admin, "cat.players"), InventoryTools.name(player), Msg.trFor(admin, "notes.title"))
                .perm(Perm.INSPECT);
        m.renderer(menu -> {
            List<Note> all = new ArrayList<>(of(player));
            menu.info(Btn.head(player, InventoryTools.name(player)).name(Category.PLAYERS, Msg.tr("notes.title")).desc(Msg.tr("notes.desc"))
                    .line(Msg.tr("notes.count", all.size())).build());
            menu.list(all, n -> Btn.of(Items.PAPER).color(Theme.WHITE).name("§f" + n.text)
                    .line(Msg.tr("notes.by", n.by, date(n.time))).shift(Msg.tr("panel.action.delete")).build(), n -> (pl, c) -> {
                if (c.isShift()) {
                    Confirm.open(pl, Category.PLAYERS, Msg.trFor(pl, "notes.confirm-delete"), n.text, new ItemStack(Items.PAPER), () -> {
                        remove(pl, player, n);
                        menu.reopen(pl);
                    });
                }
            }, n -> n.text + " " + n.by, List.of(), Msg.tr("notes.none"), Msg.tr("notes.none-hint"));
            menu.set(48, Btn.of(Items.WRITABLE_BOOK).color(Theme.GREEN).name(Msg.tr("notes.add")).left(Msg.tr("notes.add-action")).build(), null,
                    (pl, c) -> Input.text(pl, Msg.trFor(pl, "notes.add"), "", txt -> {
                        if (txt != null && !txt.isBlank()) {
                            add(pl, player, txt.trim());
                            Msg.send(pl, "notes.added", InventoryTools.name(player));
                        }
                        open(pl, player);
                    }));
        });
        m.open(admin);
    }
}
