package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Traders;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Traders can't be pushed. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyReturnValue(method = "isPushable", at = @At("RETURN"), require = 0)
    private boolean ac$pushable(boolean original) {
        return original && !(Ac.running() && Traders.isTrader((LivingEntity) (Object) this));
    }
}
