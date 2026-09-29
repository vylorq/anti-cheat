package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Traders;
import net.minecraft.entity.passive.VillagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lightning never turns a trader into a witch. */
@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {
    @Inject(method = "onStruckByLightning", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$lightning(CallbackInfo ci) {
        if (Ac.running() && Traders.isTrader((VillagerEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
