package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.feature.CustomBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.NoteBlock;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom blocks are note blocks in special states: they must never change. No new instrument from the blocks above
 * or below, no redstone power, no tuning (right-clicking one with a block in hand places the block instead).
 */
@Mixin(NoteBlock.class)
public abstract class NoteBlockMixin {
    @Inject(method = "getStateForNeighborUpdate", at = @At("HEAD"), cancellable = true)
    private void ac$keepShape(CallbackInfoReturnable<BlockState> cir, @Local(argsOnly = true, ordinal = 0) BlockState state) {
        if (CustomBlocks.isCustom(state)) {
            cir.setReturnValue(state);
        }
    }

    @Inject(method = "neighborUpdate", at = @At("HEAD"), cancellable = true)
    private void ac$noPower(CallbackInfo ci, @Local(argsOnly = true) BlockState state) {
        if (CustomBlocks.isCustom(state)) {
            ci.cancel();
        }
    }

    @Inject(method = "onUse", at = @At("HEAD"), cancellable = true)
    private void ac$noTuning(CallbackInfoReturnable<ActionResult> cir, @Local(argsOnly = true) BlockState state) {
        if (CustomBlocks.isCustom(state)) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }
}
