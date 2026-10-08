package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.feature.OwnerPowers;
import com.vylorq.anticheat.feature.OwnerPowers.Power;
import com.vylorq.anticheat.feature.OwnerTools;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /owner: every owner power, tool and extra in one place. */
public final class OwnerMenu {
    private OwnerMenu() {
    }

    private static final Power[] POWERS = Power.values();
    /** Base items for the icons: the owner pack draws custom icons over them (plain items without the pack). */
    private static final Item[] ICONS = {Items.FEATHER, Items.TOTEM_OF_UNDYING, Items.SUGAR, Items.ENDER_EYE,
            Items.FLINT, Items.AMETHYST_SHARD, Items.PHANTOM_MEMBRANE};
    private static final String[] ICON_IDS = {"icon_fly", "icon_god", "icon_speed", "icon_night", "icon_break", "icon_radar", "icon_ghost"};

    static ItemStack icon(Item base, String id) {
        ItemStack s = new ItemStack(base);
        com.vylorq.anticheat.util.PackIds.apply(s, id);
        return s;
    }

    public static void open(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"));
        m.renderer(menu -> {
            menu.info(Btn.head(p.getUuid(), p.getGameProfile().name()).name(Category.VIGIL, Msg.tr("owner.title"))
                    .desc(Msg.tr("owner.desc")).build());
            int[] slots = {10, 11, 12, 13, 14, 15, 16};
            for (int i = 0; i < ICONS.length; i++) {
                Power pw = POWERS[i];
                boolean on = OwnerPowers.on(p, pw);
                String name = Msg.tr("owner.power." + pw.name().toLowerCase(Locale.ROOT));
                Btn b = Btn.of(icon(ICONS[i], ICON_IDS[i])).color(on ? Theme.GREEN : Theme.SOFT).name(name)
                        .desc(Msg.tr("owner.power." + pw.name().toLowerCase(Locale.ROOT) + ".desc"))
                        .status(on ? Theme.GREEN : Theme.SOFT, Msg.tr(on ? "owner.state-on" : "owner.state-off")
                                + (pw == Power.SPEED && on ? " (" + OwnerPowers.state().speed + "/3)" : ""))
                        .left(Msg.tr(pw == Power.SPEED ? "owner.next-level" : "owner.toggle")).glint(on);
                menu.set(slots[i], b.build(), null, (pl, c) -> {
                    OwnerPowers.toggle(pl, pw);
                    menu.refresh();
                });
            }
            List<ItemStack> tools = OwnerTools.all();
            int[] toolSlots = {19, 20, 22, 24, 25};
            for (int i = 0; i < tools.size(); i++) {
                ItemStack t = tools.get(i);
                menu.set(toolSlots[i], t.copy(), null, (pl, c) -> OwnerTools.give(pl, t));
            }
            menu.set(28, Btn.of(icon(Items.PRISMARINE_SHARD, "freeze_wand")).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.freeze"))
                    .desc(Msg.tr("owner.freeze.desc")).status(Theme.GOLD_LIGHT, Msg.tr("owner.freeze-radius", OwnerPowers.state().freezeRadius))
                    .left(Msg.tr("owner.bigger")).right(Msg.tr("owner.smaller")).shift(Msg.tr("owner.type-number")).build(), null, (pl, c) -> {
                if (c.isShift()) {
                    Input.text(pl, Msg.trFor(pl, "owner.freeze"), String.valueOf(OwnerPowers.state().freezeRadius), t -> {
                        try {
                            OwnerPowers.setFreezeRadius(pl, Integer.parseInt(t.trim()));
                        } catch (NumberFormatException e) {
                            Msg.send(pl, "general.bad-number");
                        }
                        open(pl);
                    });
                    return;
                }
                int r = OwnerPowers.state().freezeRadius;
                OwnerPowers.setFreezeRadius(pl, c.isRight() ? r - 5 : r + 5);
                menu.refresh();
            });
            menu.set(29, Btn.of(icon(Items.IRON_NUGGET, "icon_repair")).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.repair")).desc(Msg.tr("owner.repair.desc"))
                    .left(Msg.tr("owner.repair-hand")).right(Msg.tr("owner.repair-all")).build(), null,
                    (pl, c) -> OwnerTools.repair(pl, c.isRight()));
            menu.set(31, Btn.of(icon(Items.PAPER, "icon_items")).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.items")).desc(Msg.tr("owner.items.desc"))
                    .left(Msg.tr("owner.open")).build(), null, (pl, c) -> items(pl));
            String style = OwnerPowers.state().joinStyle;
            menu.set(33, Btn.of(icon(Items.NETHER_STAR, "icon_join")).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.join"))
                    .desc(Msg.tr("owner.join.desc")).status(Theme.GOLD_LIGHT, Msg.tr("owner.join." + style))
                    .left(Msg.tr("owner.next-option")).build(), null, (pl, c) -> {
                String next = switch (style) {
                    case "normal" -> "grand";
                    case "grand" -> "silent";
                    default -> "normal";
                };
                OwnerPowers.setJoinStyle(pl, next);
                menu.refresh();
            });
            menu.set(32, Btn.of(Items.ENCHANTED_BOOK).color(Theme.GOLD_LIGHT).name(Msg.tr("enchants.title"))
                    .desc(Msg.tr("enchants.desc")).left(Msg.tr("owner.open")).glint(true).build(), null, (pl, c) -> enchants(pl));
            menu.set(30, Btn.of(Items.WITHER_SKELETON_SKULL).color(Theme.RED).name(Msg.tr("bmenu.title"))
                    .desc(Msg.tr("bmenu.desc")).left(Msg.tr("owner.open")).glint(true).build(), null, (pl, c) -> BoiledMenu.open(pl));
            menu.set(38, Btn.of(icon(Items.BLAZE_POWDER, "icon_berserk")).color(Theme.RED).name(Msg.tr("owner.combat"))
                    .desc(Msg.tr("owner.combat.desc")).left(Msg.tr("owner.open")).glint(true).build(), null, (pl, c) -> combat(pl));
            menu.set(40, Btn.of(icon(Items.PAINTING, "icon_pack")).color(Theme.SOFT).name(Msg.tr("owner.pack")).desc(Msg.tr("owner.pack.desc"))
                    .left(Msg.tr("owner.pack-send")).build(), null, (pl, c) -> OwnerPowers.sendPack(pl));
            menu.set(42, Btn.of(secretIcon("stormbreaker")).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.secrets"))
                    .desc(Msg.tr("owner.secrets.desc")).left(Msg.tr("owner.open")).glint(true).build(), null, (pl, c) -> secrets(pl));
        });
        m.open(p);
    }

    private static final Power[] COMBAT = {Power.ONE_PUNCH, Power.LIFESTEAL, Power.MEGA_KNOCKBACK, Power.NO_COOLDOWN, Power.FORCE_FIELD};
    private static final Item[] COMBAT_ICONS = {Items.BRICK, Items.RED_DYE, Items.SLIME_BALL, Items.GLOWSTONE_DUST, Items.HEART_OF_THE_SEA};
    private static final String[] COMBAT_IDS = {"icon_punch", "icon_steal", "icon_knock", "icon_nocool", "icon_field"};

    /** Combat: toggles, weapons and instant actions. */
    public static void combat(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "owner.combat"));
        m.renderer(menu -> {
            menu.info(Btn.head(p.getUuid(), p.getGameProfile().name()).name(Category.VIGIL, Msg.tr("owner.combat"))
                    .desc(Msg.tr("owner.combat.desc")).build());
            int[] slots = {11, 12, 13, 14, 15};
            for (int i = 0; i < COMBAT.length; i++) {
                Power pw = COMBAT[i];
                boolean on = OwnerPowers.on(p, pw);
                String key = "owner.power." + pw.name().toLowerCase(Locale.ROOT);
                menu.set(slots[i], Btn.of(icon(COMBAT_ICONS[i], COMBAT_IDS[i])).color(on ? Theme.GREEN : Theme.SOFT).name(Msg.tr(key))
                        .desc(Msg.tr(key + ".desc")).status(on ? Theme.GREEN : Theme.SOFT, Msg.tr(on ? "owner.state-on" : "owner.state-off"))
                        .left(Msg.tr("owner.toggle")).glint(on).build(), null, (pl, c) -> {
                    OwnerPowers.toggle(pl, pw);
                    menu.refresh();
                });
            }
            List<ItemStack> weapons = com.vylorq.anticheat.feature.OwnerCombat.weapons();
            int[] weaponSlots = {19, 20, 21, 22, 23, 24, 25, 28};
            for (int i = 0; i < weapons.size(); i++) {
                ItemStack t = weapons.get(i);
                menu.set(weaponSlots[i], t.copy(), null, (pl, c) -> OwnerTools.give(pl, t));
            }
            menu.set(30, Btn.of(icon(Items.BLAZE_POWDER, "icon_berserk")).color(Theme.RED).name(Msg.tr("owner.berserk"))
                    .desc(Msg.tr("owner.berserk.desc")).left(Msg.tr("owner.activate")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                com.vylorq.anticheat.feature.OwnerCombat.berserk(pl);
            });
            int r = OwnerPowers.state().wipeRadius;
            menu.set(31, Btn.of(icon(Items.BONE, "icon_wipe")).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.mobwipe"))
                    .desc(Msg.tr("owner.mobwipe.desc")).status(Theme.GOLD_LIGHT, Msg.tr("owner.wipe-radius", r))
                    .left(Msg.tr("owner.activate")).right(Msg.tr("owner.wipe-change")).shift(Msg.tr("owner.type-number")).build(), null, (pl, c) -> {
                if (c.isShift()) {
                    Input.text(pl, Msg.trFor(pl, "owner.mobwipe"), String.valueOf(OwnerPowers.state().wipeRadius), t -> {
                        try {
                            OwnerPowers.setWipeRadius(pl, Integer.parseInt(t.trim()));
                        } catch (NumberFormatException e) {
                            Msg.send(pl, "general.bad-number");
                        }
                        combat(pl);
                    });
                    return;
                }
                if (c.isRight()) {
                    int cur = OwnerPowers.state().wipeRadius;
                    OwnerPowers.setWipeRadius(pl, cur >= 128 ? 8 : cur + 8);
                    menu.refresh();
                    return;
                }
                pl.closeHandledScreen();
                com.vylorq.anticheat.feature.OwnerCombat.mobWipe(pl);
            });
            menu.set(40, Btn.of(icon(Items.PRISMARINE_CRYSTALS, "orbital_cannon")).color(Theme.RED).name(Msg.tr("orbital.title"))
                    .desc(Msg.tr("orbital.desc")).left(Msg.tr("owner.open")).build(), null, (pl, c) -> orbital(pl));
            menu.set(32, Btn.of(icon(Items.GOLD_NUGGET, "icon_smite")).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.smite"))
                    .desc(Msg.tr("owner.smite.desc")).left(Msg.tr("owner.activate")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                com.vylorq.anticheat.feature.OwnerCombat.smite(pl);
            });
        });
        m.open(p);
    }

    // ---------------------------------------------------------------- orbital strike

    private static final String[] PRESETS = {"nuke", "stab", "carpet", "doomsday"};

    private static String next(String[] all, String cur) {
        int i = java.util.Arrays.asList(all).indexOf(cur);
        return all[(i + 1) % all.length];
    }

    /** A number button: left +step, right -step, shift-click to type it. */
    private static void num(Menu menu, ServerPlayerEntity p, int slot, Item base, String key, String shown, int step,
                            java.util.function.IntConsumer delta, java.util.function.Consumer<String> typed) {
        menu.set(slot, Btn.of(base).color(Theme.GOLD_LIGHT).name(Msg.tr("orbital." + key)).desc(Msg.tr("orbital." + key + ".desc"))
                .status(Theme.GOLD_LIGHT, shown).left("+" + step).right("-" + step).shift(Msg.tr("owner.type-number")).build(), null, (pl, c) -> {
            if (c.isShift()) {
                Input.text(pl, Msg.trFor(pl, "orbital." + key), shown, t -> {
                    try {
                        typed.accept(t.trim());
                    } catch (RuntimeException e) {
                        Msg.send(pl, "general.bad-number");
                    }
                    com.vylorq.anticheat.feature.OrbitalStrike.changed();
                    orbital(pl);
                });
                return;
            }
            delta.accept(c.isRight() ? -step : step);
            com.vylorq.anticheat.feature.OrbitalStrike.changed();
            menu.refresh();
        });
    }

    private static void toggle(Menu menu, int slot, Item base, String key, boolean on, Runnable flip) {
        menu.set(slot, Btn.of(base).color(on ? Theme.GREEN : Theme.SOFT).name(Msg.tr("orbital." + key)).desc(Msg.tr("orbital." + key + ".desc"))
                .status(on ? Theme.GREEN : Theme.SOFT, Msg.tr(on ? "owner.state-on" : "owner.state-off")).left(Msg.tr("owner.toggle")).glint(on).build(),
                null, (pl, c) -> {
                    flip.run();
                    com.vylorq.anticheat.feature.OrbitalStrike.changed();
                    menu.refresh();
                });
    }

    /** Every setting of the orbital strike. */
    public static void orbital(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "orbital.title"));
        m.renderer(menu -> {
            var s = com.vylorq.anticheat.feature.OrbitalStrike.settings();
            menu.info(Btn.of(icon(Items.PRISMARINE_CRYSTALS, "orbital_cannon")).color(Theme.RED).name(Msg.tr("orbital.title"))
                    .desc(Msg.tr("orbital.desc")).build());
            menu.set(10, Btn.of(Items.TNT).color(Theme.RED).name(Msg.tr("orbital.fire")).desc(Msg.tr("orbital.fire.desc"))
                    .status(Theme.RED, s.tnt + " TNT, " + Msg.tr("orbital.pattern." + s.pattern) + ", " + com.vylorq.anticheat.feature.OrbitalStrike.describe(p))
                    .left(Msg.tr("orbital.fire")).glint(true).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                com.vylorq.anticheat.feature.OrbitalStrike.fire(pl);
            });
            menu.set(11, Btn.of(Items.NETHER_STAR).color(Theme.GOLD_LIGHT).name(Msg.tr("orbital.preset")).desc(Msg.tr("orbital.preset.desc"))
                    .left(Msg.tr("orbital.preset.nuke")).right(Msg.tr("orbital.preset.stab")).shift(Msg.tr("orbital.preset.carpet") + " / "
                            + Msg.tr("orbital.preset.doomsday")).build(), null, (pl, c) -> {
                String cur = c.isShift() ? (OwnerPowers.state().orbital.tnt >= 400 ? "carpet" : "doomsday") : c.isRight() ? "stab" : "nuke";
                com.vylorq.anticheat.feature.OrbitalStrike.preset(cur);
                Msg.actionBar(pl, "§c◎ " + Msg.trFor(pl, "orbital.preset." + cur));
                menu.refresh();
            });
            menu.set(12, Btn.of(Items.COMPASS).color(Theme.GOLD_LIGHT).name(Msg.tr("orbital.pattern")).desc(Msg.tr("orbital.pattern.desc"))
                    .status(Theme.GOLD_LIGHT, Msg.tr("orbital.pattern." + s.pattern)).left(Msg.tr("owner.next-option")).build(), null, (pl, c) -> {
                var st = com.vylorq.anticheat.feature.OrbitalStrike.settings();
                st.pattern = next(com.vylorq.anticheat.feature.OrbitalStrike.PATTERNS, st.pattern);
                com.vylorq.anticheat.feature.OrbitalStrike.changed();
                menu.refresh();
            });
            menu.set(13, Btn.of(Items.TARGET).color(Theme.GOLD_LIGHT).name(Msg.tr("orbital.target")).desc(Msg.tr("orbital.target.desc"))
                    .status(Theme.GOLD_LIGHT, com.vylorq.anticheat.feature.OrbitalStrike.describe(p))
                    .left(Msg.tr("owner.next-option")).right(Msg.tr("orbital.pick-player")).shift(Msg.tr("orbital.type-coords")).build(), null, (pl, c) -> {
                var st = com.vylorq.anticheat.feature.OrbitalStrike.settings();
                if (c.isShift()) {
                    Input.text(pl, Msg.trFor(pl, "orbital.type-coords"), pl.getBlockX() + " " + pl.getBlockZ(), t -> {
                        String[] parts = t.trim().split("[ ,]+");
                        try {
                            if (parts.length == 2) {
                                st.x = Integer.parseInt(parts[0]);
                                st.z = Integer.parseInt(parts[1]);
                                st.y = null;
                            } else if (parts.length == 3) {
                                st.x = Integer.parseInt(parts[0]);
                                st.y = Integer.parseInt(parts[1]);
                                st.z = Integer.parseInt(parts[2]);
                            } else {
                                throw new NumberFormatException();
                            }
                            st.target = "coords";
                        } catch (NumberFormatException e) {
                            Msg.send(pl, "orbital.bad-coords");
                        }
                        com.vylorq.anticheat.feature.OrbitalStrike.changed();
                        orbital(pl);
                    });
                    return;
                }
                if (c.isRight()) {
                    pickTarget(pl);
                    return;
                }
                st.target = next(com.vylorq.anticheat.feature.OrbitalStrike.TARGETS, st.target);
                com.vylorq.anticheat.feature.OrbitalStrike.changed();
                menu.refresh();
            });
            num(menu, p, 14, Items.TNT, "tnt", String.valueOf(s.tnt), 10, d -> s.tnt += d, t -> s.tnt = Integer.parseInt(t));
            num(menu, p, 15, Items.SPYGLASS, "radius", s.radius + " " + Msg.tr("orbital.blocks"), 2, d -> s.radius += d, t -> s.radius = Integer.parseInt(t));
            num(menu, p, 16, Items.FEATHER, "height", s.height + " " + Msg.tr("orbital.blocks"), 10, d -> s.height += d, t -> s.height = Integer.parseInt(t));
            num(menu, p, 19, Items.GUNPOWDER, "power", com.vylorq.anticheat.feature.OrbitalStrike.fmtPower(s.power), 1,
                    d -> s.power += d, t -> s.power = Float.parseFloat(t));
            num(menu, p, 20, Items.CLOCK, "fuse", s.fuse == 0 ? Msg.tr("orbital.on-impact") : s.fuse + " ticks", 10,
                    d -> s.fuse += d, t -> s.fuse = Integer.parseInt(t));
            num(menu, p, 21, Items.HOPPER, "per-wave", String.valueOf(s.perWave), 5, d -> s.perWave += d, t -> s.perWave = Integer.parseInt(t));
            num(menu, p, 22, Items.REPEATER, "wave-ticks", s.waveTicks + " ticks", 1, d -> s.waveTicks += d, t -> s.waveTicks = Integer.parseInt(t));
            num(menu, p, 23, Items.CLOCK, "delay", s.delay + "s", 5, d -> s.delay += d, t -> s.delay = Integer.parseInt(t));
            toggle(menu, 24, Items.IRON_PICKAXE, "break-blocks", s.breakBlocks, () -> s.breakBlocks = !s.breakBlocks);
            toggle(menu, 25, Items.SHIELD, "ignore-claims", s.ignoreClaims, () -> s.ignoreClaims = !s.ignoreClaims);
            toggle(menu, 28, Items.LEAD, "follow", s.follow, () -> s.follow = !s.follow);
            menu.set(30, com.vylorq.anticheat.feature.OrbitalStrike.item(), null,
                    (pl, c) -> OwnerTools.give(pl, com.vylorq.anticheat.feature.OrbitalStrike.item()));
            int undo = com.vylorq.anticheat.feature.OrbitalStrike.undoable();
            menu.set(31, Btn.of(Items.RECOVERY_COMPASS).color(undo > 0 ? Theme.GREEN : Theme.SOFT).name(Msg.tr("orbital.undo"))
                    .desc(Msg.tr("orbital.undo.desc")).status(undo > 0 ? Theme.GREEN : Theme.SOFT, Msg.tr("orbital.undo-count", undo))
                    .left(Msg.tr("orbital.undo-last")).right(Msg.tr("orbital.undo-all")).build(), null, (pl, c) -> {
                com.vylorq.anticheat.feature.OrbitalStrike.undo(pl, c.isRight());
                menu.refresh();
            });
            int live = com.vylorq.anticheat.feature.OrbitalStrike.active();
            menu.set(32, Btn.of(Items.BARRIER).color(live > 0 ? Theme.RED : Theme.SOFT).name(Msg.tr("orbital.cancel"))
                    .desc(Msg.tr("orbital.cancel.desc")).status(live > 0 ? Theme.RED : Theme.SOFT, Msg.tr("orbital.active", live))
                    .left(Msg.tr("orbital.cancel")).build(), null, (pl, c) -> {
                com.vylorq.anticheat.feature.OrbitalStrike.cancelAll(pl);
                menu.refresh();
            });
        });
        m.open(p);
    }

    /** Pick the player the strike falls on. */
    private static void pickTarget(ServerPlayerEntity p) {
        List<ServerPlayerEntity> players = new ArrayList<>(p.getEntityWorld().getServer().getPlayerManager().getPlayerList());
        players.remove(p);
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "orbital.title"), Msg.trFor(p, "orbital.pick-player"));
        m.renderer(menu -> menu.list(players, t -> Btn.head(t.getUuid(), t.getGameProfile().name()).color(Theme.GOLD_LIGHT)
                        .name(t.getGameProfile().name()).left(Msg.tr("owner.pick")).build(),
                t -> (pl, c) -> {
                    var st = com.vylorq.anticheat.feature.OrbitalStrike.settings();
                    st.target = "player";
                    st.targetPlayer = t.getUuid().toString();
                    st.targetName = t.getGameProfile().name();
                    com.vylorq.anticheat.feature.OrbitalStrike.changed();
                    orbital(pl);
                }, t -> t.getGameProfile().name(), List.of(), Msg.tr("orbital.no-players"), ""));
        m.open(p);
    }

    private static final long[] JAIL_TIMES = {5, 15, 30, 60, 6 * 60, 24 * 60, 7 * 24 * 60};

    /** Quick jail from the gavel: how long, then why. */
    public static void jail(ServerPlayerEntity p, ServerPlayerEntity target) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (com.vylorq.anticheat.Ac.get().jail.cells().isEmpty()) {
            Msg.send(p, "jail.no-cells");
            return;
        }
        String name = target.getGameProfile().name();
        Menu m = Menu.std(Category.JAIL, Msg.trFor(p, "owner.gavel"), name);
        m.renderer(menu -> {
            menu.info(Btn.head(target.getUuid(), name).name(Category.JAIL, Msg.tr("owner.jail-who", name)).desc(Msg.tr("owner.jail-how-long")).build());
            int[] slots = {19, 20, 21, 22, 23, 24, 25};
            for (int i = 0; i < JAIL_TIMES.length; i++) {
                long minutes = JAIL_TIMES[i];
                long ms = minutes * com.vylorq.anticheat.core.util.Durations.MINUTE;
                String label = com.vylorq.anticheat.core.util.Durations.format(ms);
                menu.set(slots[i], Btn.of(Items.CLOCK).color(Theme.GOLD_LIGHT).name(label).left(Msg.tr("owner.pick")).build(), null,
                        (pl, c) -> jailWhy(pl, target, ms));
            }
        });
        m.open(p);
    }

    private static void jailWhy(ServerPlayerEntity p, ServerPlayerEntity target, long ms) {
        String name = target.getGameProfile().name();
        Menu m = Menu.std(Category.JAIL, Msg.trFor(p, "owner.gavel"), name, com.vylorq.anticheat.core.util.Durations.format(ms));
        m.renderer(menu -> {
            menu.info(Btn.head(target.getUuid(), name).name(Category.JAIL, Msg.tr("owner.jail-who", name)).desc(Msg.tr("owner.jail-why")).build());
            List<String> reasons = new ArrayList<>(com.vylorq.anticheat.Ac.config().staff.reasonTemplates.values());
            int slot = 19;
            for (String r : reasons) {
                if (slot > 25) {
                    break;
                }
                menu.set(slot++, Btn.of(Items.PAPER).color(Theme.WHITE).name(r).left(Msg.tr("owner.pick")).build(), null,
                        (pl, c) -> doJail(pl, target, ms, r));
            }
            menu.set(31, Btn.of(Items.WRITABLE_BOOK).color(Theme.GOLD_LIGHT).name(Msg.tr("owner.jail-type")).left(Msg.tr("owner.pick")).build(), null,
                    (pl, c) -> Input.text(pl, Msg.trFor(pl, "owner.jail-why"), "", t -> {
                        if (t != null && !t.isBlank()) {
                            doJail(pl, target, ms, t.trim());
                        }
                    }));
        });
        m.open(p);
    }

    private static void doJail(ServerPlayerEntity p, ServerPlayerEntity target, long ms, String reason) {
        p.closeHandledScreen();
        if (target.isRemoved()) {
            Msg.send(p, "owner.gone");
            return;
        }
        com.vylorq.anticheat.feature.Jail.jail(target, reason, ms, p.getGameProfile().name());
        com.vylorq.anticheat.feature.Staff.log(p, "jail", target.getUuid(), target.getGameProfile().name(),
                com.vylorq.anticheat.core.util.Durations.format(ms) + " " + reason + " (gavel)");
        com.vylorq.anticheat.Ac.get().punishments.add(com.vylorq.anticheat.core.staff.Punishment.Type.JAIL, target.getUuid(),
                target.getGameProfile().name(), reason, p.getGameProfile().name(), ms);
        com.vylorq.anticheat.Ac.markDirty("punishments");
        Msg.send(p, "owner.jailed", target.getGameProfile().name(), com.vylorq.anticheat.core.util.Durations.format(ms));
        OwnerPowers.sfx(p, "gavel", net.minecraft.sound.SoundEvents.BLOCK_ANVIL_LAND, 1.4f);
        target.getEntityWorld().spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, target.getX(), target.getY() + 1, target.getZ(), 30, 0.4, 0.8, 0.4, 0.02);
    }

    /** Every item in the game, searchable. Left: one, right: a full stack. */
    public static void items(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        List<Item> all = new ArrayList<>();
        for (Item i : Registries.ITEM) {
            if (i != Items.AIR) {
                all.add(i);
            }
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "owner.items"));
        m.renderer(menu -> menu.list(all, i -> {
                    ItemStack s = new ItemStack(i);
                    return s;
                }, i -> (pl, c) -> {
                    ItemStack s = new ItemStack(i);
                    s.setCount(c.isRight() || c.isShift() ? s.getMaxCount() : 1);
                    OwnerTools.give(pl, s);
                }, i -> Registries.ITEM.getId(i).getPath().replace('_', ' '),
                List.of(), Msg.tr("owner.items.none"), ""));
        m.open(p);
    }

    /** Each secret item: its base item, name and colour (the same as scripts/secrets/build.py ITEMS), and where it comes from. */
    private record Secret(String id, Item base, String name, int colour, String from) {
    }

    private static final List<Secret> SECRETS = List.of(
            new Secret("voidblade", Items.DIAMOND_SWORD, "Voidblade", 0xB488FF, "structure"),
            new Secret("stormbreaker", Items.DIAMOND_AXE, "Stormbreaker", 0x7FD8FF, "structure"),
            new Secret("tidecaller", Items.TRIDENT, "Tidecaller", 0x3FA8FF, "structure"),
            new Secret("phoenix_feather", Items.FEATHER, "Phoenix Feather", 0xFF8A1E, "structure"),
            new Secret("shadow_cloak", Items.PHANTOM_MEMBRANE, "Shadow Cloak", 0x8A73B0, "structure"),
            new Secret("seeker_compass", Items.NAUTILUS_SHELL, "Seeker Compass", 0x7FFFD4, "structure"),
            new Secret("tide_trident", Items.TRIDENT, "Warden's Tide", 0x3FD0FF, "boss"),
            new Secret("colossus_maul", Items.MACE, "Colossus Maul", 0x8A93A6, "boss"),
            new Secret("storm_fang", Items.DIAMOND_SWORD, "Storm Fang", 0xB8E8FF, "boss"),
            new Secret("forge_cleaver", Items.NETHERITE_AXE, "Forge Cleaver", 0xFF8A2E, "boss"),
            new Secret("dune_blade", Items.DIAMOND_SWORD, "Dune Blade", 0xE8C77A, "boss"),
            new Secret("glacier_axe", Items.DIAMOND_AXE, "Glacier Axe", 0xBFF4FF, "boss"),
            new Secret("thornspine", Items.DIAMOND_SWORD, "Thornspine", 0x6FD05A, "boss"),
            new Secret("hollow_edge", Items.NETHERITE_SWORD, "Hollow Edge", 0x9A7BD0, "boss"),
            new Secret("boiling_edge", Items.NETHERITE_SWORD, "Boiling Edge", 0xE0102E, "boss"),
            new Secret("hammer", Items.IRON_PICKAXE, "Hammer", 0xE0E0E0, "craft"),
            new Secret("lumber_axe", Items.IRON_AXE, "Lumber Axe", 0xE0B070, "craft"),
            new Secret("grappling_hook", Items.FISHING_ROD, "Grappling Hook", 0xC0C8D0, "craft"),
            new Secret("magnet_charm", Items.IRON_NUGGET, "Magnet Charm", 0xFF6060, "craft"),
            new Secret("ender_pouch", Items.RABBIT_HIDE, "Ender Pouch", 0xB05CFF, "craft"),
            new Secret("backpack", Items.LEATHER, "Backpack", 0xC8823C, "craft"));

    /** Every custom enchantment, as books at each level (and straight onto the held item). */
    public static void enchants(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "enchants.title"));
        m.renderer(menu -> {
            List<String[]> all = new ArrayList<>();
            for (String id : com.vylorq.anticheat.feature.CustomEnchants.ALL) {
                for (int lv = 1; lv <= com.vylorq.anticheat.feature.CustomEnchants.maxLevel(id); lv++) {
                    all.add(new String[]{id, String.valueOf(lv)});
                }
            }
            menu.list(all, e -> {
                        ItemStack b = com.vylorq.anticheat.feature.CustomEnchants.book(e[0], Integer.parseInt(e[1]));
                        return Btn.of(b).line(Msg.tr("enchants.desc." + e[0])).left(Msg.tr("enchants.take"))
                                .right(Msg.tr("enchants.apply")).build();
                    },
                    e -> (pl, c) -> {
                        int lv = Integer.parseInt(e[1]);
                        if (c.isRight()) {
                            boolean ok = com.vylorq.anticheat.feature.CustomEnchants.apply(pl.getMainHandStack(), e[0], lv);
                            Msg.send(pl, ok ? "enchants.applied" : "enchants.hold");
                        } else {
                            pl.getInventory().offerOrDrop(com.vylorq.anticheat.feature.CustomEnchants.book(e[0], lv));
                        }
                    },
                    e -> e[0], List.of(), Msg.tr("owner.items.none"), "");
        });
        m.open(p);
    }

    static ItemStack secretIcon(String id) {
        for (Secret x : SECRETS) {
            if (x.id().equals(id)) {
                return icon(x.base(), x.id());
            }
        }
        return new ItemStack(Items.BARRIER);
    }

    /** The owner's page of every secret item, the craftable tools and the boss weapons: click to take one. */
    public static void secrets(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "owner.secrets"));
        m.renderer(menu -> menu.list(SECRETS, x -> Btn.of(icon(x.base(), x.id())).color(x.colour()).name(x.name())
                        .line(Msg.tr("owner.secrets.from." + x.from())).left(Msg.tr("owner.secrets.take")).glint(true).build(),
                x -> (pl, c) -> com.vylorq.anticheat.feature.SecretItems.give(pl, x.id()),
                x -> x.name(), List.of(), Msg.tr("owner.items.none"), ""));
        m.open(p);
    }
}
