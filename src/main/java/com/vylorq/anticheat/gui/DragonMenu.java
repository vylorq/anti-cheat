package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.feature.Dragons;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/** Your dragon: how it's doing, feed it, call it or send it home, and what it can do at each stage. */
public final class DragonMenu {
    private DragonMenu() {
    }

    private static String bar(int value, int max) {
        int filled = max <= 0 ? 10 : Math.max(0, Math.min(10, value * 10 / max));
        return "§a" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled);
    }

    public static void open(ServerPlayerEntity p) {
        Dragons.Dragon d = Dragons.of(p);
        if (d == null) {
            Msg.send(p, "dragon.none");
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "dragon.title"));
        m.renderer(menu -> {
            Dragons.Dragon dd = Dragons.of(p);
            if (dd == null) {
                return;
            }
            boolean egg = dd.stage.equals("egg");
            Btn info = Btn.of(egg ? Items.FIREWORK_STAR : Items.DRAGON_HEAD).color(0xE5534B).name("§c" + dd.name)
                    .line(Msg.trFor(p, "dragon.stage-line", Dragons.stageName(p, dd)));
            if (egg) {
                info.line(Msg.trFor(p, "dragon.warmth-line", bar(dd.warmth, Dragons.WARMTH), dd.warmth, Dragons.WARMTH));
            } else {
                info.line(Msg.trFor(p, "dragon.hunger-line", bar(dd.hunger, 100), dd.hunger));
                int next = Dragons.nextAt(dd);
                info.line(next > 0 ? Msg.trFor(p, "dragon.growth-line", bar(dd.growth, next), dd.growth, next) : Msg.trFor(p, "dragon.grown"));
            }
            menu.info(info.build());
            menu.set(20, Btn.of(egg ? Items.BLAZE_POWDER : Items.COOKED_BEEF).color(Theme.GREEN)
                    .name(Msg.trFor(p, egg ? "dragon.btn-warm" : "dragon.btn-feed"))
                    .desc(Msg.trFor(p, egg ? "dragon.btn-warm-desc" : "dragon.btn-feed-desc")).left(Msg.trFor(p, "dragon.click")).build(),
                    null, (pl, c) -> {
                        Dragons.feed(pl);
                        menu.refresh();
                    });
            if (!egg) {
                boolean out = Dragons.out(p);
                menu.set(22, Btn.of(out ? Items.BARRIER : Items.ELYTRA).color(Theme.GOLD_LIGHT)
                        .name(Msg.trFor(p, out ? "dragon.btn-home" : "dragon.btn-call")).left(Msg.trFor(p, "dragon.click")).build(),
                        null, (pl, c) -> {
                            if (Dragons.out(pl)) {
                                Dragons.dismiss(pl, true);
                            } else {
                                Dragons.summon(pl);
                            }
                            pl.closeHandledScreen();
                        });
            }
            Btn abilities = Btn.of(Items.ENCHANTED_BOOK).color(0xD580FF).name(Msg.trFor(p, "dragon.abilities"));
            for (String s : new String[]{"hatchling", "young", "adult"}) {
                boolean has = switch (dd.stage) {
                    case "adult" -> true;
                    case "young" -> !s.equals("adult");
                    case "hatchling" -> s.equals("hatchling");
                    default -> false;
                };
                abilities.line((has ? "§a✔ " : "§8✘ ") + Msg.trFor(p, "dragon.ab." + s));
            }
            abilities.line("");
            abilities.line(Msg.trFor(p, "dragon.ab.note"));
            menu.icon(24, abilities.build());
        });
        m.open(p);
    }
}
