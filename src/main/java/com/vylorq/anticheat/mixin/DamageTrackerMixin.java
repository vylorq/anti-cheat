package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.vylorq.anticheat.Ac;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageTracker;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The Boiled One's kills get their own death message (in chat and on the death screen). */
@Mixin(DamageTracker.class)
public abstract class DamageTrackerMixin {
    @Shadow
    @Final
    private LivingEntity entity;

    @ModifyReturnValue(method = "getDeathMessage", at = @At("RETURN"))
    private Text ac$boiledDeath(Text original) {
        if (!Ac.running()) {
            return original;
        }
        Text own = com.vylorq.anticheat.feature.BoiledHaunts.deathMessage(entity);
        return own != null ? own : original;
    }
}
