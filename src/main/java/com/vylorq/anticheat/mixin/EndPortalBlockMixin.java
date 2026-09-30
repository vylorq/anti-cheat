package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.EndLock;
import com.vylorq.anticheat.feature.Protection;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While the End is closed, End portals take nobody (and nothing) there, except built ones. Leaving the End still works. */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {
    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void ac$endClosed(ServerWorld world, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTarget> cir) {
        if (Ac.running() && world.getRegistryKey() != World.END && !EndLock.allowedAt(world, pos)) {
            if (entity instanceof ServerPlayerEntity p) {
                Protection.endClosed(p);
            }
            cir.setReturnValue(null);
        }
    }
}
