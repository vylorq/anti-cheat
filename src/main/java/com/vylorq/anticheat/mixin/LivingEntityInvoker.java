package com.vylorq.anticheat.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the game tests pop a real totem of undying. */
@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {
    @Invoker("tryUseDeathProtector")
    boolean ac$tryUseDeathProtector(DamageSource source);
}
