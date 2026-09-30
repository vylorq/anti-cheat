package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.feature.Watcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerMetadata;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Watcher's occasional server list message (33.5): swaps the description while it's active. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "getServerMetadata", at = @At("RETURN"), cancellable = true)
    private void ac$watcherMotd(CallbackInfoReturnable<ServerMetadata> cir) {
        String text = Watcher.serverListText();
        ServerMetadata m = cir.getReturnValue();
        if (text != null && m != null) {
            cir.setReturnValue(new ServerMetadata(Text.literal(text), m.players(), m.version(), m.favicon(), m.secureChatEnforced()));
        }
    }
}
