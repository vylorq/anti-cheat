package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.stats.AcStats;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;

/** Anti-cheat statistics (section 28). */
public final class StatsMenu {
    private StatsMenu() {
    }

    public static void open(ServerPlayerEntity admin) {
        Menu m = Menu.std(Category.STAFF, Msg.trFor(admin, "cat.staff"), Msg.trFor(admin, "panel.stats")).perm(Perm.STATS);
        m.renderer(menu -> {
            AcStats st = Ac.get().stats;
            long now = System.currentTimeMillis();
            menu.info(Btn.of(Items.WRITABLE_BOOK).name(Category.STAFF, Msg.tr("panel.stats")).desc(Msg.tr("panel.stats-desc"))
                    .line(Msg.tr("st.caught", st.caught())).line(Msg.tr("panel.cases", Ac.get().reviews.openCount())).build());
            for (int d = 0; d < 7; d++) {
                String day = AcStats.day(now - d * 86_400_000L);
                Btn b = Btn.of(Items.PAPER).name(Category.STAFF, day);
                int total = 0;
                for (Map.Entry<String, Integer> e : st.flagsOn(day).entrySet()) {
                    b.line(e.getKey() + ": " + e.getValue());
                    total += e.getValue();
                }
                b.status(total > 0 ? Theme.GOLD : Theme.SOFT, Theme.Sym.DOT.sp() + Msg.tr("st.flags", total));
                menu.icon(10 + d, b.amount(Math.max(1, Math.min(64, total))).build());
            }
            Btn top = Btn.of(Items.PLAYER_HEAD).name(Category.REVIEW, Msg.tr("st.most-flagged"));
            var mf = st.mostFlagged(10);
            if (mf.isEmpty()) {
                top.line(Msg.tr("rv.none"));
            }
            for (Map.Entry<UUID, Integer> e : mf) {
                top.line(st.nameOf(e.getKey()) + ": " + e.getValue());
            }
            menu.icon(29, top.build());
            Btn dis = Btn.of(Items.LIME_CONCRETE).name(Category.CLAIMS, Msg.tr("st.most-dismissed")).desc(Msg.tr("st.most-dismissed-desc"));
            var md = st.mostDismissed();
            if (md.isEmpty()) {
                dis.line(Msg.tr("st.no-false-flags"));
            }
            for (Map.Entry<String, Integer> e : md) {
                double rate = st.dismissalRate(e.getKey());
                dis.status(rate >= 0.5 ? Theme.RED : Theme.WHITE, (rate >= 0.5 ? Theme.Sym.WARN.sp() : "") + Msg.tr("st.dismissed-line", e.getKey(),
                        e.getValue(), String.format(java.util.Locale.ROOT, "%.0f", rate * 100)));
            }
            menu.set(31, dis.left(Msg.tr("st.open-sensitivity")).build(), Perm.SETTINGS, (p, c) -> SettingsMenu.sensitivity(p));
            menu.icon(33, Btn.of(Items.IRON_BARS).name(Category.PUNISHMENTS, Msg.tr("st.caught-title")).line(Msg.tr("st.caught", st.caught())).build());
        });
        m.open(admin);
    }
}
