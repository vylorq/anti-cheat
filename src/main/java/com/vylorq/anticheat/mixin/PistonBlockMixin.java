package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Redstone;
import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.piston.PistonHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pistons can't push or pull blocks across a claim border, and per-chunk piston spam is limited. */
@Mixin(PistonBlock.class)
public abstract class PistonBlockMixin {
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void ac$move(World world, BlockPos pos, Direction dir, boolean extend, CallbackInfoReturnable<Boolean> cir) {
        if (!Ac.running() || !(world instanceof ServerWorld sw)) {
            return;
        }
        if (!Redstone.onPiston(sw, pos)) {
            cir.setReturnValue(false);
            return;
        }
        PistonHandler h = new PistonHandler(world, pos, dir, extend);
        if (!h.calculatePush()) {
            return;
        }
        Direction motion = h.getMotionDirection();
        for (BlockPos moved : h.getMovedBlocks()) {
            if (com.vylorq.anticheat.feature.HomeTeleport.isPad(world, moved)) {
                cir.setReturnValue(false);
                return;
            }
            if (!WorldGuard.crossAllowed(world, pos, moved) || !WorldGuard.crossAllowed(world, pos, moved.offset(motion))) {
                cir.setReturnValue(false);
                return;
            }
        }
        for (BlockPos broken : h.getBrokenBlocks()) {
            if (com.vylorq.anticheat.feature.HomeTeleport.isPad(world, broken)) {
                cir.setReturnValue(false);
                return;
            }
            if (!WorldGuard.crossAllowed(world, pos, broken)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
