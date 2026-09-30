package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Viewer;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/** /language: each player picks English, Arabic, or their game's language (34.14). */
public final class LanguageMenu {
    private LanguageMenu() {
    }

    public static void set(ServerPlayerEntity p, String choice) {
        if (choice.equals("auto")) {
            Ac.get().misc.languages.remove(p.getUuid());
        } else {
            Ac.get().misc.languages.put(p.getUuid(), choice);
        }
        Ac.markDirty("misc");
        Msg.success(p, "language.set", Msg.trFor(p, "lang.name"));
    }

    public static void open(ServerPlayerEntity p) {
        Menu m = Menu.std(Theme.Category.PLAYER, 3, Msg.trFor(p, "language.title"));
        m.renderer(menu -> {
            String chosen = Ac.get().misc.languages.getOrDefault(p.getUuid(), "auto");
            option(menu, 11, Items.COMPASS, "auto", Msg.tr("language.auto"), Msg.tr("language.auto-desc"), chosen);
            option(menu, 13, Items.WHITE_BANNER, "en_us", "English", "English", chosen);
            option(menu, 15, Items.GREEN_BANNER, "ar_sa", "العربية", Viewer.isBedrock(p) ? Msg.tr("language.arabic-bedrock") : "Arabic", chosen);
        });
        m.open(p);
    }

    private static void option(Menu menu, int slot, net.minecraft.item.Item icon, String id, String name, String desc, String chosen) {
        boolean on = id.equals(chosen);
        Btn b = Btn.of(icon).color(on ? Theme.GOLD_LIGHT : Theme.WHITE).name(name).desc(desc);
        if (on) {
            b.status(Theme.GREEN, Theme.Sym.CHECK.sp() + Msg.tr("language.current"));
        }
        menu.set(slot, b.left(Msg.tr("language.choose")).glint(on).build(), null, (p, c) -> {
            set(p, id);
            menu.refresh();
        });
    }
}
