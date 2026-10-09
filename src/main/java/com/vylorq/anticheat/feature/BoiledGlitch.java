package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When it's near, things stop working right: your chat glitches, an extra footstep follows yours, the sky turns to
 * midnight at noon, you see yourself far away staring back, the torches behind you go out one by one, your view
 * snaps round to look behind you, and if you never answered its knock, it leaves a note.
 */
public final class BoiledGlitch {
    private BoiledGlitch() {
    }

    // ---------------------------------------------------------------- corrupted chat

    /** Whether it's close enough to them to get into their words. */
    static boolean near(ServerPlayerEntity p, double r) {
        Entity it = BoiledOne.hunting().get(p.getUuid());
        return it != null && it.getEntityWorld() == p.getEntityWorld() && it.squaredDistanceTo(p) < r * r;
    }

    /** Their message, with letters glitching (obfuscated) and a few turned to its own. */
    static Text corrupt(Text message, net.minecraft.util.math.random.Random r) {
        String s = message.getString();
        MutableText out = Text.empty();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int roll = r.nextInt(100);
            if (c != ' ' && roll < 18) {
                out.append(Text.literal(String.valueOf(c)).formatted(Formatting.OBFUSCATED, Formatting.DARK_RED));
            } else if (c != ' ' && roll < 22) {
                out.append(Text.literal("█").formatted(Formatting.DARK_RED));
            } else {
                out.append(Text.literal(String.valueOf(c)));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the extra footstep

    private static final Map<UUID, Vec3d> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> WALKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_ECHO = new ConcurrentHashMap<>();

    /** Every tick, for those it's hunting: when they stop walking, one more step behind them. */
    private static void echo(ServerPlayerEntity p, long now) {
        Vec3d at = p.getEntityPos();
        Vec3d was = LAST.put(p.getUuid(), at);
        if (was == null) {
            return;
        }
        double moved = at.subtract(was).horizontalLengthSquared();
        if (moved > 0.004) {
            WALKED.merge(p.getUuid(), 1, Integer::sum);
            return;
        }
        int walked = WALKED.getOrDefault(p.getUuid(), 0);
        WALKED.put(p.getUuid(), 0);
        if (walked < 15 || now < NEXT_ECHO.getOrDefault(p.getUuid(), 0L) || !p.isOnGround()) {
            return;
        }
        NEXT_ECHO.put(p.getUuid(), now + 20 * 6);
        Vec3d dir = at.subtract(was.equals(at) ? at.add(p.getRotationVec(1f)) : was).multiply(1, 0, 1);
        Vec3d back = p.getRotationVec(1f).multiply(-1, 0, -1);
        if (back.lengthSquared() < 1e-4) {
            back = dir;
        }
        Vec3d step = at.add(back.normalize().multiply(1.6));
        BlockState ground = p.getEntityWorld().getBlockState(p.getBlockPos().down());
        OwnerPowers.later(7, () -> {
            if (!p.isRemoved()) {
                BoiledOmens.sound(p, ground.getSoundGroup().getStepSound(), step, 0.5f, 0.85f);
            }
        });
    }

    // ---------------------------------------------------------------- midnight at noon

    /** For them alone, the day turns to midnight for twenty seconds. */
    static void midnight(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        for (int i = 0; i < 20 * 20; i++) {
            OwnerPowers.later(i, () -> {
                if (!p.isRemoved()) {
                    p.networkHandler.sendPacket(new WorldTimeUpdateS2CPacket(w.getTime(), 18000, false));
                }
            });
        }
        OwnerPowers.later(20 * 20 + 1, () -> {
            if (!p.isRemoved()) {
                p.networkHandler.sendPacket(new WorldTimeUpdateS2CPacket(w.getTime(), w.getTimeOfDay(),
                        w.getGameRules().getValue(net.minecraft.world.rule.GameRules.ADVANCE_TIME)));
            }
        });
        BoiledOmens.pack(p, "boiled_static", p.getEyePos(), 0.7f, 0.5f);
        BoiledFear.scare(p, 10);
    }

    // ---------------------------------------------------------------- yourself, far away

    private static final class Twin {
        final UUID victim;
        final WatcherFigure figure;
        final ServerWorld world;
        final long born;
        int seen;

        Twin(UUID victim, WatcherFigure figure, ServerWorld world, long born) {
            this.victim = victim;
            this.figure = figure;
            this.world = world;
            this.born = born;
        }
    }

    private static final Map<UUID, Twin> DOUBLES = new ConcurrentHashMap<>();

    /** Their own body, name over its head, standing far off and staring at them. Look too long and it's gone. */
    static boolean bodyDouble(ServerPlayerEntity p) {
        if (DOUBLES.containsKey(p.getUuid())) {
            return false;
        }
        Vec3d at = BoiledOne.spotNear(p, 18, 30);
        if (at == null) {
            return false;
        }
        WatcherFigure f = WatcherFigure.ghost(p.getEntityWorld(), p.getUuid(), p.getGameProfile().name()).named();
        f.at(at, 0, 0);
        f.show(p);
        f.lookAt(p, p.getEyePos());
        DOUBLES.put(p.getUuid(), new Twin(p.getUuid(), f, p.getEntityWorld(), p.getEntityWorld().getTime()));
        return true;
    }

    private static void doubles() {
        for (Twin d : DOUBLES.values().toArray(new Twin[0])) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(d.victim);
            if (p == null || p.getEntityWorld() != d.world || d.world.getTime() - d.born > 20 * 40) {
                DOUBLES.remove(d.victim);
                if (p != null) {
                    d.figure.hide(p);
                }
                continue;
            }
            d.figure.lookAt(p, p.getEyePos());
            Vec3d to = d.figure.head().subtract(p.getEyePos());
            double dist = to.length();
            boolean looking = dist > 0.1 && p.getRotationVec(1f).dotProduct(to.multiply(1 / dist)) > 0.97;
            d.seen = looking ? d.seen + 1 : 0;
            if (d.seen > 30 || dist < 8) {
                DOUBLES.remove(d.victim);
                d.figure.hide(p);
                BoiledOmens.pack(p, "boiled_static", p.getEyePos(), 0.8f, 1.3f);
                BoiledFear.scare(p, 12);
            }
        }
    }

    // ---------------------------------------------------------------- torches dying behind you

    /** Torches each player's client shows as out (the real blocks are untouched), and when they come back. */
    private static final Map<UUID, Map<BlockPos, Long>> OUT = new ConcurrentHashMap<>();

    private static boolean torch(BlockState st) {
        return st.isOf(Blocks.TORCH) || st.isOf(Blocks.WALL_TORCH) || st.isOf(Blocks.SOUL_TORCH) || st.isOf(Blocks.SOUL_WALL_TORCH)
                || st.isOf(Blocks.COPPER_TORCH) || st.isOf(Blocks.COPPER_WALL_TORCH);
    }

    /** Underground and afraid (or hunted): the torch behind them goes out. */
    private static void torchesBehind(ServerPlayerEntity p, long now) {
        ServerWorld w = p.getEntityWorld();
        Map<BlockPos, Long> out = OUT.computeIfAbsent(p.getUuid(), k -> new ConcurrentHashMap<>());
        // Lit again after a minute.
        for (var e : new ArrayList<>(out.entrySet())) {
            if (now > e.getValue()) {
                out.remove(e.getKey());
                p.networkHandler.sendPacket(new BlockUpdateS2CPacket(w, e.getKey()));
            }
        }
        if (!BoiledOne.inCave(p) || !(BoiledFear.fear(p) >= 45 || BoiledOne.hunting().containsKey(p.getUuid()))) {
            return;
        }
        Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) {
            return;
        }
        look = look.normalize();
        BlockPos c = p.getBlockPos();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.iterate(c.add(-14, -4, -14), c.add(14, 5, 14))) {
            if (out.containsKey(b) || !torch(w.getBlockState(b))) {
                continue;
            }
            Vec3d to = Vec3d.ofCenter(b).subtract(p.getEyePos());
            double d = to.length();
            if (d < 5 || d > 15 || look.dotProduct(to.multiply(1, 0, 1).normalize()) > -0.3) {
                continue;       // only ones behind them
            }
            if (d < bd) {
                bd = d;
                best = b.toImmutable();
            }
        }
        if (best != null) {
            out.put(best, now + 20 * 60);
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(best, Blocks.AIR.getDefaultState()));
            BoiledOmens.sound(p, SoundEvents.BLOCK_FIRE_EXTINGUISH, Vec3d.ofCenter(best), 0.5f, 0.7f);
        }
    }

    // ---------------------------------------------------------------- the view snaps round

    /** Their view snaps round to look behind them, for a moment, then back. */
    static void snap(ServerPlayerEntity p) {
        float yaw = p.getYaw();
        float pitch = p.getPitch();
        p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), yaw + 180, 10);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 60, 0, false, false));
        BoiledOmens.pack(p, "boiled_static", p.getEyePos(), 1f, 0.7f);
        OwnerPowers.later(22, () -> {
            if (!p.isRemoved()) {
                p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), yaw, pitch);
            }
        });
        BoiledFear.scare(p, 8);
    }

    // ---------------------------------------------------------------- "I waited."

    /** Nobody opened the door after its knocking: a sign outside it now. */
    static void waited(ServerPlayerEntity p, BlockPos door) {
        ServerWorld w = p.getEntityWorld();
        BlockState d = w.getBlockState(door);
        if (!(d.getBlock() instanceof DoorBlock)) {
            return;
        }
        var facing = d.get(DoorBlock.FACING);
        // The side away from where they are.
        Vec3d c = Vec3d.ofCenter(door);
        var side = p.getEntityPos().subtract(c).dotProduct(Vec3d.of(facing.getVector())) > 0 ? facing.getOpposite() : facing;
        for (int k = 1; k <= 2; k++) {
            BlockPos at = door.offset(side, k);
            if (!w.getBlockState(at).isAir() || !w.getBlockState(at.down()).isSolidBlock(w, at.down())) {
                continue;
            }
            int rot = Math.floorMod(Math.round((side.getPositiveHorizontalDegrees()) / 22.5f) + 8, 16);
            w.setBlockState(at, Blocks.DARK_OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, rot));
            if (w.getBlockEntity(at) instanceof SignBlockEntity s) {
                SignText t = new SignText().withColor(DyeColor.RED).withGlowing(true)
                        .withMessage(1, Text.literal(Msg.trFor(p, "boiled.waited")));
                s.setText(t, true);
                s.setText(t, false);
                s.setWaxed(true);
                s.markDirty();
            }
            BoiledOmens.sound(p, SoundEvents.BLOCK_WOOD_PLACE, Vec3d.ofCenter(at), 0.6f, 0.6f);
            return;
        }
    }

    // ---------------------------------------------------------------- every tick

    static void tick(long now) {
        Set<UUID> hunted = new HashSet<>(BoiledOne.hunting().keySet());
        for (UUID id : hunted) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
            if (p != null) {
                echo(p, now);
            }
        }
        LAST.keySet().retainAll(hunted);
        if (!DOUBLES.isEmpty() && now % 2 == 0) {
            doubles();
        }
        if (now % 40 == 0) {
            for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                if (!p.isSpectator()) {
                    torchesBehind(p, now);
                }
            }
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent.EVENT.register(
                net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent.CONTENT_PHASE, (sender, message) -> {
                    if (Ac.running() && sender != null && near(sender, 32)) {
                        return corrupt(message, sender.getRandom());
                    }
                    return message;
                });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            DOUBLES.remove(h.player.getUuid());
            OUT.remove(h.player.getUuid());
            LAST.remove(h.player.getUuid());
        });
    }

    public static List<String> forTest() {
        return List.of();
    }
}
