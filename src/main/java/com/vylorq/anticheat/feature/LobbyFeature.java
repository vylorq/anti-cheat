package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.lobby.Lobby;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Location;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lobby (section 20). */
public final class LobbyFeature {
    private LobbyFeature() {
    }

    public static boolean in(World w, BlockPos pos) {
        return Ac.get().lobby.inLobby(Mc.worldId(w), pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean in(ServerPlayerEntity p) {
        return Ac.get().lobby.inLobby(Mc.worldId(p.getEntityWorld()), p.getX(), p.getY(), p.getZ());
    }

    /** @return true when the action is allowed (always true outside the lobby). */
    public static boolean allowed(ServerPlayerEntity p, World w, BlockPos pos, Lobby.Action action) {
        if (!Features.on(Features.Feature.LOBBY)) {
            return true;
        }
        if (!in(w, pos)) {
            return true;
        }
        boolean ok = Ac.get().lobby.allowed(p.getUuid(), action)
                || ((action == Lobby.Action.PLACE || action == Lobby.Action.BREAK) && BuilderMode.is(p));
        if (!ok) {
            Msg.actionBar(p, Msg.tr("lobby.protected"));
        }
        return ok;
    }

    public static void teleport(ServerPlayerEntity p) {
        Location spawn = Ac.get().lobby.data().spawn;
        if (spawn == null) {
            Msg.send(p, "lobby.not-set");
            return;
        }
        Mc.teleport(p, Ac.server(), spawn);
    }

    /**
     * Chest rules in the lobby. @return true when the event was handled here (view-only chests open a read-only copy).
     */
    public static boolean openChest(ServerPlayerEntity p, ServerWorld w, BlockPos pos, Inventory inv) {
        if (!in(w, pos) || Ac.get().lobby.isEditing(p.getUuid())) {
            return false;
        }
        Lobby.ChestConfig c = Ac.get().lobby.chestOrDefault(pos.getX(), pos.getY(), pos.getZ());
        if (c.mode != Lobby.ChestMode.VIEW_ONLY) {
            return false;
        }
        int rows = Math.max(1, Math.min(6, (inv.size() + 8) / 9));
        Menu m = new Menu("", rows).titleText(com.vylorq.anticheat.ui.Theme.title(com.vylorq.anticheat.ui.Theme.Category.LOBBY,
                Msg.trFor(p, "lobby.view-only").replaceAll("§.", "")));
        m.renderer(menu -> {
            for (int i = 0; i < Math.min(inv.size(), menu.size()); i++) {
                menu.icon(i, inv.getStack(i).copy());
            }
        });
        m.open(p);
        return true;
    }

    /** Saves the chest's current contents as its loot template. */
    public static void setChestMode(ServerWorld w, BlockPos pos, Lobby.ChestMode mode, Inventory inv) {
        Lobby.ChestConfig c = new Lobby.ChestConfig();
        c.mode = mode;
        if (mode == Lobby.ChestMode.LOOT) {
            c.lootContents = new ArrayList<>();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isEmpty()) {
                    c.lootContents.add(ItemConv.encodeSlot(i, s));
                }
            }
            c.lastRefill = System.currentTimeMillis();
        }
        Ac.get().lobby.setChest(pos.getX(), pos.getY(), pos.getZ(), c);
        Ac.markDirty("lobby");
    }

    /** Every minute: refill loot chests. Every second: void rescue, no hunger. */
    public static void tickMinute() {
        if (!Features.on(Features.Feature.LOBBY)) {
            return;
        }
        Ac ac = Ac.get();
        Lobby lobby = ac.lobby;
        if (!lobby.isSet()) {
            return;
        }
        ServerWorld w = Mc.world(ac.server, lobby.data().area.world);
        if (w == null) {
            return;
        }
        Map<String, Lobby.ChestConfig> due = lobby.dueRefills(System.currentTimeMillis(),
                Ac.config().lobby.lootChestRefillMinutes * Durations.MINUTE);
        for (Map.Entry<String, Lobby.ChestConfig> e : due.entrySet()) {
            String[] xyz = e.getKey().split(",");
            BlockPos pos = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            if (!w.isChunkLoaded(pos)) {
                continue;
            }
            if (w.getBlockEntity(pos) instanceof Inventory inv) {
                inv.clear();
                List<String> items = e.getValue().lootContents == null ? List.of() : e.getValue().lootContents;
                for (String s : items) {
                    int slot = ItemConv.slotOf(s);
                    if (slot >= 0 && slot < inv.size()) {
                        inv.setStack(slot, ItemConv.decodeSlot(s));
                    }
                }
                inv.markDirty();
            }
        }
        if (!due.isEmpty()) {
            Ac.markDirty("lobby");
        }
    }

    public static void tickPlayer(ServerPlayerEntity p) {
        if (!Features.on(Features.Feature.LOBBY)) {
            return;
        }
        Lobby lobby = Ac.get().lobby;
        if (!lobby.isSet()) {
            return;
        }
        var area = lobby.data().area;
        if (!area.world.equals(Mc.worldId(p.getEntityWorld()))) {
            return;
        }
        boolean inColumn = p.getX() >= area.minX && p.getX() < area.maxX + 1 && p.getZ() >= area.minZ && p.getZ() < area.maxZ + 1;
        if (inColumn && p.getY() < Math.min(area.minY, p.getEntityWorld().getBottomY() + 1) - 2) {
            teleport(p);
            p.fallDistance = 0;
            return;
        }
        if (in(p) && Ac.config().lobby.noHunger) {
            p.getHungerManager().setFoodLevel(20);
        }
    }
}
