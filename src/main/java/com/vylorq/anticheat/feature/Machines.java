package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Machines: special blocks (SpecialBlocks) that work by themselves while their chunk is loaded. They're made of the
 * game's own blocks, so they work for Bedrock players too, and put what they make into a chest (or barrel) touching
 * them.
 * <ul>
 * <li><b>Auto-Miner</b> (a blast furnace): digs a 3x3 shaft straight down under itself, one block every second, while
 * it has fuel (coal, charcoal or Topaz) in its fuel slot: one coal for every 16 blocks.</li>
 * <li><b>Ore Crusher</b> (a barrel): turns each raw iron, gold and copper put in it into two ingots.</li>
 * <li><b>Auto-Farm</b> (a composter): harvests ripe crops within 4 blocks of it and plants them again.</li>
 * <li><b>Mob Grinder</b> (a grindstone): hurts monsters right on top of it and gathers what they drop.</li>
 * <li><b>Teleport Pad</b> (a lodestone): place two; stand on one and sneak to go to the other.</li>
 * <li><b>Item Sorter</b> (chiseled copper): takes what's in the chest on top of it and puts each item into a chest
 * next to it that already holds that item.</li>
 * </ul>
 * None of them work in someone else's claim or in the lobby.
 */
public final class Machines {
    private Machines() {
    }

    static void defs(Map<String, SpecialBlocks.Def> defs) {
        defs.put("miner", new SpecialBlocks.Def(Items.BLAST_FURNACE, "§6Auto-Miner", "§7Digs a 3x3 shaft down. Coal in its fuel slot;",
                "§7a chest next to it gets what it digs."));
        defs.put("crusher", new SpecialBlocks.Def(Items.BARREL, "§7Ore Crusher", "§7Put raw iron, gold or copper in it:",
                "§7each becomes two ingots."));
        defs.put("farm", new SpecialBlocks.Def(Items.COMPOSTER, "§aAuto-Farm", "§7Harvests and replants ripe crops",
                "§7within 4 blocks; a chest next to it gets the crops."));
        defs.put("grinder", new SpecialBlocks.Def(Items.GRINDSTONE, "§cMob Grinder", "§7Hurts monsters standing on it;",
                "§7a chest next to it gets their drops."));
        defs.put("pad", new SpecialBlocks.Def(Items.LODESTONE, "§dTeleport Pad", "§7Place two: stand on one and sneak",
                "§7to go to the other."));
        defs.put("sorter", new SpecialBlocks.Def(Items.CHISELED_COPPER, "§3Item Sorter", "§7Takes what's in the chest on top",
                "§7and sorts it into the chests next to it."));
    }

    /** A special block was placed: the value it's remembered by (a pad gets paired here), or null to forget it. */
    static String placed(ServerPlayerEntity p, ServerWorld w, BlockPos at, String kind) {
        if (kind.equals("pad")) {
            // The first pad waits; the next one this player places pairs with it.
            for (var e : SpecialBlocks.all("pad")) {
                String v = SpecialBlocks.kindAt(e.getKey(), e.getValue());
                if (v != null && v.equals("pad:wait:" + p.getUuidAsString())) {
                    String id = UUID.randomUUID().toString().substring(0, 8);
                    SpecialBlocks.set(e.getKey(), e.getValue(), "pad:" + id);
                    Msg.send(p, "machines.pad-linked");
                    return "pad:" + id;
                }
            }
            Msg.send(p, "machines.pad-first");
            return "pad:wait:" + p.getUuidAsString();
        }
        if (kind.equals("miner")) {
            return "miner:" + (at.getY() - 1);
        }
        if (isMachine(kind)) {
            Msg.send(p, "machines.placed", SpecialBlocks.DEFS.get(kind).name());
        }
        return kind;
    }

    static boolean isMachine(String kind) {
        return List.of("miner", "crusher", "farm", "grinder", "pad", "sorter").contains(kind.split(":")[0]);
    }

    /** A pad was broken: its partner waits for a new pair. */
    static void broken(ServerWorld w, BlockPos pos, String kind) {
        if (kind.startsWith("pad:") && !kind.startsWith("pad:wait:")) {
            for (var e : SpecialBlocks.all("pad")) {
                if (kind.equals(SpecialBlocks.kindAt(e.getKey(), e.getValue())) && !(e.getKey() == w && e.getValue().equals(pos))) {
                    SpecialBlocks.set(e.getKey(), e.getValue(), "pad:alone");
                }
            }
        }
    }

    private static boolean allowed(ServerWorld w, BlockPos pos) {
        return !WorldGuard.claimedOrLobby(w, pos) || Teams.at(w, pos) != null;
    }

    // ---------------------------------------------------------------- every tick

    public static void tick(long ticks) {
        if (ticks % 10 == 0) {
            pads();
        }
        if (ticks % 20 == 0) {
            each("miner", Machines::miner);
            each("grinder", Machines::grinder);
        }
        if (ticks % 40 == 0) {
            each("crusher", Machines::crusher);
            each("sorter", Machines::sorter);
        }
        if (ticks % 100 == 0) {
            each("farm", Machines::farm);
        }
    }

    private interface Work {
        void run(ServerWorld w, BlockPos pos, String value);
    }

    private static void each(String kind, Work work) {
        for (var e : SpecialBlocks.all(kind)) {
            ServerWorld w = e.getKey();
            BlockPos pos = e.getValue();
            if (!w.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            try {
                work.run(w, pos, SpecialBlocks.kindAt(w, pos));
            } catch (Exception ex) {
                Ac.LOG.debug("machine " + kind, ex);
            }
        }
    }

    /** The chest or barrel touching it (not the one on top, which is an Item Sorter's input), or null. */
    private static Inventory output(ServerWorld w, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (d == Direction.UP) {
                continue;
            }
            BlockPos q = pos.offset(d);
            if (SpecialBlocks.kindAt(w, q) != null) {
                continue;
            }
            if (w.getBlockEntity(q) instanceof Inventory inv && (w.getBlockState(q).isOf(Blocks.CHEST) || w.getBlockState(q).isOf(Blocks.BARREL)
                    || w.getBlockState(q).isOf(Blocks.TRAPPED_CHEST))) {
                return inv;
            }
        }
        return null;
    }

    /** Puts a stack into the inventory (or drops what doesn't fit next to the machine). */
    private static void give(ServerWorld w, BlockPos pos, Inventory out, ItemStack st) {
        if (out != null) {
            for (int i = 0; i < out.size() && !st.isEmpty(); i++) {
                ItemStack in = out.getStack(i);
                if (in.isEmpty()) {
                    out.setStack(i, st.copy());
                    st.setCount(0);
                } else if (ItemStack.areItemsAndComponentsEqual(in, st) && in.getCount() < in.getMaxCount()) {
                    int move = Math.min(st.getCount(), in.getMaxCount() - in.getCount());
                    in.increment(move);
                    st.decrement(move);
                }
            }
            out.markDirty();
        }
        if (!st.isEmpty()) {
            w.spawnEntity(new ItemEntity(w, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, st));
        }
    }

    // ---------------------------------------------------------------- the machines

    private static void miner(ServerWorld w, BlockPos pos, String value) {
        if (!w.getBlockState(pos).isOf(Blocks.BLAST_FURNACE) || !(w.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        int y = Integer.parseInt(value.substring("miner:".length()));
        if (y <= w.getBottomY() + 1) {
            return;
        }
        // Fuel: one coal (or charcoal, or Topaz) for every 16 blocks.
        ItemStack fuel = furnace.getStack(1);
        int credit = tickets.getOrDefault(SpecialBlocks.key(w, pos), 0);
        if (credit <= 0) {
            if (fuel.isOf(Items.COAL) || fuel.isOf(Items.CHARCOAL) || Gems.isTopaz(fuel)) {
                credit = Gems.isTopaz(fuel) ? 64 : 16;
                fuel.decrement(1);
                furnace.markDirty();
            } else {
                return;
            }
        }
        Inventory out = output(w, pos);
        // The next block of the 3x3 layer at depth y that isn't air.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos q = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                BlockState st = w.getBlockState(q);
                if (st.isAir() || !st.getFluidState().isEmpty()) {
                    continue;
                }
                if (st.getHardness(w, q) < 0 || !allowed(w, q) || SpecialBlocks.kindAt(w, q) != null) {
                    continue;
                }
                for (ItemStack drop : Block.getDroppedStacks(st, w, q, w.getBlockEntity(q), null, new ItemStack(Items.IRON_PICKAXE))) {
                    give(w, pos, out, drop);
                }
                w.breakBlock(q, false);
                tickets.put(SpecialBlocks.key(w, pos), credit - 1);
                return;
            }
        }
        // This layer is done: go one down.
        SpecialBlocks.set(w, pos, "miner:" + (y - 1));
        tickets.put(SpecialBlocks.key(w, pos), credit);
    }

    /** How many more blocks the Auto-Miner can dig on the fuel it already burned. */
    private static final Map<String, Integer> tickets = new java.util.HashMap<>();

    private static void crusher(ServerWorld w, BlockPos pos, String value) {
        if (!(w.getBlockEntity(pos) instanceof Inventory inv)) {
            return;
        }
        boolean did = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            var into = st.isOf(Items.RAW_IRON) ? Items.IRON_INGOT : st.isOf(Items.RAW_GOLD) ? Items.GOLD_INGOT
                    : st.isOf(Items.RAW_COPPER) ? Items.COPPER_INGOT : null;
            if (into == null || st.isEmpty()) {
                continue;
            }
            int n = st.getCount();
            inv.setStack(i, ItemStack.EMPTY);
            give(w, pos, inv, new ItemStack(into, n * 2));
            did = true;
        }
        if (did) {
            inv.markDirty();
            w.playSound(null, pos, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.5f, 0.8f);
        }
    }

    private static void farm(ServerWorld w, BlockPos pos, String value) {
        if (!allowed(w, pos)) {
            return;
        }
        Inventory out = output(w, pos);
        for (BlockPos q : BlockPos.iterate(pos.add(-4, -1, -4), pos.add(4, 1, 4))) {
            BlockState st = w.getBlockState(q);
            if (st.getBlock() instanceof CropBlock crop && crop.isMature(st)) {
                for (ItemStack drop : Block.getDroppedStacks(st, w, q.toImmutable(), null)) {
                    give(w, pos, out, drop);
                }
                w.setBlockState(q, crop.withAge(0));
            }
        }
    }

    private static void grinder(ServerWorld w, BlockPos pos, String value) {
        Box top = new Box(pos.up()).expand(1.5, 1, 1.5);
        for (HostileEntity m : w.getEntitiesByClass(HostileEntity.class, top, m -> m.isAlive() && !Bosses.isBoss(m))) {
            m.damage(w, w.getDamageSources().generic(), 6f);
        }
        Inventory out = output(w, pos);
        for (ItemEntity it : w.getEntitiesByClass(ItemEntity.class, new Box(pos).expand(4), it -> it.isAlive() && it.age > 5)) {
            ItemStack st = it.getStack().copy();
            it.discard();
            give(w, pos, out, st);
        }
    }

    private static void sorter(ServerWorld w, BlockPos pos, String value) {
        if (!(w.getBlockEntity(pos.up()) instanceof Inventory in)) {
            return;
        }
        List<Inventory> outs = new ArrayList<>();
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (w.getBlockEntity(pos.offset(d)) instanceof Inventory inv) {
                outs.add(inv);
            }
        }
        for (int i = 0; i < in.size(); i++) {
            ItemStack st = in.getStack(i);
            if (st.isEmpty()) {
                continue;
            }
            for (Inventory out : outs) {
                boolean holds = false;
                for (int k = 0; k < out.size() && !holds; k++) {
                    holds = ItemStack.areItemsAndComponentsEqual(out.getStack(k), st);
                }
                if (!holds) {
                    continue;
                }
                for (int k = 0; k < out.size() && !st.isEmpty(); k++) {
                    ItemStack o = out.getStack(k);
                    if (o.isEmpty()) {
                        out.setStack(k, st.copy());
                        st.setCount(0);
                    } else if (ItemStack.areItemsAndComponentsEqual(o, st) && o.getCount() < o.getMaxCount()) {
                        int move = Math.min(st.getCount(), o.getMaxCount() - o.getCount());
                        o.increment(move);
                        st.decrement(move);
                    }
                }
                out.markDirty();
                if (st.isEmpty()) {
                    break;
                }
            }
        }
        in.markDirty();
    }

    private static final Map<UUID, Long> PAD_NEXT = new java.util.HashMap<>();

    /** Someone sneaking on a paired pad goes to its partner (then waits two seconds before the next jump). */
    private static void pads() {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (!p.isSneaking() || !p.isOnGround() || p.getEntityWorld().getTime() < PAD_NEXT.getOrDefault(p.getUuid(), 0L)) {
                continue;
            }
            ServerWorld w = p.getEntityWorld();
            BlockPos under = p.getBlockPos().down();
            String v = SpecialBlocks.kindAt(w, under);
            if (v == null || !v.startsWith("pad:") || v.startsWith("pad:wait:") || v.equals("pad:alone")) {
                continue;
            }
            for (var e : SpecialBlocks.all("pad")) {
                if (v.equals(SpecialBlocks.kindAt(e.getKey(), e.getValue())) && !(e.getKey() == w && e.getValue().equals(under))) {
                    ServerWorld to = e.getKey();
                    BlockPos dest = e.getValue();
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.8, 0.4, 0.3);
                    Movement.grace(p, 20);
                    com.vylorq.anticheat.util.Mc.teleport(p, to, dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5, p.getYaw(), p.getPitch());
                    to.playSound(null, dest, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 0.8f, 1.2f);
                    PAD_NEXT.put(p.getUuid(), to.getTime() + 40);
                    break;
                }
            }
        }
    }
}
