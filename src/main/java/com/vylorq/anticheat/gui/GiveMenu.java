package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.OwnerPowers;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's Give menu: pick a player, pick any item in the game, then set the amount, enchantments (any level up to
 * 255), a name and unbreakable before giving it.
 */
public final class GiveMenu {
    private GiveMenu() {
    }

    private static final class Draft {
        UUID target;
        ItemStack stack = ItemStack.EMPTY;
    }

    private static final Map<UUID, Draft> DRAFTS = new HashMap<>();

    private static Draft draft(ServerPlayerEntity p) {
        return DRAFTS.computeIfAbsent(p.getUuid(), x -> new Draft());
    }

    private static ServerPlayerEntity target(ServerPlayerEntity p) {
        UUID id = draft(p).target;
        return id == null ? null : Ac.server().getPlayerManager().getPlayer(id);
    }

    /** Step 1: who gets it. */
    public static void open(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "give.title"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.CHEST).name(Category.VIGIL, Msg.tr("give.title")).desc(Msg.tr("give.desc")).build());
            List<ServerPlayerEntity> all = new ArrayList<>(Ac.server().getPlayerManager().getPlayerList());
            menu.list(all, t -> Btn.head(t.getUuid(), t.getGameProfile().name()).color(Theme.GOLD_LIGHT).name(t.getGameProfile().name())
                            .left(Msg.tr("give.pick-player")).build(),
                    t -> (pl, c) -> {
                        draft(pl).target = t.getUuid();
                        items(pl);
                    }, t -> t.getGameProfile().name(), List.of(), Msg.tr("tracker.none"), "");
        });
        m.open(p);
    }

    /** Step 2: what item (every item in the game, searchable). */
    public static void items(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "give.title"), Msg.trFor(p, "give.pick-item"));
        m.renderer(menu -> {
            List<Item> all = new ArrayList<>();
            for (Item i : Registries.ITEM) {
                if (i != Items.AIR) {
                    all.add(i);
                }
            }
            menu.list(all, i -> Btn.of(i).left(Msg.tr("give.pick-item")).build(),
                    i -> (pl, c) -> {
                        draft(pl).stack = new ItemStack(i);
                        editor(pl);
                    }, i -> Registries.ITEM.getId(i).getPath() + " " + new ItemStack(i).getName().getString(),
                    List.of(), Msg.tr("owner.items.none"), "");
        });
        m.open(p);
    }

    private static ItemEnchantmentsComponent enchants(ItemStack s) {
        return s.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
    }

    private static void setLevel(ItemStack s, RegistryEntry<Enchantment> e, int level) {
        var b = new ItemEnchantmentsComponent.Builder(enchants(s));
        b.set(e, Math.max(0, Math.min(255, level)));
        s.set(DataComponentTypes.ENCHANTMENTS, b.build());
    }

    private static List<RegistryEntry.Reference<Enchantment>> allEnchants() {
        return Ac.server().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).streamEntries().toList();
    }

    /** Step 3: amount, enchantments, name, unbreakable, then give. */
    public static void editor(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "give.title"), Msg.trFor(p, "give.edit"));
        m.renderer(menu -> {
            Draft d = draft(p);
            ItemStack s = d.stack;
            if (s.isEmpty()) {
                items(p);
                return;
            }
            ServerPlayerEntity t = target(p);
            menu.info(s.copy());
            menu.set(13, s.copy(), null, (pl, c) -> {
            });
            menu.set(19, Btn.of(Items.RED_STAINED_GLASS_PANE).color(Theme.RED).name(Msg.tr("give.less"))
                    .left("-1").right("-16").build(), null, (pl, c) -> {
                s.setCount(Math.max(1, s.getCount() - (c.isRight() ? 16 : 1)));
                menu.refresh();
            });
            menu.set(20, Btn.of(Items.PAPER).color(Theme.GOLD_LIGHT).name(Msg.tr("give.amount", s.getCount()))
                    .left(Msg.tr("give.amount.max")).shift(Msg.tr("give.type-amount")).build(), null, (pl, c) -> {
                if (c.isShift()) {
                    Input.text(pl, Msg.trFor(pl, "give.amount", s.getCount()), String.valueOf(s.getCount()), txt -> {
                        try {
                            s.setCount(Math.max(1, Math.min(99, Integer.parseInt(txt.trim()))));
                        } catch (NumberFormatException e) {
                            Msg.send(pl, "general.bad-number");
                        }
                        editor(pl);
                    });
                    return;
                }
                s.setCount(Math.max(1, s.getMaxCount()));
                menu.refresh();
            });
            menu.set(21, Btn.of(Items.LIME_STAINED_GLASS_PANE).color(Theme.GREEN).name(Msg.tr("give.more"))
                    .left("+1").right("+16").build(), null, (pl, c) -> {
                s.setCount(Math.min(99, s.getCount() + (c.isRight() ? 16 : 1)));
                if (s.getCount() > s.getMaxCount()) {
                    s.set(DataComponentTypes.MAX_STACK_SIZE, Math.min(99, s.getCount()));
                }
                menu.refresh();
            });
            menu.set(23, Btn.of(Items.ENCHANTED_BOOK).color(Theme.GOLD_LIGHT).name(Msg.tr("give.enchants"))
                    .desc(Msg.tr("give.enchants.desc")).status(Theme.GOLD_LIGHT, String.valueOf(enchants(s).getSize()))
                    .left(Msg.tr("owner.open")).glint(true).build(), null, (pl, c) -> enchantPage(pl));
            menu.set(24, Btn.of(Items.NETHER_STAR).color(Theme.GOLD_LIGHT).name(Msg.tr("give.max-all"))
                    .desc(Msg.tr("give.max-all.desc")).left(Msg.tr("give.max-normal")).right(Msg.tr("give.max-255")).build(), null, (pl, c) -> {
                for (var e : allEnchants()) {
                    if (e.value().isAcceptableItem(s) && !e.isIn(net.minecraft.registry.tag.EnchantmentTags.CURSE)) {
                        setLevel(s, e, c.isRight() ? 255 : e.value().getMaxLevel());
                    }
                }
                menu.refresh();
            });
            menu.set(25, Btn.of(Items.GRINDSTONE).color(Theme.SOFT).name(Msg.tr("give.clear-enchants")).left(Msg.tr("give.clear-enchants")).build(),
                    null, (pl, c) -> {
                        s.remove(DataComponentTypes.ENCHANTMENTS);
                        menu.refresh();
                    });
            boolean unbreakable = s.contains(DataComponentTypes.UNBREAKABLE);
            menu.set(29, Btn.of(Items.ANVIL).color(unbreakable ? Theme.GREEN : Theme.SOFT).name(Msg.tr("give.unbreakable"))
                    .onOff(unbreakable).left(Msg.tr("owner.toggle")).build(), null, (pl, c) -> {
                if (unbreakable) {
                    s.remove(DataComponentTypes.UNBREAKABLE);
                } else {
                    s.set(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE);
                }
                menu.refresh();
            });
            menu.set(30, Btn.of(Items.NAME_TAG).color(Theme.GOLD_LIGHT).name(Msg.tr("give.rename")).left(Msg.tr("give.rename"))
                    .right(Msg.tr("give.rename.reset")).build(), null, (pl, c) -> {
                if (c.isRight()) {
                    s.remove(DataComponentTypes.CUSTOM_NAME);
                    menu.refresh();
                    return;
                }
                Input.text(pl, Msg.trFor(pl, "give.rename"), s.getName().getString(), txt -> {
                    if (!txt.isBlank()) {
                        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(txt.replace('&', '§')));
                    }
                    editor(pl);
                });
            });
            menu.set(31, Btn.of(Items.HOPPER).color(Theme.SOFT).name(Msg.tr("give.other-item")).left(Msg.tr("owner.open")).build(),
                    null, (pl, c) -> items(pl));
            menu.set(32, Btn.of(Items.PLAYER_HEAD).color(Theme.SOFT).name(Msg.tr("give.other-player")).left(Msg.tr("owner.open")).build(),
                    null, (pl, c) -> open(pl));
            String who = t == null ? "-" : t.getGameProfile().name();
            menu.set(33, Btn.of(Items.LIME_CONCRETE).color(Theme.GREEN).name(Msg.tr("give.give", who))
                    .left(Msg.tr("give.give", who)).right(Msg.tr("give.give-self")).glint(true).build(), null, (pl, c) -> {
                ServerPlayerEntity to = c.isRight() ? pl : target(pl);
                if (to == null) {
                    Msg.send(pl, "give.offline");
                    return;
                }
                to.getInventory().offerOrDrop(s.copy());
                Msg.send(pl, "give.given", s.getCount(), s.getName().getString(), to.getGameProfile().name());
            });
        });
        m.open(p);
    }

    /** Every enchantment in the game: left +1, right -1, shift for 10 at a time, up to 255. */
    public static void enchantPage(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "give.title"), Msg.trFor(p, "give.enchants"));
        m.renderer(menu -> {
            ItemStack s = draft(p).stack;
            if (s.isEmpty()) {
                items(p);
                return;
            }
            menu.info(s.copy());
            menu.list(allEnchants(), e -> {
                        int lv = enchants(s).getLevel(e);
                        Btn b = Btn.of(Items.ENCHANTED_BOOK).color(lv > 0 ? Theme.GREEN : Theme.SOFT)
                                .name(Enchantment.getName(e, Math.max(1, lv)).getString())
                                .status(lv > 0 ? Theme.GREEN : Theme.SOFT, Msg.tr("give.level", lv))
                                .left("+1").right("-1").shift("±10").glint(lv > 0);
                        if (!e.value().isAcceptableItem(s)) {
                            b.line(Msg.tr("give.not-normal"));
                        }
                        return b.build();
                    },
                    e -> (pl, c) -> {
                        int step = (c.isShift() ? 10 : 1) * (c.isRight() ? -1 : 1);
                        setLevel(s, e, enchants(s).getLevel(e) + step);
                        menu.refresh();
                    }, e -> e.getIdAsString() + " " + Enchantment.getName(e, 1).getString(), List.of(), Msg.tr("owner.items.none"), "");
        });
        m.open(p);
    }
}
