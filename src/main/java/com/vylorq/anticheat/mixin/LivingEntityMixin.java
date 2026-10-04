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
    /** A totem of undying (or anything else that protects from death) just saved this entity. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "tryUseDeathProtector", at = @At("RETURN"))
    private void ac$totemPopped(net.minecraft.entity.damage.DamageSource source,
                                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && Ac.running() && (Object) this instanceof net.minecraft.server.network.ServerPlayerEntity p) {
            com.vylorq.anticheat.feature.AutoTotem.popped(p);
        }
    }

    @ModifyReturnValue(method = "isPushable", at = @At("RETURN"), require = 0)
    private boolean ac$pushable(boolean original) {
        return original && !(Ac.running() && Traders.isTrader((LivingEntity) (Object) this));
    }
}
