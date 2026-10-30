package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.feature.Achievements;
import com.vylorq.anticheat.feature.Artifacts;
import com.vylorq.anticheat.feature.Bosses;
import com.vylorq.anticheat.feature.Skills;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Set;

/** The achievement book: skills, bosses, structures, artifacts, biomes and feats, one page. */
public final class AchievementsMenu {
    private AchievementsMenu() {
    }

    private static String mark(boolean done, String text) {
        return (done ? "§a✔ §f" : "§8✘ §7") + text;
    }

    public static void open(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "ach.title"));
        m.renderer(menu -> {
            Set<String> got = Achievements.of(p);
            menu.info(Btn.of(Items.WRITTEN_BOOK).name(Msg.trFor(p, "ach.title")).desc(Msg.trFor(p, "ach.desc"))
                    .line(Msg.trFor(p, "ach.count", got.stream().filter(s -> !s.startsWith("biome:")).count())).build());
            Btn skills = Btn.of(Items.EXPERIENCE_BOTTLE).color(Theme.GOLD_LIGHT).name(Msg.trFor(p, "ach.skills"));
            for (String s : Skills.ALL) {
                long[] pr = Skills.progress(p, s);
                int lv = Skills.level(p, s);
                skills.line("§e" + Msg.trFor(p, "skills." + s) + " §f" + lv + "§7/" + Skills.MAX_LEVEL
                        + (lv < Skills.MAX_LEVEL ? " §8(" + pr[0] + "/" + pr[1] + ")" : " §6★"));
            }
            skills.line("");
            skills.line(Msg.trFor(p, "ach.skills-perks"));
            menu.icon(19, skills.build());
            Btn bosses = Btn.of(Items.WITHER_SKELETON_SKULL).color(Theme.RED).name(Msg.trFor(p, "ach.bosses"));
            int nb = 0;
            for (String k : Bosses.kinds()) {
                boolean done = got.contains("boss:" + k);
                nb += done ? 1 : 0;
                bosses.line(mark(done, Bosses.nameOf(k)));
            }
            menu.icon(21, bosses.amount(Math.max(1, nb)).build());
            Btn structures = Btn.of(Items.FILLED_MAP).color(Theme.GOLD).name(Msg.trFor(p, "ach.structures"));
            int ns = 0;
            for (String s : Bosses.structures()) {
                boolean done = got.contains("structure:" + s);
                ns += done ? 1 : 0;
                structures.line(mark(done, Achievements.pretty(s)));
            }
            menu.icon(23, structures.amount(Math.max(1, ns)).build());
            Btn arts = Btn.of(Items.NETHER_STAR).color(0xD580FF).name(Msg.trFor(p, "ach.artifacts"));
            int na = 0;
            for (var e : Artifacts.DEFS.entrySet()) {
                boolean done = got.contains("artifact:" + e.getKey());
                na += done ? 1 : 0;
                arts.line(mark(done, e.getValue().name()));
            }
            menu.icon(25, arts.amount(Math.max(1, na)).build());
            Btn biomes = Btn.of(Items.GRASS_BLOCK).color(Theme.GREEN).name(Msg.trFor(p, "ach.biomes"));
            long seen = got.stream().filter(s -> s.startsWith("biome:")).count();
            biomes.line(Msg.trFor(p, "ach.biomes-seen", seen));
            for (String b : Achievements.NEW_BIOMES) {
                biomes.line(mark(got.contains("biome:" + b), Achievements.pretty(b)));
            }
            menu.icon(30, biomes.build());
            Btn feats = Btn.of(Items.GOLD_INGOT).color(Theme.GOLD_LIGHT).name(Msg.trFor(p, "ach.feats"));
            for (var e : Achievements.FEATS.entrySet()) {
                feats.line(mark(got.contains(e.getKey()), e.getValue()));
            }
            for (String s : Skills.ALL) {
                for (int lv = 10; lv <= Skills.MAX_LEVEL; lv += 10) {
                    String id = "skill:" + s + ":" + lv;
                    if (got.contains(id)) {
                        feats.line(mark(true, Achievements.label(id)));
                    }
                }
            }
            menu.icon(32, feats.build());
        });
        m.open(p);
    }
}
