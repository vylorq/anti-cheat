package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.stats.AcStats;
import com.vylorq.anticheat.util.Icons;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Anti-cheat statistics (section 28). */
public final class StatsMenu {
    private StatsMenu() {
    }

    public static void open(ServerPlayerEntity admin) {
        Menu m = new Menu("§8Anti-cheat stats", 6).perm(Perm.STATS);
        m.renderer(menu -> {
            AcStats st = Ac.get().stats;
            long now = System.currentTimeMillis();
            for (int d = 0; d < 7; d++) {
                String day = AcStats.day(now - d * 86_400_000L);
                List<String> lines = new ArrayList<>();
                int total = 0;
                for (Map.Entry<String, Integer> e : st.flagsOn(day).entrySet()) {
                    lines.add("§7" + e.getKey() + ": §f" + e.getValue());
                    total += e.getValue();
                }
                menu.icon(10 + d, Icons.of(Items.PAPER, "§e" + day + " §7(" + total + " flags)", lines.isEmpty() ? List.of("§7None") : lines));
            }
            List<String> top = new ArrayList<>();
            for (Map.Entry<UUID, Integer> e : st.mostFlagged(10)) {
                top.add("§7" + st.nameOf(e.getKey()) + ": §f" + e.getValue());
            }
            menu.icon(28, Icons.of(Items.PLAYER_HEAD, "§cMost-flagged players", top.isEmpty() ? List.of("§7None") : top));
            List<String> dis = new ArrayList<>();
            for (Map.Entry<String, Integer> e : st.mostDismissed()) {
                double rate = st.dismissalRate(e.getKey());
                dis.add((rate >= 0.5 ? "§c⚠ " : "§7") + e.getKey() + ": §f" + e.getValue() + String.format(" dismissed (%.0f%%)", rate * 100));
            }
            menu.icon(30, Icons.of(Items.LIME_CONCRETE, "§aMost dismissed checks", dis.isEmpty() ? List.of("§7None - no false flags reported")
                    : withHint(dis)));
            menu.icon(32, Icons.of(Items.IRON_BARS, "§6Cheaters caught: §f" + st.caught()));
            menu.icon(34, Icons.of(Items.BOOK, "§eOpen review cases: §f" + Ac.get().reviews.openCount()));
        });
        m.open(admin);
    }

    private static List<String> withHint(List<String> l) {
        List<String> out = new ArrayList<>(l);
        out.add("");
        out.add("§7Checks marked §c⚠ §7are often dismissed:");
        out.add("§7consider lowering their sensitivity in /settings.");
        return out;
    }
}
