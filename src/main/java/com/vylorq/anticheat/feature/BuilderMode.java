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
        /** Builds in a draft copy of the lobby that the owner approves (instead of the real lobby). */
        public boolean draft;
        public PlayerSnapshot snapshot;
        /** Time warnings already given (5 minutes, 1 minute). */
        public int warned;
    }

    /** Made a builder while offline: starts when they next join (the time counts from then). */
    public static final class Pending {
        public String name;
        public long durationMs;
        public boolean anywhere;
        public boolean live;
        public String by;
        public long at;
    }

    private static Map<String, Pending> pending() {
        if (Ac.get().misc.pendingBuilders == null) {
            Ac.get().misc.pendingBuilders = new java.util.LinkedHashMap<>();
        }
        return Ac.get().misc.pendingBuilders;
    }

    /** Queues a builder grant for someone who isn't online. */
    public static void queue(String name, long durationMs, boolean anywhere, boolean live, String by) {
        Pending q = new Pending();
        q.name = name;
        q.durationMs = durationMs;
        q.anywhere = anywhere;
        q.live = live;
        q.by = by;
        q.at = System.currentTimeMillis();
        pending().put(name.toLowerCase(java.util.Locale.ROOT), q);
        Ac.markDirty("misc");
    }

    /** Cancels a queued grant. @return whether there was one */
    public static boolean unqueue(String name) {
        boolean had = pending().remove(name.toLowerCase(java.util.Locale.ROOT)) != null;
        if (had) {
            Ac.markDirty("misc");
        }
        return had;
    }

    public static java.util.Collection<Pending> queued() {
        return pending().values();
    }

    /** Blocks that aren't allowed even though they're blocks. */
    private static final Set<Block> DENIED = Set.of(Blocks.TNT, Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_VOID,
            Blocks.END_PORTAL_FRAME, Blocks.DRAGON_EGG, Blocks.BEDROCK, Blocks.REINFORCED_DEEPSLATE, Blocks.RESPAWN_ANCHOR,
            Blocks.BUDDING_AMETHYST, Blocks.SPAWNER, Blocks.INFESTED_STONE,
            // Workstations: they open menus that make items.
            Blocks.CRAFTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.GRINDSTONE, Blocks.LOOM,
            Blocks.CARTOGRAPHY_TABLE, Blocks.STONECUTTER, Blocks.SMITHING_TABLE,
            // Only in build files and tools, never good in a lobby.
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY,
            Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW);

    private static Map<UUID, Builder> builders() {
        return Ac.get().misc.builders;
    }

    public static boolean is(ServerPlayerEntity p) {
        return Ac.running() && builders().containsKey(p.getUuid());
    }

    public static Builder get(UUID id) {
        return builders().get(id);
    }

    /** Whether a builder may have this item: plain building blocks and the builder tools only. */
    public static boolean allowed(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        String tool = Tools.toolOf(stack);
        if (Tools.BUILDER_WAND.equals(tool) || Tools.BUILDER_MENU.equals(tool) || Tools.BUILDER_BRUSH.equals(tool)) {
            return true;
        }
        if (!(stack.getItem() instanceof BlockItem bi)) {
            return false;
        }
        // Copies of placed blocks with their contents (Ctrl + pick block) are out.
        if (stack.contains(DataComponentTypes.BLOCK_ENTITY_DATA) || stack.contains(DataComponentTypes.CONTAINER)
                || stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            return false;
        }
        return allowedBlock(bi.getBlock());
    }

    /** Whether a builder may place this block (by hand or with the builder tools). */
    public static boolean allowedBlock(Block b) {
        if (DENIED.contains(b)) {
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
        if (b == null || b.anywhere) {
            return true;
        }
        if (b.draft) {
            return BuilderDrafts.inDraft(p.getUuid(), com.vylorq.anticheat.util.Mc.worldId(w), pos);
        }
        if (Ac.get().lobby.data().area == null) {
            return true;
        }
        return LobbyFeature.in(w, pos);
    }

    public static void start(ServerPlayerEntity by, ServerPlayerEntity p, long durationMs, boolean anywhere) {
        start(by, p, durationMs, anywhere, false);
    }

    /**
     * @param live build straight in the real lobby (no draft to approve)
     */
    public static void start(ServerPlayerEntity by, ServerPlayerEntity p, long durationMs, boolean anywhere, boolean live) {
        if (WaitingRoomFeature.waiting(p)) {
            // Made a builder straight from the waiting room: they're let in first (or they'd stay stuck there).
            WaitingRoomFeature.letIn(by, p);
        }
        Ac.get().misc.builderNames.put(p.getUuid(), p.getGameProfile().name());
        BuilderDrafts.backup("builder-start-" + p.getGameProfile().name());
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
        giveTools(p);
        p.currentScreenHandler.sendContentUpdates();
        Ac.server().getCommandManager().sendCommandTree(p);
        b.draft = !anywhere && !live && BuilderDrafts.start(p);
        Ac.saveNow("misc");
        String mode = (anywhere ? "anywhere" : b.draft ? "draft" : "lobby")
                + (durationMs > 0 ? " for " + com.vylorq.anticheat.core.util.Durations.format(durationMs) : "");
        Staff.log(by, "builder-add", p.getUuid(), b.name, mode);
        BuilderLog.event(p, "START", "by " + (by == null ? "console" : by.getGameProfile().name()) + ", " + mode);
        Msg.send(p, "builder.you-are");
        if (b.draft) {
            Msg.send(p, "draft.explain");
        }
    }

    /** Ends builder mode and gives back what they had. Call while they're online. */
    public static boolean end(ServerPlayerEntity by, ServerPlayerEntity p) {
        Builder b = builders().remove(p.getUuid());
        if (b == null) {
            return false;
        }
        BuilderTools.forget(p.getUuid());
        Ac.saveNow("misc");
        p.closeHandledScreen();
        com.vylorq.anticheat.ui.BossBars.hide(p, com.vylorq.anticheat.ui.BossBars.Kind.BUILDER);
        BuilderLog.event(p, "END", "by " + (by == null ? "time/server" : by.getGameProfile().name()));
        BuilderLog.close(p.getUuid());
        if (b.snapshot != null) {
            // From a draft they go back where they were.
            PlayerState.apply(p, b.snapshot, b.draft);
        } else {
            p.getInventory().clear();
            p.changeGameMode(GameMode.SURVIVAL);
        }
        Ac.server().getCommandManager().sendCommandTree(p);
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
        Pending q = pending().remove(p.getGameProfile().name().toLowerCase(java.util.Locale.ROOT));
        if (q == null && p.getGameProfile().name().startsWith(".")) {
            q = pending().remove(p.getGameProfile().name().substring(1).toLowerCase(java.util.Locale.ROOT));   // Bedrock prefix
        }
        if (q != null && !builders().containsKey(p.getUuid())) {
            Ac.markDirty("misc");
            Pending grant = q;
            // A moment after joining (after the waiting room has had its say: it lets them straight in).
            OwnerPowers.later(20, () -> {
                if (!p.isRemoved() && !builders().containsKey(p.getUuid())) {
                    start(null, p, grant.durationMs, grant.anywhere, grant.live);
                    Ac.LOG.info("{} became a builder on joining (queued by {})", p.getGameProfile().name(), grant.by);
                }
            });
            return;
        }
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
        giveTools(p);
        BuilderLog.event(p, "JOIN", "");
        if (b.draft) {
            BuilderDrafts.start(p);
        }
    }

    public static void onLeave(ServerPlayerEntity p) {
        if (builders().containsKey(p.getUuid())) {
            BuilderLog.event(p, "LEAVE", "");
            BuilderLog.close(p.getUuid());
        }
    }

    /** The builder wand and builder menu, unless they already have them. */
    public static void giveTools(ServerPlayerEntity p) {
        var inv = p.getInventory();
        boolean wand = false;
        boolean menu = false;
        for (int i = 0; i < inv.size(); i++) {
            wand |= Tools.is(inv.getStack(i), Tools.BUILDER_WAND);
            menu |= Tools.is(inv.getStack(i), Tools.BUILDER_MENU);
        }
        if (!wand) {
            inv.insertStack(Tools.builderWand());
        }
        if (!menu) {
            inv.insertStack(Tools.builderMenu());
        }
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
            if (b.draft) {
                BuilderDrafts.keepInside(p);
            }
            timer(p, b, now);
        }
        BuilderDrafts.tick();
        BuilderLog.flush();
    }

    /** The time-left bar, and warnings 5 minutes and 1 minute before the end. */
    private static void timer(ServerPlayerEntity p, Builder b, long now) {
        if (b.until <= 0) {
            return;
        }
        long left = Math.max(0, b.until - now);
        float progress = (float) left / Math.max(1, b.until - b.since);
        com.vylorq.anticheat.ui.BossBars.show(p, com.vylorq.anticheat.ui.BossBars.Kind.BUILDER,
                net.minecraft.text.Text.literal(Msg.trFor(p, "builder.bar", com.vylorq.anticheat.core.util.Durations.format(left))), progress, 3);
        if (left <= 60_000 && b.warned < 2) {
            b.warned = 2;
            Msg.send(p, "builder.warn", com.vylorq.anticheat.core.util.Durations.format(left));
        } else if (left <= 300_000 && b.warned < 1) {
            b.warned = 1;
            Msg.send(p, "builder.warn", com.vylorq.anticheat.core.util.Durations.format(left));
        }
    }

    public static void denied(ServerPlayerEntity p) {
        denied(p, com.vylorq.anticheat.util.Mc.itemId(p.getMainHandStack().getItem()));
    }

    /** Tells the builder no, and logs what they tried. */
    public static void denied(ServerPlayerEntity p, String what) {
        Msg.actionBar(p, Msg.trFor(p, "builder.blocks-only"));
        BuilderLog.event(p, "BLOCKED", what);
    }
}
