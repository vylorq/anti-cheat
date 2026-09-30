package com.vylorq.anticheat.feature;

import com.mojang.authlib.GameProfile;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.PlayerSnapshot;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary admin: the owner gives a player op and full staff access for one visit. When they leave (or the owner
 * ends it) op is removed, their inventory, ender chest, XP, effects, game mode and position go back to how they were
 * when access was given, and by default every block they changed is rolled back. Saved to disk so a crash still
 * reverts it on their next join.
 */
public final class TempAdmins {
    private TempAdmins() {
    }

    public static final class Grant {
        public String name;
        public long since;
        public boolean keepBuilds;
        /** Whether they were already op before, so ending it doesn't remove a real admin. */
        public boolean wasOp;
        /** Temporary owner: every owner power for the visit, not just admin. */
        public boolean owner;
        public PlayerSnapshot snapshot;
    }

    private static Map<UUID, Grant> grants() {
        return Ac.get().misc.tempAdmins;
    }

    public static boolean isTemp(UUID id) {
        return Ac.running() && grants().containsKey(id);
    }

    public static boolean isTempOwner(UUID id) {
        if (!Ac.running()) {
            return false;
        }
        Grant g = grants().get(id);
        return g != null && g.owner;
    }

    public static void grant(ServerPlayerEntity by, ServerPlayerEntity p, boolean keepBuilds) {
        grant(by, p, keepBuilds, false);
    }

    public static void grant(ServerPlayerEntity by, ServerPlayerEntity p, boolean keepBuilds, boolean owner) {
        var server = Ac.server();
        GameProfile profile = p.getGameProfile();
        Grant g = new Grant();
        g.name = profile.name();
        g.since = System.currentTimeMillis();
        g.keepBuilds = keepBuilds;
        g.owner = owner;
        g.wasOp = server.getPlayerManager().isOperator(new PlayerConfigEntry(profile));
        g.snapshot = PlayerState.capture(p, "tempadmin");
        g.snapshot.enderChest = new ArrayList<>();
        EnderChestInventory ender = p.getEnderChestInventory();
        for (int i = 0; i < ender.size(); i++) {
            ItemStack st = ender.getStack(i);
            if (!st.isEmpty()) {
                g.snapshot.enderChest.add(ItemConv.encodeSlot(i, st));
            }
        }
        grants().put(p.getUuid(), g);
        Ac.saveNow("misc");
        if (!g.wasOp) {
            server.getPlayerManager().addToOperators(new PlayerConfigEntry(profile));
        }
        Staff.log(by, owner ? "tempowner-add" : "tempadmin-add", p.getUuid(), g.name, keepBuilds ? "keep builds" : "revert builds");
        Msg.send(p, owner ? "tempowner.you-are" : "tempadmin.you-are");
        // Refresh the command list so the new commands show up right away.
        server.getPlayerManager().sendCommandTree(p);
    }

    /**
     * Ends temporary access and puts everything back. Call while the player is still online (before they are saved)
     * or when they join after a crash.
     */
    public static boolean end(ServerPlayerEntity by, ServerPlayerEntity p) {
        Grant g = grants().remove(p.getUuid());
        if (g == null) {
            return false;
        }
        Ac.saveNow("misc");
        var server = Ac.server();
        if (!g.wasOp) {
            server.getPlayerManager().removeFromOperators(new PlayerConfigEntry(p.getGameProfile()));
        }
        Ac.get().pins.logout(p.getUuid());
        if (!p.isDisconnected()) {
            server.getPlayerManager().sendCommandTree(p);
        }
        if (g.snapshot != null) {
            PlayerState.apply(p, g.snapshot, true);
            if (g.snapshot.enderChest != null) {
                EnderChestInventory ender = p.getEnderChestInventory();
                ender.clear();
                for (String e : g.snapshot.enderChest) {
                    int slot = ItemConv.slotOf(e);
                    if (slot >= 0 && slot < ender.size()) {
                        ender.setStack(slot, ItemConv.decodeSlot(e));
                    }
                }
            }
        }
        if (!g.keepBuilds) {
            BlockLog.rollback(null, p.getUuid(), g.name, System.currentTimeMillis() - g.since + 1000, null, false);
        }
        Staff.log(by, "tempadmin-end", p.getUuid(), g.name, "");
        Staff.broadcast(Msg.prefixed(Msg.tr("tempadmin.ended", g.name)));
        return true;
    }

    public static void onLeave(ServerPlayerEntity p) {
        end(null, p);
    }

    /** After a crash the grant is still on disk: revert as soon as they come back. */
    public static void onJoin(ServerPlayerEntity p) {
        end(null, p);
    }

    /** Op is saved by vanilla, so remove it at startup for anyone whose visit didn't end cleanly. */
    public static void onServerStarted() {
        var server = Ac.server();
        for (Map.Entry<UUID, Grant> e : grants().entrySet()) {
            if (!e.getValue().wasOp) {
                server.getPlayerManager().removeFromOperators(new PlayerConfigEntry(e.getKey(), e.getValue().name));
            }
        }
    }
}
