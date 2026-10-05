package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Protection;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block placement: claims, lobby, jail, freeze, shadow mode, block logging and custom blocks. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<BlockState> AC$BEFORE = new ThreadLocal<>();
    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<String> AC$CUSTOM = new ThreadLocal<>();

    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("HEAD"), cancellable = true)
    private void ac$beforePlace(ItemPlacementContext ctx, CallbackInfoReturnable<ActionResult> cir) {
        if (!Ac.running() || !(ctx.getPlayer() instanceof ServerPlayerEntity p) || !(ctx.getWorld() instanceof ServerWorld w)) {
            return;
        }
        BlockPos pos = ctx.getBlockPos();
        if (!Protection.canPlace(p, w, pos)) {
            com.vylorq.anticheat.feature.Movement.ghostBlock(p);
            p.currentScreenHandler.syncState();
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket(w, pos));
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }
        AC$BEFORE.set(w.getBlockState(pos));
        // Read now: the stack may be used up by the time the block is placed.
        AC$CUSTOM.set(com.vylorq.anticheat.feature.CustomBlocks.placedBy(ctx.getStack()));
    }

    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("RETURN"))
    private void ac$afterPlace(ItemPlacementContext ctx, CallbackInfoReturnable<ActionResult> cir) {
        BlockState before = AC$BEFORE.get();
        AC$BEFORE.remove();
        String custom = AC$CUSTOM.get();
        AC$CUSTOM.remove();
        if (before == null || !Ac.running() || !cir.getReturnValue().isAccepted()) {
            return;
        }
        if (custom != null && ctx.getWorld() instanceof ServerWorld cw && cw.getBlockState(ctx.getBlockPos()).isOf(net.minecraft.block.Blocks.NOTE_BLOCK)) {
            // A custom block item: the placed note block becomes that custom block.
            cw.setBlockState(ctx.getBlockPos(), com.vylorq.anticheat.feature.CustomBlocks.state(custom), net.minecraft.block.Block.NOTIFY_LISTENERS);
        }
        if (ctx.getPlayer() instanceof ServerPlayerEntity p && ctx.getWorld() instanceof ServerWorld w) {
            Protection.afterPlace(p, w, ctx.getBlockPos(), before);
        }
    }
}
