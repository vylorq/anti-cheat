package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The summoning ritual: a chiseled polished blackstone altar ringed by eight lit candles, at night. Offer it a bone
 * and the candles go out one by one, and it rises from the altar to fight whoever is there, right in the overworld.
 */
public final class BoiledRitual {
    private BoiledRitual() {
    }

    static final String TAG = "vigil_boiled_ritual";
    static final float HEALTH = 1000;
    /** Real minutes between rituals (server-wide). */
    static final long COOLDOWN = 30L * 60 * 1000;
    static final int ARMOR_ODDS = 10;
    static final int CURSE_ODDS = 4;

    private static MobEntity boss;
    private static ServerBossBar bar;
    private static long lastRitual;
    private static boolean rising;
    private static long now;
    private static long nextMove;
    private static long nextScare;
    private static long lonelySince = -1;

    /** The eight blocks around the altar, each with a lit candle on top. */
    static boolean altar(ServerWorld w, BlockPos center) {
        if (!w.getBlockState(center).isOf(Blocks.CHISELED_POLISHED_BLACKSTONE)) {
            return false;
        }
        for (BlockPos c : candles(center)) {
            BlockState st = w.getBlockState(c);
            if (!(st.getBlock() instanceof AbstractCandleBlock) || !st.get(AbstractCandleBlock.LIT)) {
                return false;
            }
        }
        return true;
    }

    private static List<BlockPos> candles(BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    out.add(center.add(dx, 1, dz));
                }
            }
        }
        return out;
    }

    private static ActionResult use(ServerPlayerEntity p, ServerWorld w, BlockPos center, ItemStack held) {
        if (!World.OVERWORLD.equals(w.getRegistryKey()) || !BoiledOne.night(w)) {
            Msg.send(p, "ritual.night");
            return ActionResult.FAIL;
        }
        if (boss != null || rising) {
            Msg.send(p, "ritual.busy");
            return ActionResult.FAIL;
        }
        long wait = lastRitual + COOLDOWN - System.currentTimeMillis();
        if (wait > 0 && !com.vylorq.anticheat.perm.Perms.isOwner(p.getUuid())) {
            Msg.send(p, "ritual.wait", com.vylorq.anticheat.core.util.Durations.format(wait));
            return ActionResult.FAIL;
        }
        if (!p.isCreative()) {
            held.decrement(1);
        }
        begin(w, center, p);
        return ActionResult.SUCCESS;
    }

    /** The candles go out, one by one; then it rises. */
    static void begin(ServerWorld w, BlockPos center, ServerPlayerEntity by) {
        rising = true;
        lastRitual = System.currentTimeMillis();
        List<BlockPos> cs = candles(center);
        for (ServerPlayerEntity o : w.getPlayers(pl -> pl.squaredDistanceTo(Vec3d.ofCenter(center)) < 48 * 48)) {
            Mc.title(o, "§4" + Msg.trFor(o, "ritual.title"), "§7" + Msg.trFor(o, "ritual.sub"), 10, 60, 20);
            Mc.sound(o, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.5f);
        }
        for (int i = 0; i < cs.size(); i++) {
            BlockPos c = cs.get(i);
            OwnerPowers.later(12 * (i + 1), () -> {
                BlockState st = w.getBlockState(c);
                if (st.getBlock() instanceof AbstractCandleBlock && st.get(AbstractCandleBlock.LIT)) {
                    w.setBlockState(c, st.with(AbstractCandleBlock.LIT, false));
                }
                w.spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, c.getX() + 0.5, c.getY() + 0.6, c.getZ() + 0.5, 10, 0.1, 0.1, 0.1, 0.01);
                w.playSound(null, c, SoundEvents.BLOCK_CANDLE_EXTINGUISH, SoundCategory.BLOCKS, 1.5f, 0.6f);
            });
        }
        OwnerPowers.later(12 * cs.size() + 30, () -> rise(w, center));
        Ac.LOG.info("{} began the Boiled One ritual at {}", by == null ? "?" : by.getGameProfile().name(), center.toShortString());
    }

    private static void rise(ServerWorld w, BlockPos center) {
        rising = false;
        Vec3d at = Vec3d.ofBottomCenter(center.up());
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.EVENT);
        if (bolt != null) {
            bolt.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
            bolt.setCosmetic(true);
            w.spawnEntity(bolt);
        }
        MobEntity m = EntityType.WITHER_SKELETON.create(w, SpawnReason.EVENT);
        if (m == null) {
            return;
        }
        m.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        m.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        set(m, EntityAttributes.SCALE, 1.7);
        set(m, EntityAttributes.MAX_HEALTH, HEALTH);
        m.setHealth(HEALTH);
        set(m, EntityAttributes.MOVEMENT_SPEED, 0.3);
        set(m, EntityAttributes.FOLLOW_RANGE, 64);
        set(m, EntityAttributes.KNOCKBACK_RESISTANCE, 1.0);
        set(m, EntityAttributes.ATTACK_DAMAGE, 10);
        set(m, EntityAttributes.ARMOR, 10);
        set(m, EntityAttributes.STEP_HEIGHT, 1.6);
        m.setPersistent();
        m.setSilent(true);
        m.setCanPickUpLoot(false);
        m.setCustomName(Text.literal("The Boiled One"));
        m.setCustomNameVisible(false);
        m.addCommandTag(TAG);
        StructureMobs.noDrops(m);
        ModelMobs.attach(m, BoiledOne.MODEL);
        w.spawnEntity(m);
        boss = m;
        nextMove = now + 100;
        nextScare = now + 200;
        lonelySince = -1;
        bar = new ServerBossBar(Text.literal("§4The Boiled One §7(" + Msg.tr("ritual.summoned") + ")"), BossBar.Color.RED, BossBar.Style.NOTCHED_10);
        for (ServerPlayerEntity o : near(48)) {
            BoiledOne.jumpscare(o);
        }
    }

    private static void set(MobEntity m, RegistryEntry<EntityAttribute> a, double v) {
        var i = m.getAttributeInstance(a);
        if (i != null) {
            i.setBaseValue(v);
        }
    }

    private static List<ServerPlayerEntity> near(double r) {
        if (boss == null) {
            return List.of();
        }
        return ((ServerWorld) boss.getEntityWorld()).getPlayers(pl -> pl.squaredDistanceTo(boss) < r * r && !pl.isSpectator());
    }

    static void tick(long ticks) {
        now = ticks;
        if (boss == null) {
            return;
        }
        ServerWorld w = (ServerWorld) boss.getEntityWorld();
        if (boss.isRemoved() || !boss.isAlive()) {
            end(false);
            return;
        }
        List<ServerPlayerEntity> in = near(48);
        if (now % 20 == 0) {
            bar.setPercent(Math.max(0, boss.getHealth() / HEALTH));
            for (ServerPlayerEntity p : new ArrayList<>(bar.getPlayers())) {
                if (!in.contains(p)) {
                    bar.removePlayer(p);
                }
            }
            for (ServerPlayerEntity p : in) {
                bar.addPlayer(p);
            }
            ServerPlayerEntity target = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayerEntity p : in) {
                double d = p.squaredDistanceTo(boss);
                if (d < best && !p.isCreative()) {
                    best = d;
                    target = p;
                }
            }
            boss.setTarget(target);
            // It goes back into the ground at sunrise, or when nobody's left to fight it.
            if (in.isEmpty()) {
                if (lonelySince < 0) {
                    lonelySince = now;
                } else if (now - lonelySince > 20 * 60) {
                    end(true);
                    return;
                }
            } else {
                lonelySince = -1;
            }
            if (!BoiledOne.night(w)) {
                end(true);
                return;
            }
        }
        // It lunges at someone, and lands hard.
        if (now >= nextMove && !in.isEmpty()) {
            nextMove = now + 100 + w.getRandom().nextInt(60);
            ServerPlayerEntity t = in.get(w.getRandom().nextInt(in.size()));
            Vec3d d = t.getEntityPos().subtract(boss.getEntityPos());
            OwnerCombat.push(boss, new Vec3d(d.x * 0.18, 0.9, d.z * 0.18));
            w.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 2f, 0.8f);
            OwnerPowers.later(22, () -> {
                if (boss == null) {
                    return;
                }
                w.spawnParticles(net.minecraft.particle.ParticleTypes.EXPLOSION, boss.getX(), boss.getY(), boss.getZ(), 3, 1, 0.2, 1, 0);
                for (ServerPlayerEntity p : near(4)) {
                    p.damage(w, boss.getDamageSources().mobAttack(boss), 8f);
                }
            });
        }
        // Now and then, everyone near it sees its face.
        if (now >= nextScare) {
            nextScare = now + 400 + w.getRandom().nextInt(200);
            for (ServerPlayerEntity p : near(24)) {
                BoiledOne.jumpscare(p);
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0, false, false));
            }
        }
    }

    /** It's over: it sank back into the ground, or it died (and everyone who fought it gets something). */
    private static void end(boolean sank) {
        MobEntity m = boss;
        boss = null;
        if (bar != null) {
            bar.clearPlayers();
            bar = null;
        }
        if (m == null) {
            return;
        }
        ServerWorld w = (ServerWorld) m.getEntityWorld();
        List<ServerPlayerEntity> there = w.getPlayers(pl -> pl.squaredDistanceTo(m) < 48 * 48 && !pl.isSpectator());
        if (sank) {
            for (ServerPlayerEntity p : there) {
                Msg.send(p, "ritual.sank");
            }
            if (!m.isRemoved()) {
                ModelMobs.remove(m);
            }
            return;
        }
        for (ServerPlayerEntity p : there) {
            p.getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, 6));
            p.getInventory().offerOrDrop(new ItemStack(Items.GOLDEN_APPLE, 2));
            BoiledHaunts.givePage(p);
            if (p.getRandom().nextInt(CURSE_ODDS) == 0) {
                p.getInventory().offerOrDrop(BoiledDread.cursedBone());
            }
            if (p.getRandom().nextInt(ARMOR_ODDS) == 0) {
                p.getInventory().offerOrDrop(BoiledFight.armor(p.getRandom().nextInt(4)));
            }
            p.addExperienceLevels(15);
            Mc.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
        }
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            Msg.send(o, "ritual.won", there.size());
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return ActionResult.PASS;
            }
            ItemStack held = p.getStackInHand(hand);
            if (!held.isOf(Items.BONE) || BoiledDread.isCursed(held) || !altar(w, hit.getBlockPos())) {
                return ActionResult.PASS;
            }
            return use(p, w, hit.getBlockPos(), held);
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && e == boss) {
                end(false);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e.getCommandTags().contains(TAG) && e != boss && e instanceof MobEntity m) {
                OwnerPowers.later(1, () -> {
                    if (!m.isRemoved() && m != boss) {
                        ModelMobs.remove(m);        // left over from before a restart
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- owner

    /** Builds the altar (lit) in front of the owner. */
    public static void ownerAltar(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        BlockPos c = p.getBlockPos().offset(p.getHorizontalFacing(), 3);
        w.setBlockState(c, Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState());
        for (BlockPos b : candles(c)) {
            w.setBlockState(b.down(), Blocks.POLISHED_BLACKSTONE.getDefaultState());
            w.setBlockState(b, Blocks.RED_CANDLE.getDefaultState().with(AbstractCandleBlock.LIT, true));
        }
        w.setBlockState(c.up(), Blocks.AIR.getDefaultState());
        Msg.send(p, "ritual.altar-built");
    }

    /** Summons it right away in front of the owner (no altar, no waiting). */
    public static void ownerSummon(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (boss != null) {
            Msg.send(p, "ritual.busy");
            return;
        }
        rise(p.getEntityWorld(), p.getBlockPos().offset(p.getHorizontalFacing(), 4).down());
    }

    public static boolean altarForTest(ServerWorld w, BlockPos c) {
        return altar(w, c);
    }
}
