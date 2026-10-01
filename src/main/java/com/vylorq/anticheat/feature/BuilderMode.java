package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.PlayerSnapshot;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.AbstractSkullBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Temporary builder: a player gets creative mode to build (for example the lobby), but only with plain building
 * blocks. They can't take anything else from the creative menu, can't place or open chests, furnaces, crafting tables
 * and other containers, can't use or hit entities, and can't pass items to anyone. Their inventory, game mode and
 * the rest come back when it ends. When a lobby is set, they can only build inside it.
 */
public final class BuilderMode {
    private BuilderMode() {
    }

    public static final class Builder {
        public String name;
        public long since;
        /** When it ends by itself (0: when removed). */
        public long until;
        /** Build anywhere, not just in the lobby. */
        public boolean anywhere;
        public PlayerSnapshot snapshot;
    }

    /** Blocks that aren't allowed even though they're blocks. */
    private static final Set<Block> DENIED = Set.of(Blocks.TNT, Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_VOID,
            Blocks.END_PORTAL_FRAME, Blocks.DRAGON_EGG, Blocks.BEDROCK, Blocks.REINFORCED_DEEPSLATE, Blocks.RESPAWN_ANCHOR,
            Blocks.BUDDING_AMETHYST, Blocks.SPAWNER, Blocks.INFESTED_STONE,
            // Workstations: they open menus that make items.
            Blocks.CRAFTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.GRINDSTONE, Blocks.LOOM,
            Blocks.CARTOGRAPHY_TABLE, Blocks.STONECUTTER, Blocks.SMITHING_TABLE);

    private static Map<UUID, Builder> builders() {
        return Ac.get().misc.builders;
    }

    public static boolean is(ServerPlayerEntity p) {
        return Ac.running() && builders().containsKey(p.getUuid());
    }

    public static Builder get(UUID id) {
        return builders().get(id);
    }

    /** Whether a builder may have this item: plain building blocks only. */
    public static boolean allowed(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        if (!(stack.getItem() instanceof BlockItem bi)) {
            return false;
        }
        Block b = bi.getBlock();
        if (DENIED.contains(b)) {
            return false;
        }
        // Copies of placed blocks with their contents (Ctrl + pick block) are out.
        if (stack.contains(DataComponentTypes.BLOCK_ENTITY_DATA) || stack.contains(DataComponentTypes.CONTAINER)
                || stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            return false;
        }
        if (b instanceof BlockEntityProvider) {
            // Blocks with their own data: only decorative ones (no storage, no menus).
            return b instanceof AbstractSignBlock || b instanceof AbstractBannerBlock || b instanceof AbstractSkullBlock
                    || b instanceof BedBlock || b instanceof BellBlock;
        }
        return true;
    }

    /** Whether a builder may right-click this block: nothing that opens a menu or holds items. */
    public static boolean mayUse(ServerWorld w, BlockPos pos) {
        BlockState s = w.getBlockState(pos);
        if (s.getBlock() instanceof EnderChestBlock || s.createScreenHandlerFactory(w, pos) != null) {
            return false;
        }
        BlockEntity be = w.getBlockEntity(pos);
        return !(be instanceof Inventory) && !(be instanceof NamedScreenHandlerFactory);
    }

    /** Whether a builder may break this block (containers would spill their items). */
    public static boolean mayBreak(ServerWorld w, BlockPos pos) {
        return !(w.getBlockEntity(pos) instanceof Inventory);
    }

    /** Whether a builder may build here: in the lobby when one is set (unless they may build anywhere). */
    public static boolean mayBuildAt(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        Builder b = builders().get(p.getUuid());
        if (b == null || b.anywhere || Ac.get().lobby.data().area == null) {
            return true;
        }
        return LobbyFeature.in(w, pos);
    }

    public static void start(ServerPlayerEntity by, ServerPlayerEntity p, long durationMs, boolean anywhere) {
        Builder b = new Builder();
        b.name = p.getGameProfile().name();
        b.since = System.currentTimeMillis();
        b.until = durationMs > 0 ? b.since + durationMs : 0;
        b.anywhere = anywhere;
        b.snapshot = PlayerState.capture(p, "builder");
        builders().put(p.getUuid(), b);
        Ac.saveNow("misc");
        p.getInventory().clear();
        p.changeGameMode(GameMode.CREATIVE);
        p.currentScreenHandler.sendContentUpdates();
        Staff.log(by, "builder-add", p.getUuid(), b.name, (anywhere ? "anywhere" : "lobby")
                + (durationMs > 0 ? " for " + com.vylorq.anticheat.core.util.Durations.format(durationMs) : ""));
        Msg.send(p, "builder.you-are");
    }

    /** Ends builder mode and gives back what they had. Call while they're online. */
    public static boolean end(ServerPlayerEntity by, ServerPlayerEntity p) {
        Builder b = builders().remove(p.getUuid());
        if (b == null) {
            return false;
        }
        Ac.saveNow("misc");
        p.closeHandledScreen();
        if (b.snapshot != null) {
            PlayerState.apply(p, b.snapshot, false);
        } else {
            p.getInventory().clear();
            p.changeGameMode(GameMode.SURVIVAL);
        }
        Staff.log(by, "builder-end", p.getUuid(), b.name, "");
        Msg.send(p, "builder.ended");
        Staff.broadcast(Msg.prefixed(Msg.tr("builder.ended-staff", b.name)));
        return true;
    }

    /** Takes away anything that isn't a building block. @return how many stacks */
    public static int sanitize(ServerPlayerEntity p) {
        var inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (!allowed(inv.getStack(i))) {
                inv.setStack(i, ItemStack.EMPTY);
                n++;
            }
        }
        if (!allowed(p.currentScreenHandler.getCursorStack())) {
            p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            n++;
        }
        if (n > 0) {
            p.currentScreenHandler.sendContentUpdates();
        }
        return n;
    }

    public static void onJoin(ServerPlayerEntity p) {
        Builder b = builders().get(p.getUuid());
        if (b == null) {
            return;
        }
        if (b.until > 0 && System.currentTimeMillis() > b.until) {
            end(null, p);
            return;
        }
        p.changeGameMode(GameMode.CREATIVE);
        sanitize(p);
    }

    /** Every second: ends timed builder modes, keeps builders in creative with building blocks only. */
    public static void tick() {
        if (builders().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity p : new ArrayList<>(Ac.server().getPlayerManager().getPlayerList())) {
            Builder b = builders().get(p.getUuid());
            if (b == null) {
                continue;
            }
            if (b.until > 0 && now > b.until) {
                end(null, p);
                continue;
            }
            if (!p.isCreative()) {
                p.changeGameMode(GameMode.CREATIVE);
            }
            sanitize(p);
        }
    }

    public static void denied(ServerPlayerEntity p) {
        Msg.actionBar(p, Msg.trFor(p, "builder.blocks-only"));
    }
}
