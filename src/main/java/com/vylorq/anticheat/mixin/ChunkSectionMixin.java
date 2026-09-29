package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.feature.Xray;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Serializes a modified copy instead of the real section while a chunk packet is built (anti-x-ray). */
@Mixin(ChunkSection.class)
public abstract class ChunkSectionMixin {
    @Unique
    private ChunkSection ac$replacement() {
        Map<ChunkSection, ChunkSection> m = Xray.REPLACEMENTS.get();
        return m == null ? null : m.get((ChunkSection) (Object) this);
    }

    @Inject(method = "getPacketSize", at = @At("HEAD"), cancellable = true)
    private void ac$packetSize(CallbackInfoReturnable<Integer> cir) {
        ChunkSection r = ac$replacement();
        if (r != null) {
            cir.setReturnValue(r.getPacketSize());
        }
    }

    @Inject(method = "toPacket", at = @At("HEAD"), cancellable = true)
    private void ac$toPacket(PacketByteBuf buf, CallbackInfo ci) {
        ChunkSection r = ac$replacement();
        if (r != null) {
            r.toPacket(buf);
            ci.cancel();
        }
    }
}
