package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.OwnerPowers;
import com.vylorq.anticheat.feature.OwnerWorld;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.ui.Theme.Category;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The owner's World Tools page and Player Tracker. */
public final class OwnerWorldMenu {
    private OwnerWorldMenu() {
    }

    private static final Item[] BUILD_ICONS = {Items.OAK_DOOR, Items.STONE_BRICKS, Items.SPRUCE_FENCE, Items.WATER_BUCKET, Items.LADDER};

    public static void world(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "wmenu.title"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.GRASS_BLOCK).name(Category.VIGIL, Msg.tr("wmenu.title")).desc(Msg.tr("wmenu.desc")).build());
            menu.set(11, OwnerWorld.brush(), null, (pl, c) -> pl.getInventory().offerOrDrop(OwnerWorld.brush()));
            menu.set(12, Btn.of(Items.COMPASS).color(Theme.GOLD_LIGHT).name(Msg.tr("wmenu.radius"))
                    .status(Theme.GOLD_LIGHT, String.valueOf(OwnerWorld.radius(p))).left(Msg.tr("wmenu.bigger")).right(Msg.tr("wmenu.smaller")).build(),
                    null, (pl, c) -> {
                        OwnerWorld.setRadius(pl, Math.max(1, Math.min(16, OwnerWorld.radius(pl) + (c.isRight() ? -1 : 1))));
                        menu.refresh();
                    });
            menu.set(14, OwnerWorld.treeSpawner(), null, (pl, c) -> pl.getInventory().offerOrDrop(OwnerWorld.treeSpawner()));
            menu.set(15, Btn.of(Items.RECOVERY_COMPASS).color(Theme.GREEN).name(Msg.tr("wmenu.undo")).desc(Msg.tr("wmenu.undo.desc"))
                    .status(Theme.GREEN, String.valueOf(OwnerWorld.undoCount(p))).left(Msg.tr("wmenu.undo")).build(), null, (pl, c) -> {
                OwnerWorld.undo(pl);
                menu.refresh();
            });
            int[] slots = {20, 21, 22, 23, 24};
            for (int i = 0; i < OwnerWorld.BUILDS.size(); i++) {
                String b = OwnerWorld.BUILDS.get(i);
                menu.set(slots[i], Btn.of(BUILD_ICONS[i]).color(Theme.GOLD_LIGHT).name(Msg.tr("wmenu.build." + b))
                        .desc(Msg.tr("wmenu.build.desc")).left(Msg.tr("wmenu.build")).build(), null, (pl, c) -> {
                    OwnerWorld.build(pl, b);
                    pl.closeHandledScreen();
                });
            }
            int r = OwnerWorld.resetRadius(p);
            menu.set(30, Btn.of(Items.STRUCTURE_VOID).color(Theme.RED).name(Msg.tr("wmenu.reset")).desc(Msg.tr("wmenu.reset.desc"))
                    .status(Theme.GOLD_LIGHT, Msg.tr("wmenu.reset.size", (2 * r + 1) * 16)).left(Msg.tr("wmenu.bigger")).right(Msg.tr("wmenu.smaller")).build(),
                    null, (pl, c) -> {
                        OwnerWorld.setResetRadius(pl, OwnerWorld.resetRadius(pl) + (c.isRight() ? -1 : 1));
                        menu.refresh();
                    });
            menu.set(31, Btn.of(Items.TNT).color(Theme.RED).name(Msg.tr("wmenu.reset.queue")).desc(Msg.tr("wmenu.reset.queue.desc"))
                    .left(Msg.tr("wmenu.reset.queue")).build(), null, (pl, c) -> Confirm.open(pl, Category.VIGIL, Msg.trFor(pl, "wmenu.reset.queue"),
                    Msg.trFor(pl, "wmenu.reset.queue.desc"), new ItemStack(Items.TNT), () -> OwnerWorld.queueReset(pl, OwnerWorld.resetRadius(pl))));
            boolean xray = com.vylorq.anticheat.feature.OwnerXray.on(p);
            menu.set(33, Btn.of(Items.DIAMOND_ORE).color(xray ? Theme.GREEN : Theme.SOFT).name(Msg.tr("xray.title"))
                    .desc(Msg.tr("xray.desc")).onOff(xray).left(Msg.tr("owner.toggle")).glint(xray).build(), null, (pl, c) -> {
                com.vylorq.anticheat.feature.OwnerXray.toggle(pl);
                menu.refresh();
            });
            menu.set(32, Btn.of(Items.BARRIER).color(Theme.SOFT).name(Msg.tr("wmenu.reset.cancel")).left(Msg.tr("wmenu.reset.cancel")).build(),
                    null, (pl, c) -> OwnerWorld.cancelReset(pl));
        });
        m.open(p);
    }

    /** Every online player with where they are and how they're doing at a glance; click for the full /inspect. */
    public static void tracker(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Menu m = Menu.std(Category.VIGIL, Msg.trFor(p, "owner.title"), Msg.trFor(p, "tracker.title")).live();
        m.renderer(menu -> {
            List<ServerPlayerEntity> all = new ArrayList<>(Ac.server().getPlayerManager().getPlayerList());
            menu.list(all, t -> {
                ItemStack held = t.getMainHandStack();
                return Btn.head(t.getUuid(), t.getGameProfile().name()).color(Theme.GOLD_LIGHT).name(t.getGameProfile().name())
                        .line(Msg.tr("tracker.world", Mc.worldId(t.getEntityWorld())))
                        .line(Msg.tr("tracker.pos", (int) t.getX(), (int) t.getY(), (int) t.getZ()))
                        .line(Msg.tr("tracker.health", Math.round(t.getHealth()), Math.round(t.getMaxHealth()), t.getHungerManager().getFoodLevel()))
                        .line(Msg.tr("tracker.armor", t.getArmor(), armor(t)))
                        .line(Msg.tr("tracker.held", held.isEmpty() ? "-" : held.getName().getString()))
                        .line(Msg.tr("tracker.mode", t.interactionManager.getGameMode().name().toLowerCase(Locale.ROOT), t.experienceLevel))
                        .line(Msg.tr("in.ping", t.networkHandler.getLatency()))
                        .left(Msg.tr("tracker.inspect")).build();
            }, t -> (pl, c) -> InspectMenu.open(pl, t.getUuid()), t -> t.getGameProfile().name(), List.of(), Msg.tr("tracker.none"), "");
        });
        m.open(p);
    }

    private static int armor(ServerPlayerEntity t) {
        int n = 0;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!t.getEquippedStack(s).isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
