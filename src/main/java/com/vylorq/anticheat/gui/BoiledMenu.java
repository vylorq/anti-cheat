package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.BoiledFight;
import com.vylorq.anticheat.feature.BoiledHaunts;
import com.vylorq.anticheat.feature.BoiledOmens;
import com.vylorq.anticheat.feature.BoiledOne;
import com.vylorq.anticheat.feature.OwnerPowers;
import com.vylorq.anticheat.feature.SecretItems;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /owner boiledone: everything about The Boiled One and The Boiling in one menu. */
public final class BoiledMenu {
    private BoiledMenu() {
    }

    private static String title(ServerPlayerEntity p) {
        return Msg.trFor(p, "bmenu.title");
    }

    public static void open(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), title(p));
        m.renderer(menu -> {
            boolean on = BoiledOne.enabled();
            boolean night = BoiledOne.eventOn();
            int out = BoiledOne.sightings().size();
            menu.info(Btn.of(Items.WITHER_SKELETON_SKULL).color(Theme.RED).name(title(p))
                    .status(on ? Theme.GREEN : Theme.SOFT, Msg.tr(on ? "owner.state-on" : "owner.state-off"))
                    .line(Msg.tr("bmenu.info-out", out, BoiledOne.huntedPlayers().size()))
                    .line(Msg.tr("bmenu.info-night", Msg.tr(night ? "owner.state-on" : "owner.state-off")))
                    .line(Msg.tr("bmenu.info-fight", BoiledFight.status(), BoiledFight.fights(), BoiledFight.wins())).build());

            // ---- it, in the world
            menu.set(10, Btn.of(on ? Items.LIME_DYE : Items.GRAY_DYE).name(Msg.tr("bmenu.enabled")).desc(Msg.tr("bmenu.enabled.desc"))
                    .onOff(on).left(Msg.tr("owner.toggle")).build(), null, (pl, c) -> {
                BoiledOne.ownerToggle(pl, !on);
                menu.refresh();
            });
            menu.set(11, Btn.of(Items.CRIMSON_FUNGUS).color(Theme.RED).name(Msg.tr("bmenu.night")).desc(Msg.tr("bmenu.night.desc"))
                    .onOff(night).left(Msg.tr(night ? "bmenu.stop" : "bmenu.start")).glint(night).build(), null, (pl, c) -> {
                BoiledOne.ownerEvent(pl, !night);
                menu.refresh();
            });
            boolean esp = BoiledOne.locating(p);
            menu.set(12, Btn.of(Items.SPYGLASS).name(Msg.tr("bmenu.locate")).desc(Msg.tr("bmenu.locate.desc")).onOff(esp)
                    .left(Msg.tr("owner.toggle")).glint(esp).build(), null, (pl, c) -> {
                BoiledOne.ownerLocate(pl);
                menu.refresh();
            });
            int mins = BoiledOne.minutes();
            menu.set(13, Btn.of(Items.CLOCK).name(Msg.tr("bmenu.often")).desc(Msg.tr("bmenu.often.desc"))
                    .status(Theme.GOLD_LIGHT, Msg.tr("bmenu.often-now", mins)).left(Msg.tr("bmenu.more-often"))
                    .right(Msg.tr("bmenu.less-often")).shift(Msg.tr("owner.type-number")).build(), null, (pl, c) -> {
                if (c.isShift()) {
                    Input.text(pl, Msg.trFor(pl, "bmenu.often"), String.valueOf(mins), t -> {
                        try {
                            BoiledOne.ownerMinutes(pl, Integer.parseInt(t.trim()));
                        } catch (NumberFormatException e) {
                            Msg.send(pl, "general.bad-number");
                        }
                        open(pl);
                    });
                    return;
                }
                BoiledOne.ownerMinutes(pl, c.isRight() ? mins + 15 : Math.max(1, mins - 15));
                menu.refresh();
            });
            boolean always = BoiledOne.breakInsAlways();
            menu.set(14, Btn.of(Items.IRON_DOOR).name(Msg.tr("bmenu.breakins")).desc(Msg.tr("bmenu.breakins.desc"))
                    .status(always ? Theme.RED : Theme.SOFT, Msg.tr(always ? "bmenu.breakins-always" : "bmenu.breakins-rare"))
                    .left(Msg.tr("owner.toggle")).build(), null, (pl, c) -> {
                BoiledOne.ownerBreakIns(pl, !always);
                menu.refresh();
            });
            int nights = BoiledOne.eventNights();
            menu.set(15, Btn.of(Items.RED_CANDLE).name(Msg.tr("bmenu.nights")).desc(Msg.tr("bmenu.nights.desc"))
                    .status(Theme.GOLD_LIGHT, nights == 0 ? Msg.tr("bmenu.never") : Msg.tr("bmenu.nights-now", nights))
                    .left(Msg.tr("bmenu.more-often")).right(Msg.tr("bmenu.less-often")).shift(Msg.tr("bmenu.never")).build(), null, (pl, c) -> {
                BoiledOne.ownerEventNights(pl, c.isShift() ? 0 : c.isRight() ? (nights == 0 ? 20 : nights + 5) : Math.max(1, nights == 0 ? 20 : nights - 5));
                menu.refresh();
            });
            menu.set(16, Btn.of(Items.BARRIER).color(Theme.RED).name(Msg.tr("bmenu.removeall")).desc(Msg.tr("bmenu.removeall.desc"))
                    .left(Msg.tr("bmenu.do")).build(), null, (pl, c) -> {
                BoiledOne.ownerRemoveAll(pl);
                menu.refresh();
            });

            // ---- sending it, and who it's after
            menu.set(19, Btn.of(Items.TARGET).color(Theme.RED).name(Msg.tr("bmenu.send")).desc(Msg.tr("bmenu.send.desc"))
                    .left(Msg.tr("owner.open")).glint(true).build(), null, (pl, c) -> players(pl));
            menu.set(20, Btn.of(Items.WITHER_ROSE).name(Msg.tr("bmenu.spawn")).desc(Msg.tr("bmenu.spawn.desc"))
                    .left(Msg.tr("bmenu.spawn-watch")).right(Msg.tr("bmenu.spawn-scare")).shift(Msg.tr("bmenu.spawn-behind")).build(), null,
                    (pl, c) -> BoiledOne.ownerSend(pl, pl, c.isShift() ? "behind" : c.isRight() ? "scare" : "watch"));
            menu.set(21, Btn.of(Items.ENDER_EYE).name(Msg.tr("bmenu.live")).desc(Msg.tr("bmenu.live.desc")).amount(Math.max(1, out))
                    .left(Msg.tr("owner.open")).build(), null, (pl, c) -> live(pl));
            menu.set(22, Btn.of(Items.SKELETON_SKULL).name(Msg.tr("bmenu.hunted")).desc(Msg.tr("bmenu.hunted.desc"))
                    .amount(Math.max(1, BoiledOne.huntedPlayers().size())).left(Msg.tr("owner.open")).build(), null, (pl, c) -> hunted(pl));
            menu.set(23, Btn.of(Items.OAK_DOOR).name(Msg.tr("bmenu.bases")).desc(Msg.tr("bmenu.bases.desc"))
                    .left(Msg.tr("bmenu.show")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                BoiledOne.ownerBases(pl);
            });
            menu.set(24, Btn.of(Items.BOOK).name(Msg.tr("bmenu.chatinfo")).desc(Msg.tr("bmenu.chatinfo.desc"))
                    .left(Msg.tr("bmenu.show")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                BoiledOne.ownerInfo(pl);
                BoiledFight.ownerInfo(pl);
            });

            // ---- The Boiling
            menu.set(28, Btn.of(Items.CRIMSON_DOOR).color(Theme.RED).name(Msg.tr("bmenu.fight")).desc(Msg.tr("bmenu.fight.desc"))
                    .left(Msg.tr("owner.open")).glint(true).build(), null, (pl, c) -> BoiledFight.ownerFight(pl));
            boolean door = BoiledFight.doorOut();
            menu.set(29, Btn.of(Items.POLISHED_BLACKSTONE_BRICKS).name(Msg.tr("bmenu.door")).desc(Msg.tr("bmenu.door.desc"))
                    .status(door ? Theme.GREEN : Theme.SOFT, Msg.tr(door ? "bmenu.door-out" : "bmenu.door-none"))
                    .left(Msg.tr("bmenu.door-place")).build(), null, (pl, c) -> {
                BoiledFight.ownerDoor(pl);
                menu.refresh();
            });
            menu.set(30, Btn.of(Items.RED_STAINED_GLASS).name(Msg.tr("bmenu.fightstop")).desc(Msg.tr("bmenu.fightstop.desc"))
                    .left(Msg.tr("bmenu.do")).build(), null, (pl, c) -> {
                BoiledFight.ownerStop(pl);
                menu.refresh();
            });
            boolean there = BoiledFight.inBoiling(p);
            menu.set(31, Btn.of(Items.CRIMSON_NYLIUM).color(Theme.RED).name(Msg.tr("bmenu.go")).desc(Msg.tr("bmenu.go.desc"))
                    .left(Msg.tr("bmenu.go-in")).right(Msg.tr("bmenu.go-back")).glint(there).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                if (c.isRight()) {
                    BoiledFight.ownerLeave(pl);
                } else {
                    BoiledFight.ownerEnter(pl);
                }
            });
            menu.set(32, Btn.of(Items.GOLDEN_HELMET).name(Msg.tr("bmenu.records")).desc(Msg.tr("bmenu.records.desc"))
                    .left(Msg.tr("bmenu.show")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                BoiledFight.records(pl);
            });

            // ---- its items
            item(menu, 37, Items.LANTERN, "bmenu.item-lantern", pl -> pl.getInventory().offerOrDrop(BoiledOmens.lantern()));
            item(menu, 38, Items.PAPER, "bmenu.item-pages", BoiledOne::ownerPages);
            item(menu, 39, Items.ENDER_EYE, "bmenu.item-eye", pl -> pl.getInventory().offerOrDrop(BoiledHaunts.eye(pl)));
            item(menu, 40, Items.NETHERITE_CHESTPLATE, "bmenu.item-armor", pl -> {
                for (int i = 0; i < 4; i++) {
                    pl.getInventory().offerOrDrop(BoiledFight.armor(i));
                }
            });
            item(menu, 41, Items.NETHERITE_SWORD, "bmenu.item-edge", pl -> SecretItems.give(pl, SecretItems.BOILING_EDGE));
            item(menu, 42, Items.NETHER_STAR, "bmenu.item-heart", pl -> pl.getInventory().offerOrDrop(BoiledFight.heart(pl)));
        });
        m.open(p);
    }

    private static void item(Menu menu, int slot, Item icon, String key, java.util.function.Consumer<ServerPlayerEntity> give) {
        menu.set(slot, Btn.of(icon).color(Theme.GOLD_LIGHT).name(Msg.tr(key)).left(Msg.tr("bmenu.take")).glint(true).build(), null,
                (pl, c) -> {
                    if (OwnerPowers.require(pl)) {
                        give.accept(pl);
                    }
                });
    }

    // ---------------------------------------------------------------- send it at someone

    private static void players(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), title(p), Msg.trFor(p, "bmenu.send"));
        m.renderer(menu -> menu.list(new ArrayList<>(Ac.server().getPlayerManager().getPlayerList()),
                o -> Btn.head(o.getUuid(), o.getGameProfile().name()).name(o.getGameProfile().name())
                        .line(BoiledOne.huntedPlayers().containsKey(o.getUuidAsString()) ? "§4" + Msg.tr("bmenu.followed") : "")
                        .left(Msg.tr("bmenu.pick")).build(),
                o -> (pl, c) -> actions(pl, o.getUuid()),
                o -> o.getGameProfile().name(), List.of(), Msg.tr("bmenu.nobody"), ""));
        m.open(p);
    }

    /** What it does to them: every way it can come, every omen, and following them. */
    private static final String[] HOW = {"watch", "scare", "peek", "behind", "breakin", "glimpse", "atdoor", "mimic", "mimicplayer",
            "footsteps", "knock", "door", "whisper", "fakechat", "trail", "torches"};
    private static final Item[] HOW_ICONS = {Items.WITHER_SKELETON_SKULL, Items.GHAST_TEAR, Items.STONE, Items.SOUL_LANTERN,
            Items.IRON_DOOR, Items.GLASS_PANE, Items.OAK_DOOR, Items.COW_SPAWN_EGG, Items.PLAYER_HEAD, Items.LEATHER_BOOTS,
            Items.OAK_BUTTON, Items.SPRUCE_DOOR, Items.PAPER, Items.WRITABLE_BOOK, Items.REDSTONE, Items.TORCH};

    private static void actions(ServerPlayerEntity p, UUID target) {
        ServerPlayerEntity t0 = Ac.server().getPlayerManager().getPlayer(target);
        if (t0 == null) {
            Msg.send(p, "boiled.no-player");
            return;
        }
        String name = t0.getGameProfile().name();
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), title(p), name);
        m.renderer(menu -> {
            menu.info(Btn.head(target, name).name(name).desc(Msg.tr("bmenu.actions.desc")).build());
            for (int i = 0; i < HOW.length; i++) {
                String how = HOW[i];
                menu.set(Menu.CONTENT[i], Btn.of(HOW_ICONS[i]).name(Msg.tr("bmenu.how." + how)).desc(Msg.tr("bmenu.how." + how + ".desc"))
                        .left(Msg.tr("bmenu.do")).build(), null, (pl, c) -> {
                    ServerPlayerEntity t = Ac.server().getPlayerManager().getPlayer(target);
                    BoiledOne.ownerSend(pl, t, how);
                });
            }
            boolean followed = BoiledOne.huntedPlayers().containsKey(target.toString());
            menu.set(Menu.CONTENT[HOW.length + 2], Btn.of(followed ? Items.REDSTONE_BLOCK : Items.BONE_BLOCK).color(Theme.RED)
                    .name(Msg.tr("bmenu.follow")).desc(Msg.tr("bmenu.follow.desc")).onOff(followed).left(Msg.tr("owner.toggle"))
                    .glint(followed).build(), null, (pl, c) -> {
                BoiledOne.ownerHunt(pl, Ac.server().getPlayerManager().getPlayer(target), !followed);
                menu.refresh();
            });
        });
        m.open(p);
    }

    // ---------------------------------------------------------------- what's out there right now

    private static void live(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), title(p), Msg.trFor(p, "bmenu.live"));
        m.live();
        m.renderer(menu -> menu.list(BoiledOne.sightings(),
                s -> Btn.of(Items.WITHER_SKELETON_SKULL).color(Theme.RED).name(s.victim())
                        .line(Msg.tr("bmenu.live-line", s.mode(), s.pos().toShortString(),
                                com.vylorq.anticheat.util.Mc.worldId(s.world()).replace("minecraft:", "")))
                        .left(Msg.tr("bmenu.goto")).build(),
                s -> (pl, c) -> {
                    pl.closeHandledScreen();
                    BoiledOne.ownerGoto(pl, s.mob());
                },
                BoiledOne.Sighting::victim, List.of(), Msg.tr("bmenu.live-none"), ""));
        m.open(p);
    }

    private static void hunted(ServerPlayerEntity p) {
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), title(p), Msg.trFor(p, "bmenu.hunted"));
        m.renderer(menu -> {
            List<Map.Entry<String, String>> all = new ArrayList<>(BoiledOne.huntedPlayers().entrySet());
            menu.list(all, e -> {
                        UUID id;
                        try {
                            id = UUID.fromString(e.getKey());
                        } catch (IllegalArgumentException x) {
                            id = new UUID(0, 0);
                        }
                        return Btn.head(id, e.getValue()).name(e.getValue()).left(Msg.tr("bmenu.unfollow")).build();
                    },
                    e -> (pl, c) -> {
                        BoiledOne.ownerUnhunt(pl, e.getKey());
                        menu.refresh();
                    },
                    Map.Entry::getValue, List.of(), Msg.tr("bmenu.hunted-none"), "");
        });
        m.open(p);
    }
}
