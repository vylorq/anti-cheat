package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.vylorq.anticheat.feature.AntiEsp;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Anti-ESP: containers left out of a chunk also leave out their block data. */
@Mixin(ChunkData.class)
public abstract class ChunkDataMixin {
    @ModifyExpressionValue(method = "<init>(Lnet/minecraft/world/chunk/WorldChunk;)V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/WorldChunk;getBlockEntities()Ljava/util/Map;"))
    private Map<BlockPos, BlockEntity> ac$withoutHidden(Map<BlockPos, BlockEntity> original) {
        Set<BlockPos> hidden = AntiEsp.HIDDEN_CONTAINERS.get();
        if (hidden == null || hidden.isEmpty()) {
            return original;
        }
        Map<BlockPos, BlockEntity> copy = new HashMap<>(original);
        copy.keySet().removeAll(hidden);
        return copy;
    }
}
