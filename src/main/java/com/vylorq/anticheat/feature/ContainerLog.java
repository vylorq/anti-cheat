package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Container change logging (section 15): what a player put into or took out of a container, worked out from the
 * contents when they opened it and when they closed it.
 */
public final class ContainerLog {
    private ContainerLog() {
    }

    private static Map<String, Integer> counts(Inventory inv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                m.merge(Mc.itemId(s.getItem()), s.getCount(), Integer::sum);
            }
        }
        return m;
    }

    public static void opened(ServerPlayerEntity p, ServerWorld w, BlockPos pos, Inventory inv) {
        PlayerSession s = Ac.session(p);
        s.openContainer = pos.toImmutable();
        s.openContainerWorld = Mc.worldId(w);
        s.openContainerCounts = counts(inv);
    }

    /** Every second: once the container screen is closed, log the difference. */
    public static void check(ServerPlayerEntity p) {
        PlayerSession s = Ac.session(p);
        if (s.openContainer == null) {
            return;
        }
        if (p.currentScreenHandler != p.playerScreenHandler) {
            // Still open: keep watching.
            return;
        }
        BlockPos pos = s.openContainer;
        Map<String, Integer> before = s.openContainerCounts;
        ServerWorld w = Mc.world(Ac.server(), s.openContainerWorld);
        s.openContainer = null;
        s.openContainerCounts = null;
        if (w == null || !(w.getBlockEntity(pos) instanceof Inventory inv)) {
            return;
        }
        Map<String, Integer> after = counts(inv);
        Set<String> items = new HashSet<>(before.keySet());
        items.addAll(after.keySet());
        for (String item : items) {
            int diff = after.getOrDefault(item, 0) - before.getOrDefault(item, 0);
            if (diff != 0) {
                BlockLog.logContainer(p, w, pos, item, diff);
                if (Ac.get().watchlist.isWatched(p.getUuid())) {
                    Ac.get().logs.activity(System.currentTimeMillis(), p.getUuid(), "container",
                            (diff > 0 ? "put " : "took ") + Math.abs(diff) + "x " + item.replace("minecraft:", "") + " at " + pos.toShortString());
                }
            }
        }
    }
}
