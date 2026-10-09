package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.feature.Gems;
import net.minecraft.block.BlockState;
import net.minecraft.block.NoteBlock;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The gem ores are note block states: they never change, play or get tuned like a note block. */
@Mixin(NoteBlock.class)
public abstract class NoteBlockMixin {
    @Inject(method = "getStateForNeighborUpdate", at = @At("HEAD"), cancellable = true)
    private void ac$oreStays(CallbackInfoReturnable<BlockState> cir, @Local(argsOnly = true, ordinal = 0) BlockState state) {
        if (Gems.oreOf(state) != null) {
            cir.setReturnValue(state);
        }
    }

    @Inject(method = "neighborUpdate", at = @At("HEAD"), cancellable = true)
    private void ac$orePower(CallbackInfo ci, @Local(argsOnly = true) BlockState state) {
        if (Gems.oreOf(state) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "onUse", at = @At("HEAD"), cancellable = true)
    private void ac$oreUse(CallbackInfoReturnable<ActionResult> cir, @Local(argsOnly = true) BlockState state) {
        if (Gems.oreOf(state) != null) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "onUseWithItem", at = @At("HEAD"), cancellable = true)
    private void ac$oreUseItem(CallbackInfoReturnable<ActionResult> cir, @Local(argsOnly = true) BlockState state) {
        if (Gems.oreOf(state) != null) {
            cir.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "onBlockBreakStart", at = @At("HEAD"), cancellable = true)
    private void ac$oreHit(CallbackInfo ci, @Local(argsOnly = true) BlockState state) {
        if (Gems.oreOf(state) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "onSyncedBlockEvent", at = @At("HEAD"), cancellable = true)
    private void ac$oreSilent(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) BlockState state) {
        if (Gems.oreOf(state) != null) {
            cir.setReturnValue(false);
        }
    }
}
