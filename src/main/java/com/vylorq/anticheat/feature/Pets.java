package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pets: a Wolf, a Fox or a little Iron Golem that follows its owner everywhere and fights the monsters that go for
 * them (never players). Each comes from its own Pet Token (crafted): use it to call your pet, use it again to send it
 * home. One pet at a time; it goes home when you leave, and comes back a minute after it falls.
 */
public final class Pets {
    private Pets() {
    }

    static final String TOKEN = "vigil_pet_token";
    static final String TAG = "vigil_pet";
    public static final List<String> KINDS = List.of("wolf", "fox", "golem");

    private static final Map<UUID, MobEntity> PETS = new HashMap<>();
    private static final Map<UUID, Long> FELL = new HashMap<>();

    public static ItemStack token(String kind) {
        ItemStack s = new ItemStack(switch (kind) {
            case "wolf" -> Items.BONE;
            case "fox" -> Items.RABBIT_FOOT;
            default -> Items.IRON_NUGGET;
        });
        String name = switch (kind) {
            case "wolf" -> "§fWolf Pet";
            case "fox" -> "§6Fox Pet";
            default -> "§7Golem Pet";
        };
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Use: call your pet (use again: send it home).").styled(x -> x.withItalic(false)),
                Text.literal("§7It follows you and fights monsters for you.").styled(x -> x.withItalic(false)))));
        s.set(DataComponentTypes.RARITY, Rarity.RARE);
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        ItemConv.setTag(s, TOKEN, kind);
        return s;
    }

    private static void use(ServerPlayerEntity p, String kind) {
        MobEntity old = PETS.remove(p.getUuid());
        if (old != null && !old.isRemoved()) {
            old.discard();
            Msg.actionBar(p, "§7" + Msg.trFor(p, "pets.home"));
            return;
        }
        ServerWorld w = p.getEntityWorld();
        long fell = FELL.getOrDefault(p.getUuid(), 0L);
        if (w.getTime() < fell) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "pets.resting", (fell - w.getTime()) / 20 + 1));
            return;
        }
        MobEntity m = switch (kind) {
            case "wolf" -> EntityType.WOLF.create(w, SpawnReason.MOB_SUMMONED);
            case "fox" -> EntityType.FOX.create(w, SpawnReason.MOB_SUMMONED);
            default -> EntityType.IRON_GOLEM.create(w, SpawnReason.MOB_SUMMONED);
        };
        if (m == null) {
            return;
        }
        m.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ(), p.getYaw(), 0);
        if (m instanceof WolfEntity wolf) {
            wolf.setTamedBy(p);
        }
        if (m instanceof IronGolemEntity g) {
            g.setPlayerCreated(true);
            var sc = g.getAttributeInstance(EntityAttributes.SCALE);
            if (sc != null) {
                sc.addPersistentModifier(new EntityAttributeModifier(Identifier.of("vigil", "pet_size"), -0.5,
                        EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        }
        // Stronger than wild ones: more health and harder bites.
        boost(m, EntityAttributes.MAX_HEALTH, 1.0);
        boost(m, EntityAttributes.ATTACK_DAMAGE, 0.5);
        boost(m, EntityAttributes.MOVEMENT_SPEED, 0.2);
        m.setHealth(m.getMaxHealth());
        m.addCommandTag(TAG);
        m.addCommandTag(TAG + ":" + p.getUuidAsString());
        m.setCustomName(Text.literal("§b" + p.getGameProfile().name() + "'s " + switch (kind) {
            case "wolf" -> "Wolf";
            case "fox" -> "Fox";
            default -> "Golem";
        }));
        m.setCustomNameVisible(true);
        PETS.put(p.getUuid(), m); // first, so it isn't taken for a leftover as it loads
        w.spawnEntity(m);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.6f);
        Msg.actionBar(p, "§a" + Msg.trFor(p, "pets.called"));
    }

    private static void boost(MobEntity m, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> a, double v) {
        var i = m.getAttributeInstance(a);
        if (i != null) {
            i.addPersistentModifier(new EntityAttributeModifier(Identifier.of("vigil", "pet"), v, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** Twice a second: pets keep up with their owners and go for what's going for them. */
    public static void tick(long ticks) {
        if (ticks % 10 != 0 || PETS.isEmpty()) {
            return;
        }
        var it = PETS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            MobEntity m = e.getValue();
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(e.getKey());
            if (m.isRemoved() || !m.isAlive()) {
                if (!m.isAlive() && p != null) {
                    FELL.put(p.getUuid(), p.getEntityWorld().getTime() + 1200);
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "pets.fell"));
                }
                it.remove();
                continue;
            }
            if (p == null) {
                m.discard();
                it.remove();
                continue;
            }
            // Never against players (or other pets).
            LivingEntity target = m.getTarget();
            if (target instanceof PlayerEntity || (target != null && target.getCommandTags().contains(TAG))) {
                m.setTarget(null);
                target = null;
            }
            double d = m.squaredDistanceTo(p);
            if (m.getEntityWorld() != p.getEntityWorld() || d > 32 * 32) {
                com.vylorq.anticheat.util.Mc.teleport(m, p.getEntityWorld(), p.getX(), p.getY(), p.getZ(), p.getYaw(), 0);
                continue;
            }
            // What's going for the owner, or what the owner is hitting.
            if (target == null || !target.isAlive()) {
                LivingEntity foe = p.getAttacking() instanceof HostileEntity h && h.isAlive() ? h : null;
                if (foe == null) {
                    for (HostileEntity h : p.getEntityWorld().getEntitiesByClass(HostileEntity.class, new Box(p.getBlockPos()).expand(10),
                            h -> h.isAlive() && h.getTarget() == p)) {
                        foe = h;
                        break;
                    }
                }
                if (foe != null) {
                    m.setTarget(foe);
                } else if (d > 6 * 6) {
                    m.getNavigation().startMovingTo(p, 1.3);
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
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MobEntity m = PETS.remove(handler.player.getUuid());
            if (m != null && !m.isRemoved()) {
                m.discard();
            }
        });
        // Pets left in the world from before a restart go home.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e instanceof MobEntity m && m.getCommandTags().contains(TAG) && !PETS.containsValue(m)) {
                OwnerPowers.later(1, m::discard);
            }
        });
    }
}
