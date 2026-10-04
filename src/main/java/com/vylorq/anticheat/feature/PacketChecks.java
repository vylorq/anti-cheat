package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.packets.BlockFace;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import com.vylorq.anticheat.util.Msg;
import com.vylorq.anticheat.util.Tps;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Checks on raw packets that a real client can't get wrong: impossible head angles, block clicks with made-up
 * geometry, blocks finished too early, sprinting while starving, and packet floods.
 */
public final class PacketChecks {
    private PacketChecks() {
    }

    private static boolean judged(ServerPlayerEntity p, PlayerSession s) {
        return Ac.running() && !s.bedrock && !p.isSpectator() && Tps.tps() >= Ac.config().general.lagTpsThreshold;
    }

    /** Head pitch is always between -90 and 90 on a real client. */
    public static void rotation(ServerPlayerEntity p, float pitch) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s == null || !judged(p, s) || !Float.isFinite(pitch) || Math.abs(pitch) <= 90.01f) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastBadRotationFlag > 5000) {
            s.lastBadRotationFlag = now;
            PlayerSessionFlags.flag(p, CheckType.BAD_PACKET, 2.0, String.format(java.util.Locale.ROOT, "impossible head angle %.1f°", pitch));
        }
    }

    /** A click on a block (placing, opening, using). */
    public static void blockClick(ServerPlayerEntity p, BlockHitResult hit) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s == null || !judged(p, s) || hit == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState st = p.getEntityWorld().getBlockState(pos);
        if (st.isAir()) {
            return;
        }
        Vec3d h = hit.getPos();
        Vec3d eye = p.getEyePos();
        boolean full = st.isFullCube(p.getEntityWorld(), pos);
        String problem = BlockFace.problem(hit.getSide().getIndex(), pos.getX(), pos.getY(), pos.getZ(), h.x, h.y, h.z,
                eye.x, eye.y, eye.z, full, 0.5);
        if (problem == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - s.badClickWindow > 20_000) {
            s.badClickWindow = now;
            s.badClicks = 0;
        }
        s.badClicks++;
        // One odd click can be lag; three in twenty seconds can't.
        if (s.badClicks == 3) {
            PlayerSessionFlags.flag(p, CheckType.SCAFFOLD, 1.5, problem + " (3 times)");
        }
    }

    /**
     * The client said it finished breaking a block. {@code early} when the game overruled it (not enough mining
     * time yet) rather than a protection refusing it.
     */
    public static void finishedBreaking(ServerPlayerEntity p, boolean early) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s == null || !judged(p, s) || p.isCreative()) {
            return;
        }
        if (s.breaks.onFinish(early)) {
            PlayerSessionFlags.flag(p, CheckType.FAST_BREAK, 1.5, "finished most blocks before the game allowed");
        }
    }

    /** A block was broken: nuker check (more mining time than has passed). Works for Java and Bedrock. */
    public static void brokeBlock(ServerPlayerEntity p, net.minecraft.server.world.ServerWorld w, BlockPos pos, BlockState state) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s == null || p.isCreative() || p.isSpectator() || Tps.tps() < Ac.config().general.lagTpsThreshold) {
            return;
        }
        long now = System.currentTimeMillis();
        s.lastBreakMs = now;
        int needed = s.miningTime.onBreak(now, state.calcBlockBreakingDelta(p, w, pos));
        if (needed > 0) {
            PlayerSessionFlags.flag(p, CheckType.FAST_BREAK, 2.0,
                    "broke blocks needing " + needed + " ticks of mining in 10 seconds (200 passed)");
        }
    }

    /** Every movement packet: a real client stops sprinting when food is 3 drumsticks or less. */
    public static void sprint(ServerPlayerEntity p) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s == null) {
            return;
        }
        boolean starving = p.isSprinting() && p.getHungerManager().getFoodLevel() <= 6 && !p.getAbilities().allowFlying
                && !p.hasVehicle() && !p.isCreative();
        if (!starving || !judged(p, s)) {
            s.starvingSprintTicks = 0;
            return;
        }
        // The client learns its food level one ping later: give it two seconds.
        if (++s.starvingSprintTicks == 40) {
            PlayerSessionFlags.flag(p, CheckType.SPEED, 1.0, "sprinting with " + p.getHungerManager().getFoodLevel() + " food");
        } else if (s.starvingSprintTicks > 200) {
            s.starvingSprintTicks = 0;
        }
    }

    /** Watched players: items they drop or pick up go into their activity log and evidence. */
    public static void watchedItem(ServerPlayerEntity p, String what, net.minecraft.item.ItemStack stack) {
        if (!Ac.running() || stack.isEmpty() || !Ac.config().watchlist.logItems || !Ac.get().watchlist.isWatched(p.getUuid())) {
            return;
        }
        String d = what + " " + stack.getCount() + "x " + com.vylorq.anticheat.util.Mc.itemId(stack.getItem());
        Ac.get().logs.activity(System.currentTimeMillis(), p.getUuid(), "item", d);
        Ac.get().evidence.record(p.getUuid(), com.vylorq.anticheat.core.evidence.EvidenceEvent.Type.INVENTORY,
                p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), d);
    }

    /** Called on the network thread by the connection when a player sends far too many packets in one second. */
    public static void flood(ServerPlayerEntity p, int count, net.minecraft.network.ClientConnection connection) {
        connection.disconnect(Text.literal(Msg.tr("kick.packet-flood")));
        var server = p.getEntityWorld().getServer();
        if (server != null) {
            server.execute(() -> {
                if (!Ac.running()) {
                    return;
                }
                Ac.LOG.warn("{} sent {} packets in one second and was disconnected", p.getGameProfile().name(), count);
                Ac.get().engine.flag(p.getUuid(), p.getGameProfile().name(), CheckType.BAD_PACKET, 3.0,
                        count + " packets in one second", Ac.sessionOrNull(p.getUuid()) != null && Ac.sessionOrNull(p.getUuid()).bedrock);
            });
        }
    }
}
