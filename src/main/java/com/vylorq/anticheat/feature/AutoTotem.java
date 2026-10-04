package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.combat.TotemWatch;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-totem detection (see {@link TotemWatch}). A pop sends a ping along with it; a new totem appearing in the
 * offhand sooner than a person could react after the game answered that ping is an auto-totem.
 */
public final class AutoTotem {
    private AutoTotem() {
    }

    /** Ping ids we send, kept apart from anything else that might ping. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0x56470000);

    /** A totem of undying just saved this player. */
    public static void popped(ServerPlayerEntity p) {
        if (!Ac.running()) {
            return;
        }
        PlayerSession s = Ac.session(p);
        int id = NEXT_ID.incrementAndGet();
        s.totemWatch.onPop(System.nanoTime(), id);
        s.totemPingId = id;
        p.networkHandler.sendPacket(new CommonPingS2CPacket(id));
    }

    /** The answer to a ping (network thread). */
    public static void pong(ServerPlayerEntity p, int id, long arrivedAt) {
        PlayerSession s = Ac.running() ? Ac.sessionOrNull(p.getUuid()) : null;
        if (s != null) {
            s.totemWatch.onPong(arrivedAt, id);
        }
    }

    /** Before an inventory-changing packet is handled: remember whether the offhand already held a totem. */
    public static void before(ServerPlayerEntity p, long arrivedAt) {
        PlayerSession s = Ac.running() ? Ac.sessionOrNull(p.getUuid()) : null;
        if (s != null) {
            s.invPacketArrival = arrivedAt;
            s.offhandWasTotem = p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        }
    }

    /** After that packet: did it put a totem into the offhand? */
    public static void after(ServerPlayerEntity p) {
        PlayerSession s = Ac.running() ? Ac.sessionOrNull(p.getUuid()) : null;
        if (s == null || s.offhandWasTotem || !p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        s.offhandWasTotem = true;
        TotemWatch.Verdict v = s.totemWatch.onRefill(s.invPacketArrival, p.networkHandler.getLatency());
        if (v == null) {
            return;
        }
        String detail = "new totem in the offhand " + Math.max(0, v.reactionMs()) + " ms after the game saw the pop";
        Ac.get().evidence.record(p.getUuid(), EvidenceEvent.Type.INVENTORY, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), detail);
        if (!v.fast() || p.isCreative() || p.isSpectator()) {
            return;
        }
        // One could be a lucky press; two within half an hour is a pattern.
        PlayerSessionFlags.flag(p, CheckType.AUTO_TOTEM, v.fastInRow() >= 2 ? 3.0 : 1.5,
                detail + " (people need 150+ ms)" + (v.fastInRow() >= 2 ? ", " + v.fastInRow() + " times" : ""));
    }
}
