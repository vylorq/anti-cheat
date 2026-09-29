package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water and lava can't flow into a claim from outside. */
@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {
    @Inject(method = "flow", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$flow(CallbackInfo ci, @Local(argsOnly = true) WorldAccess world, @Local(argsOnly = true) BlockPos to,
                         @Local(argsOnly = true) Direction dir) {
        if (world instanceof World w && !WorldGuard.crossAllowed(w, to.offset(dir.getOpposite()), to)) {
            ci.cancel();
        }
    }
}
