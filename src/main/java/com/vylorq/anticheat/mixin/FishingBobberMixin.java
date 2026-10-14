package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.util.ItemConv;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** A Void-line grappling hook (upgraded with voidstone) keeps its line out to 64 blocks instead of 32. */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberMixin {
    @ModifyConstant(method = "removeIfInvalid", constant = @Constant(doubleValue = 1024.0))
    private double vigil$longLine(double max, PlayerEntity player) {
        for (var s : java.util.List.of(player.getMainHandStack(), player.getOffHandStack())) {
            if ("1".equals(ItemConv.tag(s, "vigil_long_hook"))) {
                return 4096.0;
            }
        }
        return max;
    }
}
