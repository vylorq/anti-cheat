package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Xray;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti-x-ray: just before the chunk data of a chunk packet is built, sections that contain hidden ores (or trap
 * spots) are prepared as modified copies (swapped in by ChunkSectionMixin). The real world never changes.
 */
@Mixin(ChunkDataS2CPacket.class)
public abstract class ChunkDataPacketMixin {
    @Inject(method = "<init>(Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/chunk/light/LightingProvider;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At(value = "NEW", target = "net/minecraft/network/packet/s2c/play/ChunkData"))
    private void ac$beginChunk(CallbackInfo ci, @Local(argsOnly = true) WorldChunk chunk) {
        if (Ac.running() && chunk.getWorld() != null && !chunk.getWorld().isClient()) {
            try {
                var replacements = Xray.modifiedSections(chunk);
                try {
                    com.vylorq.anticheat.feature.AntiEsp.hideContainers(chunk, replacements);
                } catch (Throwable t) {
                    Ac.LOG.error("Anti-ESP failed for a chunk; containers sent as they are", t);
                    com.vylorq.anticheat.feature.AntiEsp.HIDDEN_CONTAINERS.remove();
                }
                Xray.REPLACEMENTS.set(replacements);
            } catch (Throwable t) {
                Ac.LOG.error("Anti-xray failed for a chunk; sending it unmodified", t);
                Xray.REPLACEMENTS.remove();
            }
        }
    }

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/chunk/light/LightingProvider;Ljava/util/BitSet;Ljava/util/BitSet;)V",
            at = @At("RETURN"))
    private void ac$endChunk(CallbackInfo ci) {
        Xray.REPLACEMENTS.remove();
        com.vylorq.anticheat.feature.AntiEsp.HIDDEN_CONTAINERS.remove();
    }
}
