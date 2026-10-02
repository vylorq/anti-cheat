package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Features;
import com.vylorq.anticheat.feature.Features.Feature;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Sounds;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /features: every system with an on/off switch, filtered by kind. */
public final class FeaturesMenu {
    private FeaturesMenu() {
    }

    public static void toggle(ServerPlayerEntity by, Feature f, boolean on) {
        Features.set(f, on);
        Staff.log(by, on ? "feature-on" : "feature-off", null, f.id, "");
        Msg.send(by, on ? "features.turned-on" : "features.turned-off", Msg.trFor(by, "feature." + f.id));
    }

    public static void open(ServerPlayerEntity admin) {
        if (!Perms.require(admin, Perm.SETTINGS)) {
            return;
        }
        Menu m = Menu.std(Category.SETTINGS, Msg.trFor(admin, "cat.settings"), Msg.trFor(admin, "features.title")).perm(Perm.SETTINGS);
        m.renderer(menu -> {
            long off = 0;
            for (Feature f : Feature.values()) {
                if (!Features.on(f)) {
                    off++;
                }
            }
            menu.info(Btn.of(Items.LEVER).name(Category.SETTINGS, Msg.tr("features.title")).desc(Msg.tr("features.desc"))
                    .line(Msg.tr("features.count", Feature.values().length - off, off)).build());
            List<Menu.Filter<Feature>> filters = new ArrayList<>();
            filters.add(Menu.Filter.of(Msg.tr("features.group.all"), f -> true));
            for (Features.Group g : Features.Group.values()) {
                filters.add(Menu.Filter.of(Msg.tr("features.group." + g.name().toLowerCase(Locale.ROOT)), f -> f.group == g));
            }
            filters.add(Menu.Filter.of(Msg.tr("features.group.off"), f -> !Features.on(f)));
            menu.list(List.of(Feature.values()), f -> {
                boolean on = Features.on(f);
                Btn b = Btn.of(on ? f.icon : Items.GRAY_DYE).color(on ? Theme.GREEN : Theme.SOFT)
                        .name(Msg.tr("feature." + f.id)).desc(Msg.tr("feature." + f.id + ".desc"))
                        .line(Msg.tr("features.group." + f.group.name().toLowerCase(Locale.ROOT)))
                        .onOff(on);
                if (!f.commands.isEmpty()) {
                    b.line(Msg.tr("features.commands", "/" + String.join(" /", f.commands)));
                }
                return b.left(Msg.tr(on ? "ui.action.turn-off" : "ui.action.turn-on")).build();
            }, f -> (pl, c) -> {
                toggle(pl, f, !Features.on(f));
                Sounds.play(pl, Sounds.Ui.SUCCESS);
                menu.refresh();
            }, f -> Msg.tr("feature." + f.id) + " " + f.id, filters, Msg.tr("features.none"), "");
        });
        m.open(admin);
    }
}
