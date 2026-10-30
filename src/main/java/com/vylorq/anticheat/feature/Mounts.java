package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Rarity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mounts: a Mount Whistle (found in the structures) calls your own mount, saddled and ready, and puts you on it; blow
 * it again to send it home. The Storm Steed is a very fast horse, the Bone Stallion a skeleton horse that walks
 * underwater, the Sky Ghast a happy ghast that flies. Only the owner can ride; it goes home when they leave.
 */
public final class Mounts {
    private Mounts() {
    }

    static final String TOKEN = "vigil_mount_token";
    static final String TAG = "vigil_mount";
    public static final List<String> KINDS = List.of("steed", "stallion", "ghast");

    private static final Map<UUID, MobEntity> MOUNTS = new HashMap<>();
    private static final Map<UUID, Long> FELL = new HashMap<>();

    private static String title(String kind) {
        return switch (kind) {
            case "steed" -> "§bStorm Steed";
            case "stallion" -> "§fBone Stallion";
            default -> "§dSky Ghast";
        };
    }

    public static ItemStack whistle(String kind) {
        ItemStack s = new ItemStack(switch (kind) {
            case "steed" -> Items.GOAT_HORN;
            case "stallion" -> Items.BONE_MEAL;
            default -> Items.GHAST_TEAR;
        });
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title(kind) + " Whistle").styled(x -> x.withItalic(false)));
        String what = switch (kind) {
            case "steed" -> "§7A horse faster than any other, and a great jumper.";
            case "stallion" -> "§7A skeleton horse: tough, and walks underwater.";
            default -> "§7A happy ghast that carries you through the sky.";
        };
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(what).styled(x -> x.withItalic(false)),
                Text.literal("§8Use: call your mount (again: send it home).").styled(x -> x.withItalic(false)))));
        s.set(DataComponentTypes.RARITY, Rarity.EPIC);
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        // A goat horn would play its own sound and wait: this one is only a whistle.
        s.remove(DataComponentTypes.INSTRUMENT);
        ItemConv.setTag(s, TOKEN, kind);
        return s;
    }

    private static void base(MobEntity m, RegistryEntry<EntityAttribute> a, double v) {
        var i = m.getAttributeInstance(a);
        if (i != null) {
            i.setBaseValue(v);
        }
    }

    private static void use(ServerPlayerEntity p, String kind) {
        MobEntity old = MOUNTS.remove(p.getUuid());
        if (old != null && !old.isRemoved()) {
            old.discard();
            Msg.actionBar(p, "§7" + Msg.trFor(p, "mounts.home"));
            return;
        }
        ServerWorld w = p.getEntityWorld();
        long fell = FELL.getOrDefault(p.getUuid(), 0L);
        if (w.getTime() < fell) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "mounts.resting", (fell - w.getTime()) / 20 + 1));
            return;
        }
        if (LobbyFeature.in(w, p.getBlockPos())) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "mounts.not-here"));
            return;
        }
        MobEntity m = switch (kind) {
            case "steed" -> EntityType.HORSE.create(w, net.minecraft.entity.SpawnReason.MOB_SUMMONED);
            case "stallion" -> EntityType.SKELETON_HORSE.create(w, net.minecraft.entity.SpawnReason.MOB_SUMMONED);
            default -> EntityType.HAPPY_GHAST.create(w, net.minecraft.entity.SpawnReason.MOB_SUMMONED);
        };
        if (m == null) {
            return;
        }
        m.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), p.getYaw(), 0);
        if (m instanceof AbstractHorseEntity h) {
            h.setTame(true);
            h.bondWithPlayer(p);
            h.equipStack(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
            base(h, EntityAttributes.MAX_HEALTH, kind.equals("steed") ? 40 : 50);
            base(h, EntityAttributes.MOVEMENT_SPEED, kind.equals("steed") ? 0.36 : 0.3);
            base(h, EntityAttributes.JUMP_STRENGTH, kind.equals("steed") ? 1.05 : 0.8);
        } else {
            m.equipStack(EquipmentSlot.BODY, new ItemStack(Items.LIGHT_BLUE_HARNESS));
            base(m, EntityAttributes.MAX_HEALTH, 40);
            base(m, EntityAttributes.FLYING_SPEED, 0.09);
        }
        m.setHealth(m.getMaxHealth());
        m.addCommandTag(TAG);
        m.addCommandTag(TAG + ":" + p.getUuidAsString());
        m.setCustomName(Text.literal(title(kind) + " §7(" + p.getGameProfile().name() + ")"));
        m.setCustomNameVisible(false);
        MOUNTS.put(p.getUuid(), m); // first, so it isn't taken for a leftover as it loads
        w.spawnEntity(m);
        p.startRiding(m, true, true);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_HORSE_SADDLE.value(), SoundCategory.PLAYERS, 0.8f, 1.2f);
        Msg.actionBar(p, "§a" + Msg.trFor(p, "mounts.called"));
    }

    private static UUID ownerOf(net.minecraft.entity.Entity e) {
        for (String t : e.getCommandTags()) {
            if (t.startsWith(TAG + ":")) {
                try {
                    return UUID.fromString(t.substring(TAG.length() + 1));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Twice a second: a mount left behind (its owner far away and not riding) goes home. */
    public static void tick(long ticks) {
        if (ticks % 10 != 0 || MOUNTS.isEmpty()) {
            return;
        }
        var it = MOUNTS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            MobEntity m = e.getValue();
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(e.getKey());
            if (m.isRemoved() || !m.isAlive()) {
                if (!m.isAlive() && p != null) {
                    FELL.put(p.getUuid(), p.getEntityWorld().getTime() + 6000);
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "mounts.fell"));
                }
                it.remove();
                continue;
            }
            if (p == null || (m.getFirstPassenger() != p && (m.getEntityWorld() != p.getEntityWorld() || m.squaredDistanceTo(p) > 48 * 48))) {
                m.discard();
                it.remove();
                continue;
            }
            // Nobody else rides it.
            for (var passenger : List.copyOf(m.getPassengerList())) {
                if (passenger != p) {
                    passenger.stopRiding();
                }
            }
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p) {
                String kind = ItemConv.tag(p.getStackInHand(hand), TOKEN);
                if (kind != null && KINDS.contains(kind)) {
                    use(p, kind);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (Ac.running() && entity.getCommandTags().contains(TAG) && !player.getUuid().equals(ownerOf(entity))) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MobEntity m = MOUNTS.remove(handler.player.getUuid());
            if (m != null && !m.isRemoved()) {
                m.discard();
            }
        });
        // Mounts left in the world from before a restart go home.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e instanceof MobEntity m && m.getCommandTags().contains(TAG) && !MOUNTS.containsValue(m)) {
                OwnerPowers.later(1, m::discard);
            }
        });
    }
}
