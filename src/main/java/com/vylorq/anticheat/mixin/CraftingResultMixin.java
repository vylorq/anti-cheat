package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.ContentToggles;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Things the owner turned off can't be crafted (the result slot stays empty). */
@Mixin(CraftingScreenHandler.class)
public abstract class CraftingResultMixin {
    @Inject(method = "updateResult", at = @At("TAIL"))
    private static void ac$turnedOff(CallbackInfo ci, @Local(argsOnly = true) ScreenHandler handler,
                                     @Local(argsOnly = true) PlayerEntity player, @Local(argsOnly = true) CraftingResultInventory result) {
        if (!Ac.running() || !(player instanceof ServerPlayerEntity p)) {
            return;
        }
        ItemStack out = result.getStack(0);
        if (!out.isEmpty() && !ContentToggles.allowed(out)) {
            result.setStack(0, ItemStack.EMPTY);
            handler.setReceivedStack(0, ItemStack.EMPTY);
            p.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(handler.syncId, handler.nextRevision(), 0, ItemStack.EMPTY));
        }
    }
}
