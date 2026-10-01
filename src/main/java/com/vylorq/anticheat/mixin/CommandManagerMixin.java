package com.vylorq.anticheat.mixin;

import com.mojang.brigadier.ParseResults;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.command.Commands;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs every player command and blocks commands for frozen, jailed and waiting players. */
@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void ac$onExecute(ParseResults<ServerCommandSource> parse, String command, CallbackInfo ci) {
        if (!Ac.running()) {
            return;
        }
        ServerPlayerEntity p = parse.getContext().getSource().getPlayer();
        if (p != null && com.vylorq.anticheat.feature.BuilderMode.is(p)) {
            com.vylorq.anticheat.feature.BuilderLog.event(p, "COMMAND", "/" + command);
        }
        if (p != null && !Commands.allowCommand(p, command)) {
            ci.cancel();
        }
    }
}
