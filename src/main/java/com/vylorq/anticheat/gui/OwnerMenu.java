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
        s.set(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA,
                new net.minecraft.component.type.CustomModelDataComponent(List.of(), List.of(), List.of("vigil:" + id), List.of()));
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
            for (int i = 0; i < POWERS.length; i++) {
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
            menu.set(40, Btn.of(icon(Items.PAINTING, "icon_pack")).color(Theme.SOFT).name(Msg.tr("owner.pack")).desc(Msg.tr("owner.pack.desc"))
                    .left(Msg.tr("owner.pack-send")).build(), null, (pl, c) -> OwnerPowers.sendPack(pl));
        });
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
}
