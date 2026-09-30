package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.core.items.DupeWatch;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** Dupe watch (section 9). */
public final class Dupes {
    private Dupes() {
    }

    public static int value(List<ItemStack> stacks) {
        List<ItemInfo> infos = new ArrayList<>();
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) {
                infos.add(ItemConv.info(s));
            }
        }
        return DupeWatch.value(infos, Ac.config().dupeWatch.weights);
    }

    public static int valueOf(ServerPlayerEntity p) {
        List<ItemStack> all = new ArrayList<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            all.add(inv.getStack(i));
        }
        var ec = p.getEnderChestInventory();
        for (int i = 0; i < ec.size(); i++) {
            all.add(ec.getStack(i));
        }
        return value(all);
    }

    /** Every few seconds per player. */
    public static void sample(ServerPlayerEntity p) {
        var cfg = Ac.config().dupeWatch;
        if (!cfg.enabled || p.isCreative() || p.isSpectator()) {
            return;
        }
        int value = valueOf(p);
        var session = Ac.session(p);
        // Taking things out of your own chests isn't a dupe: gains while a container is open count as legit.
        if (p.currentScreenHandler != p.playerScreenHandler && value > session.lastDupeValue) {
            Ac.get().dupeWatch.legitGain(p.getUuid(), value - session.lastDupeValue, System.currentTimeMillis());
        }
        session.lastDupeValue = value;
        int jump = Ac.get().dupeWatch.sample(p.getUuid(), value, System.currentTimeMillis(),
                cfg.windowSeconds * 1000L, cfg.valueJumpThreshold);
        if (jump > 0) {
            Staff.broadcast(Msg.prefixed(Msg.tr("dupe.alert", p.getGameProfile().name(), jump, cfg.windowSeconds)));
            Ac.get().logs.activity(System.currentTimeMillis(), p.getUuid(), "dupe-watch", "valuables +" + jump);
            PlayerSessionFlags.flag(p, CheckType.DUPE, 1.0, "valuables jumped by " + jump);
        }
    }

    /** Legit sources: admin gives, restores, trades, trader deals, vault and trial spawner loot, crafting. */
    public static void legit(ServerPlayerEntity p, List<ItemStack> stacks) {
        Ac.get().dupeWatch.legitGain(p.getUuid(), value(stacks), System.currentTimeMillis());
    }
}
