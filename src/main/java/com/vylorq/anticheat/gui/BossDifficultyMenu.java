package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.feature.Bosses;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/** Before a boss wakes: Normal, Hard or Nightmare (harder fights, better loot). */
public final class BossDifficultyMenu {
    private BossDifficultyMenu() {
    }

    public static void open(ServerPlayerEntity p, String key, String bossName) {
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "boss.difficulty.title"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.WITHER_SKELETON_SKULL).name(bossName).desc(Msg.tr("boss.difficulty.desc")).build());
            menu.set(20, Btn.of(Items.IRON_SWORD).color(Theme.GREEN).name(Msg.tr("boss.difficulty.0"))
                    .desc(Msg.tr("boss.difficulty.0.desc")).left(Msg.tr("boss.difficulty.pick")).build(), null, (pl, c) -> pick(pl, key, 0));
            menu.set(22, Btn.of(Items.DIAMOND_SWORD).color(Theme.GOLD_LIGHT).name(Msg.tr("boss.difficulty.1"))
                    .desc(Msg.tr("boss.difficulty.1.desc")).left(Msg.tr("boss.difficulty.pick")).build(), null, (pl, c) -> pick(pl, key, 1));
            menu.set(24, Btn.of(Items.NETHERITE_SWORD).color(Theme.RED).name(Msg.tr("boss.difficulty.2"))
                    .desc(Msg.tr("boss.difficulty.2.desc")).left(Msg.tr("boss.difficulty.pick")).glint(true).build(), null,
                    (pl, c) -> pick(pl, key, 2));
        });
        m.open(p);
    }

    private static void pick(ServerPlayerEntity p, String key, int d) {
        Bosses.chooseDifficulty(p, key, d);
        p.closeHandledScreen();
    }
}
