package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Traders;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Traders can't be pushed; bosses take scaled-down hits. */
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

    /** Bosses have more health than the game allows a mob, so each hit counts for less instead. */
    @ModifyReturnValue(method = "modifyAppliedDamage", at = @At("RETURN"))
    private float ac$bossDamage(float original,
                                @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.entity.damage.DamageSource source) {
        if (!Ac.running()) {
            return original;
        }
        if (com.vylorq.anticheat.feature.OwnerCombat.dealing()) {
            return original; // the owner's weapons hit bosses for full damage
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (original > 0 && com.vylorq.anticheat.feature.SecretItems.weaponOf(source) != null) {
            if (com.vylorq.anticheat.feature.Bosses.isBoss(self)) {
                // 20-40 off its shown health a hit, whatever its armour
                return com.vylorq.anticheat.feature.SecretItems.bossHit(source) / com.vylorq.anticheat.feature.Bosses.damageScale(self);
            }
            if (self instanceof net.minecraft.entity.player.PlayerEntity) {
                return Math.min(original, com.vylorq.anticheat.feature.SecretItems.PLAYER_CAP); // never OP on players
            }
        }
        return original / com.vylorq.anticheat.feature.Bosses.damageScale((LivingEntity) (Object) this);
    }

    @ModifyReturnValue(method = "isPushable", at = @At("RETURN"), require = 0)
    private boolean ac$pushable(boolean original) {
        return original && !(Ac.running() && Traders.isTrader((LivingEntity) (Object) this));
    }
}
