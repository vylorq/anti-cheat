package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.redstone.LagMachineDetector;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Caps TNT and falling block speed (railguns, cannons). */
@Mixin({TntEntity.class, FallingBlockEntity.class})
public abstract class TntEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void ac$cap(CallbackInfo ci) {
        if (!Ac.running()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        if (self.getWorld().isClient()) {
            return;
        }
        Vec3d v = self.getVelocity();
        double max = Ac.config().redstone.maxTntVelocity;
        if (v.lengthSquared() > max * max) {
            double[] c = LagMachineDetector.capVelocity(v.x, v.y, v.z, max);
            self.setVelocity(c[0], c[1], c[2]);
        }
    }
}
