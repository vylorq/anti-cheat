package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.decoration.painting.PaintingVariant;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapDecorationTypes;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Even more of The Boiled One: sounds that aren't there, water boiling where it stands, its face in your item
 * frames and paintings, a second shadow behind you, waking up unable to move, villagers that have seen it, and a red
 * X on your maps where it last saw you.
 */
public final class BoiledDread {
    private BoiledDread() {
    }

    // ---------------------------------------------------------------- wrong sounds

    private static final SoundEvent[] WRONG = {SoundEvents.ENTITY_CREEPER_PRIMED, SoundEvents.BLOCK_CHEST_OPEN,
            SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundEvents.ENTITY_TNT_PRIMED, SoundEvents.ENTITY_ZOMBIE_AMBIENT,
            SoundEvents.BLOCK_STONE_BREAK, SoundEvents.ENTITY_PLAYER_HURT, SoundEvents.ENTITY_SPIDER_AMBIENT,
            SoundEvents.BLOCK_GRAVEL_STEP, SoundEvents.ENTITY_SKELETON_AMBIENT};

    /** Something right behind them: a creeper's hiss, a chest opening, a door closing... and nothing is there. */
    static void wrongSound(ServerPlayerEntity p) {
        Vec3d back = p.getRotationVec(1f).multiply(-1, 0, -1);
        if (back.lengthSquared() < 1e-4) {
            back = new Vec3d(1, 0, 0);
        }
        Vec3d at = p.getEntityPos().add(back.normalize().multiply(2.5 + p.getRandom().nextDouble() * 2)).add(0, 0.5, 0);
        SoundEvent s = WRONG[p.getRandom().nextInt(WRONG.length)];
        BoiledOmens.sound(p, s, at, 1f, 1f);
        if (s == SoundEvents.BLOCK_GRAVEL_STEP) {
            for (int i = 1; i < 4; i++) {
                OwnerPowers.later(7 * i, () -> BoiledOmens.sound(p, s, at, 0.8f, 1f));
            }
        }
    }

    // ---------------------------------------------------------------- boiling water

    /** The water near it bubbles, and anyone standing in it is scalded. */
    private static void boil(ServerWorld w, Entity it) {
        BlockPos c = it.getBlockPos();
        int found = 0;
        for (BlockPos b : BlockPos.iterate(c.add(-6, -2, -6), c.add(6, 2, 6))) {
            if (found > 24) {
                break;
            }
            if (w.getFluidState(b).isStill() && w.getFluidState(b).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                    && w.getBlockState(b.up()).isAir()) {
                found++;
                w.spawnParticles(ParticleTypes.BUBBLE_POP, b.getX() + 0.5, b.getY() + 0.95, b.getZ() + 0.5, 3, 0.3, 0.02, 0.3, 0.01);
                w.spawnParticles(ParticleTypes.CLOUD, b.getX() + 0.5, b.getY() + 1.05, b.getZ() + 0.5, 1, 0.3, 0.05, 0.3, 0.005);
            }
        }
        if (found > 0) {
            w.playSound(null, c, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, net.minecraft.sound.SoundCategory.BLOCKS, 0.6f, 0.6f);
        }
        for (ServerPlayerEntity p : w.getPlayers(pl -> pl.isTouchingWater() && pl.squaredDistanceTo(it) < 10 * 10 && !pl.isCreative()
                && !pl.isSpectator())) {
            p.damage(w, w.getDamageSources().hotFloor(), 2f);
            Msg.actionBar(p, "§c" + Msg.trFor(p, "boiled.scalded"));
        }
    }

    // ---------------------------------------------------------------- its face in frames and paintings

    private static final String[] CREEPY = {"skull_and_roses", "wither", "burning_skull", "skeleton", "donkey_kong", "pointer", "pigscene"};

    /** For a moment, every item frame around them holds its face, and the paintings change. Only they see it. */
    static void reflection(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Box box = p.getBoundingBox().expand(12);
        ItemStack face = new ItemStack(Items.NAUTILUS_SHELL);
        com.vylorq.anticheat.util.PackIds.apply(face, "boiled_one__head");
        int n = 0;
        for (ItemFrameEntity f : w.getEntitiesByClass(ItemFrameEntity.class, box, e -> true)) {
            n++;
            send(p, f, com.vylorq.anticheat.mixin.ItemFrameAccessor.ac$item(), face.copy());
            OwnerPowers.later(8, () -> send(p, f, com.vylorq.anticheat.mixin.ItemFrameAccessor.ac$item(), f.getHeldItemStack().copy()));
        }
        var reg = w.getRegistryManager().getOrThrow(RegistryKeys.PAINTING_VARIANT);
        for (PaintingEntity pt : w.getEntitiesByClass(PaintingEntity.class, box, e -> true)) {
            RegistryEntry<PaintingVariant> real = pt.getVariant();
            RegistryEntry<PaintingVariant> scary = null;
            for (String id : CREEPY) {
                var e = reg.getEntry(Identifier.ofVanilla(id));
                if (e.isPresent() && e.get().value().width() == real.value().width() && e.get().value().height() == real.value().height()
                        && !e.get().equals(real)) {
                    scary = e.get();
                    break;
                }
            }
            if (scary == null) {
                continue;
            }
            n++;
            send(p, pt, com.vylorq.anticheat.mixin.PaintingAccessor.ac$variant(), scary);
            OwnerPowers.later(8, () -> send(p, pt, com.vylorq.anticheat.mixin.PaintingAccessor.ac$variant(), pt.getVariant()));
        }
        if (n > 0) {
            BoiledOmens.pack(p, "boiled_static", p.getEyePos(), 0.6f, 1.2f);
        } else {
            BoiledOne.glimpse(p);
        }
    }

    private static <T> void send(ServerPlayerEntity p, Entity e, net.minecraft.entity.data.TrackedData<T> key, T value) {
        if (!e.isRemoved()) {
            p.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(e.getId(), List.of(DataTracker.SerializedEntry.of(key, value))));
        }
    }

    // ---------------------------------------------------------------- a second shadow

    private static final class Shadow {
        final UUID player;
        final UUID display;
        final ServerWorld world;
        final long until;
        final ArrayDeque<Vec3d> trail = new ArrayDeque<>();

        Shadow(UUID player, UUID display, ServerWorld world, long until) {
            this.player = player;
            this.display = display;
            this.world = world;
            this.until = until;
        }
    }

    private static final Map<UUID, Shadow> SHADOWS = new ConcurrentHashMap<>();
    static final String SHADOW_TAG = "vigil_boiled_shadow";

    /** A dark shape on the ground, following a little behind them, wherever they go. Then it's gone. */
    static boolean shadow(ServerPlayerEntity p) {
        if (SHADOWS.containsKey(p.getUuid())) {
            return false;
        }
        ServerWorld w = p.getEntityWorld();
        UUID id = UUID.randomUUID();
        Vec3d at = p.getEntityPos();
        String cmd = String.format(java.util.Locale.ROOT,
                "summon minecraft:text_display %.3f %.3f %.3f {UUID:%s,Tags:[\"%s\"],teleport_duration:3,billboard:\"fixed\",text:\" \","
                        + "background:-1694498816,brightness:{sky:0,block:0},shadow_radius:0f,"
                        + "transformation:{left_rotation:[-0.7071f,0f,0f,0.7071f],right_rotation:[0f,0f,0f,1f],"
                        + "translation:[0f,0.03f,0f],scale:[9f,22f,1f]}}",
                at.x, at.y, at.z, OwnerCombat.Display.uuidNbt(id), SHADOW_TAG);
        try {
            Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(), cmd);
        } catch (Exception e) {
            return false;
        }
        SHADOWS.put(p.getUuid(), new Shadow(p.getUuid(), id, w, w.getTime() + 20 * 40));
        return true;
    }

    private static void shadows() {
        for (Shadow s : SHADOWS.values().toArray(new Shadow[0])) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(s.player);
            Entity d = s.world.getEntity(s.display);
            if (p == null || d == null || p.getEntityWorld() != s.world || s.world.getTime() > s.until || !p.isAlive()) {
                SHADOWS.remove(s.player);
                if (d != null) {
                    d.discard();
                }
                continue;
            }
            // Where they stood a moment ago, on the ground.
            s.trail.addLast(p.getEntityPos());
            if (s.trail.size() < 12) {
                continue;
            }
            Vec3d was = s.trail.removeFirst();
            Vec3d dir = p.getEntityPos().subtract(was).multiply(1, 0, 1);
            float yaw = dir.lengthSquared() > 1e-3 ? (float) (MathHelper.atan2(dir.z, dir.x) * 57.2958) - 90f : d.getYaw();
            BlockPos ground = BlockPos.ofFloored(was);
            double y = was.y;
            if (p.isOnGround()) {
                y = Math.floor(y + 0.01);
            }
            d.refreshPositionAndAngles(was.x, y, was.z, yaw + 180, 0);
            if (s.world.getRandom().nextInt(100) == 0) {
                BoiledOmens.sound(p, SoundEvents.ENTITY_PHANTOM_FLAP, Vec3d.ofCenter(ground), 0.3f, 0.5f);
            }
        }
    }

    // ---------------------------------------------------------------- sleep paralysis

    /** One night's sleep in this many (one in 3 for those it follows or during The Boiling Night). */
    static final int SLEEP_ODDS = 8;

    private static void slept(ServerPlayerEntity p, BlockPos bed) {
        OwnerPowers.later(50, () -> {
            if (p.isRemoved() || !p.isSleeping() || !BoiledOne.enabled() || !BoiledOne.huntable(p) || BoiledOmens.isProtected(p)
                    || BoiledFight.wearsFullSet(p) || com.vylorq.anticheat.perm.Perms.isOwner(p.getUuid())) {
                return;
            }
            boolean focus = BoiledOne.huntedPlayers().containsKey(p.getUuidAsString()) || BoiledOne.eventOn();
            if (p.getRandom().nextInt(focus ? 3 : SLEEP_ODDS) != 0) {
                return;
            }
            paralyse(p, bed);
        });
    }

    /** They wake up and can't move. It's standing at the end of the bed. */
    static boolean paralyse(ServerPlayerEntity p, BlockPos bed) {
        ServerWorld w = p.getEntityWorld();
        BlockState st = bed == null ? null : w.getBlockState(bed);
        Direction facing = st != null && st.getBlock() instanceof BedBlock ? st.get(BedBlock.FACING) : p.getHorizontalFacing().getOpposite();
        BlockPos head = st != null && st.getBlock() instanceof BedBlock && st.get(BedBlock.PART) == BedPart.FOOT ? bed.offset(facing)
                : bed != null ? bed : p.getBlockPos();
        if (p.isSleeping()) {
            p.wakeUp(true, true);
        }
        // The foot of the bed is one block back from the head; it stands just past it.
        BlockPos foot = head.offset(facing.getOpposite());
        return BoiledOne.paralysis(p, foot.offset(facing.getOpposite())) != null;
    }

    // ---------------------------------------------------------------- villagers that have seen it

    static final String TOUCHED = "vigil_boiled_seen";

    /** Villagers near where it stood have seen it: they'll never be the same. */
    static void touch(ServerWorld w, Vec3d at) {
        for (VillagerEntity v : w.getEntitiesByClass(VillagerEntity.class, new Box(at.add(-24, -8, -24), at.add(24, 8, 24)),
                e -> !e.getCommandTags().contains(BoiledHaunts.MIMIC_TAG))) {
            v.addCommandTag(TOUCHED);
        }
    }

    private static final String[] MUTTER = {"boiled.villager.1", "boiled.villager.2", "boiled.villager.3", "boiled.villager.4"};

    private static void villagers(long now) {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (p.isSpectator() || !World.OVERWORLD.equals(p.getEntityWorld().getRegistryKey())) {
                continue;
            }
            for (VillagerEntity v : p.getEntityWorld().getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(12),
                    e -> e.getCommandTags().contains(TOUCHED))) {
                // It stares. Always.
                Vec3d d = p.getEyePos().subtract(v.getEyePos());
                float yaw = (float) (MathHelper.atan2(d.z, d.x) * 57.2958) - 90f;
                float pitch = (float) -(MathHelper.atan2(d.y, d.horizontalLength()) * 57.2958);
                v.getLookControl().lookAt(p, 360, 360);
                v.setHeadYaw(yaw);
                v.setPitch(pitch);
                if (now % 400 == 0 && p.getRandom().nextInt(3) == 0 && v.squaredDistanceTo(p) < 6 * 6) {
                    Msg.actionBar(p, "§8" + Msg.trFor(p, "boiled.villager-says") + " §7§o" + Msg.trFor(p, MUTTER[p.getRandom().nextInt(MUTTER.length)]));
                    BoiledOmens.sound(p, SoundEvents.ENTITY_VILLAGER_AMBIENT, v.getEyePos(), 0.6f, 0.5f);
                }
            }
        }
    }

    // ---------------------------------------------------------------- a red X on their maps

    /** Every map they carry gets a red X where it last saw them. */
    static void markMaps(ServerPlayerEntity p, BlockPos where) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.FILLED_MAP)) {
                MapState.addDecorationsNbt(s, where, "vigil_boiled", MapDecorationTypes.RED_X);
            }
        }
    }

    // ---------------------------------------------------------------- every tick

    static void tick(long now) {
        if (!SHADOWS.isEmpty()) {
            shadows();
        }
        if (now % 2 == 0) {
            villagers(now);
        }
        if (now % 20 == 0) {
            for (var e : BoiledOne.hunting().values()) {
                if (e.getEntityWorld() instanceof ServerWorld w) {
                    boil(w, e);
                }
            }
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents.START_SLEEPING.register((e, pos) -> {
            if (Ac.running() && e instanceof ServerPlayerEntity p) {
                slept(p, pos);
            }
        });
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && entity instanceof VillagerEntity v
                    && v.getCommandTags().contains(TOUCHED) && BoiledOne.night(world)) {
                // At night it won't trade. It only stares.
                Msg.actionBar(p, "§8" + Msg.trFor(p, "boiled.villager-says") + " §7§o" + Msg.trFor(p, "boiled.villager.no"));
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e.getCommandTags().contains(SHADOW_TAG) && SHADOWS.values().stream().noneMatch(s -> s.display.equals(e.getUuid()))) {
                OwnerPowers.later(1, e::discard);       // left over from before a restart
            }
        });
    }

    public static void markMapsForTest(ServerPlayerEntity p, BlockPos where) {
        markMaps(p, where);
    }

    public static boolean markedForTest(ItemStack map) {
        var d = map.get(DataComponentTypes.MAP_DECORATIONS);
        return d != null && d.decorations().containsKey("vigil_boiled");
    }
}
