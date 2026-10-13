package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.Ac;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The owner's Damage Boost and Always Crit, on their melee hits. */
@Mixin(LivingEntity.class)
public abstract class OwnerDamageMixin {
    @ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true)
    private float ac$ownerHit(float amount, @Local(argsOnly = true) DamageSource source) {
        if (!Ac.running()) {
            return amount;
        }
        return com.vylorq.anticheat.feature.OwnerPvp.outgoing((LivingEntity) (Object) this, source, amount);
    }
}
