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
        NbtList inv = o.root.getList("Inventory", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < inv.size(); i++) {
            NbtCompound n = inv.getCompound(i);
            int slot = n.getByte("Slot") & 255;
            int idx = slot < 36 ? slot : switch (slot) {
                case 100 -> 36;
                case 101 -> 37;
                case 102 -> 38;
                case 103 -> 39;
                case 150 -> 40; // -106 as unsigned
                default -> -1;
            };
            if (idx >= 0) {
                o.inventory.setStack(idx, ItemStack.fromNbt(ItemConv.registries(), n).orElse(ItemStack.EMPTY));
            }
        }
        NbtList ec = o.root.getList("EnderItems", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < ec.size(); i++) {
            NbtCompound n = ec.getCompound(i);
            int slot = n.getByte("Slot") & 255;
            if (slot < 27) {
                o.ender.setStack(slot, ItemStack.fromNbt(ItemConv.registries(), n).orElse(ItemStack.EMPTY));
            }
        }
        return o;
    }

    /** Writes back, unless the player came online meanwhile (their live data wins). */
    public boolean save() {
        if (Ac.server().getPlayerManager().getPlayer(player) != null) {
            return false;
        }
        NbtList inv = new NbtList();
        for (int i = 0; i < 41; i++) {
            ItemStack s = inventory.getStack(i);
            if (s.isEmpty()) {
                continue;
            }
            int slot = i < 36 ? i : switch (i) {
                case 36 -> 100;
                case 37 -> 101;
                case 38 -> 102;
                case 39 -> 103;
                default -> -106;
            };
            NbtCompound prefix = new NbtCompound();
            prefix.putByte("Slot", (byte) slot);
            inv.add(s.toNbt(ItemConv.registries(), prefix));
        }
        NbtList ec = new NbtList();
        for (int i = 0; i < 27; i++) {
            ItemStack s = ender.getStack(i);
            if (s.isEmpty()) {
                continue;
            }
            NbtCompound prefix = new NbtCompound();
            prefix.putByte("Slot", (byte) i);
            ec.add(s.toNbt(ItemConv.registries(), prefix));
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
