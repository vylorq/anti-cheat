package com.vylorq.anticheat.gui;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reads and writes an offline player's inventory and ender chest from their player data file (section 15). */
public final class OfflineInventory {
    /** Admins editing offline players: target -> editor, so edits are dropped if the target logs in. */
    static final Map<UUID, UUID> EDITING = new ConcurrentHashMap<>();

    public final UUID player;
    public final SimpleInventory inventory = new SimpleInventory(41);
    public final SimpleInventory ender = new SimpleInventory(27);
    private final Path file;
    private NbtCompound root;

    private OfflineInventory(UUID player, Path file) {
        this.player = player;
        this.file = file;
    }

    public static OfflineInventory load(UUID id) {
        Path f = Ac.server().getSavePath(WorldSavePath.PLAYERDATA).resolve(id + ".dat");
        if (!Files.exists(f)) {
            return null;
        }
        OfflineInventory o = new OfflineInventory(id, f);
        try {
            o.root = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            Ac.LOG.error("Could not read player data for {}", id, e);
            return null;
        }
        // Main inventory (slots 0-35). Since 1.21.5 armor and off-hand live in the "equipment" compound.
        NbtList inv = o.root.getListOrEmpty("Inventory");
        for (int i = 0; i < inv.size(); i++) {
            NbtCompound n = inv.getCompoundOrEmpty(i);
            int slot = n.getByte("Slot", (byte) -1) & 255;
            if (slot < 36) {
                o.inventory.setStack(slot, ItemConv.fromNbt(withoutSlot(n)));
            }
        }
        NbtCompound eq = o.root.getCompoundOrEmpty("equipment");
        for (int k = 0; k < EQUIPMENT.length; k++) {
            NbtCompound n = eq.getCompoundOrEmpty(EQUIPMENT[k]);
            if (!n.isEmpty()) {
                o.inventory.setStack(36 + k, ItemConv.fromNbt(n));
            }
        }
        NbtList ec = o.root.getListOrEmpty("EnderItems");
        for (int i = 0; i < ec.size(); i++) {
            NbtCompound n = ec.getCompoundOrEmpty(i);
            int slot = n.getByte("Slot", (byte) -1) & 255;
            if (slot < 27) {
                o.ender.setStack(slot, ItemConv.fromNbt(withoutSlot(n)));
            }
        }
        return o;
    }

    /** Equipment keys in inventory index order 36..40 (boots, leggings, chestplate, helmet, off-hand). */
    private static final String[] EQUIPMENT = {"feet", "legs", "chest", "head", "offhand"};

    private static NbtCompound withoutSlot(NbtCompound n) {
        NbtCompound c = n.copy();
        c.remove("Slot");
        return c;
    }

    /** Writes back, unless the player came online meanwhile (their live data wins). */
    public boolean save() {
        if (Ac.server().getPlayerManager().getPlayer(player) != null) {
            return false;
        }
        NbtList inv = new NbtList();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inventory.getStack(i);
            if (!s.isEmpty()) {
                NbtCompound n = ItemConv.toNbt(s);
                n.putByte("Slot", (byte) i);
                inv.add(n);
            }
        }
        NbtCompound eq = root.getCompoundOrEmpty("equipment").copy();
        for (int k = 0; k < EQUIPMENT.length; k++) {
            ItemStack s = inventory.getStack(36 + k);
            if (s.isEmpty()) {
                eq.remove(EQUIPMENT[k]);
            } else {
                eq.put(EQUIPMENT[k], ItemConv.toNbt(s));
            }
        }
        root.put("equipment", eq);
        NbtList ec = new NbtList();
        for (int i = 0; i < 27; i++) {
            ItemStack s = ender.getStack(i);
            if (!s.isEmpty()) {
                NbtCompound n = ItemConv.toNbt(s);
                n.putByte("Slot", (byte) i);
                ec.add(n);
            }
        }
        root.put("Inventory", inv);
        root.put("EnderItems", ec);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".ac-tmp");
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            Ac.LOG.error("Could not write player data for {}", player, e);
            return false;
        }
    }

    /** Called when a player joins: any admin editing their offline inventory is closed without saving. */
    public static void onJoin(ServerPlayerEntity p) {
        UUID editor = EDITING.remove(p.getUuid());
        if (editor != null) {
            ServerPlayerEntity admin = Ac.server().getPlayerManager().getPlayer(editor);
            if (admin != null) {
                admin.closeHandledScreen();
                admin.sendMessage(com.vylorq.anticheat.util.Msg.prefixed(com.vylorq.anticheat.util.Msg.tr("inspect.target-joined")));
            }
        }
    }
}
