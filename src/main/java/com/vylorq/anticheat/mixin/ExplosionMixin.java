package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.vylorq.anticheat.feature.WorldGuard;
import com.vylorq.anticheat.feature.Xray;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/** Explosions never break blocks in claims (unless allowed) or the lobby; they are logged and reveal ores. */
@Mixin(ExplosionImpl.class)
public abstract class ExplosionMixin {
    @Shadow
    @Final
    private ServerWorld world;

    @ModifyReturnValue(method = "getBlocksToDestroy", at = @At("RETURN"), require = 0)
    private List<BlockPos> ac$filter(List<BlockPos> original) {
        List<BlockPos> list = new ArrayList<>(original);
        WorldGuard.filterExplosion(world, list, ((Explosion) (Object) this).getCausingEntity());
        Xray.afterExplosion(world, list);
        return list;
    }
}
