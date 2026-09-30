package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.entity.projectile.ProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Projectiles (arrows, pearls, tridents, wind charges...) can't cross a barrier that blocks them (section 19). */
@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityMixin {
    @Unique
    private double ac$lastX = Double.NaN;
    @Unique
    private double ac$lastY;
    @Unique
    private double ac$lastZ;

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void ac$tick(CallbackInfo ci) {
        ProjectileEntity self = (ProjectileEntity) (Object) this;
        if (!Ac.running() || self.getEntityWorld().isClient() || self.isRemoved()) {
            return;
        }
        String w = Mc.worldId(self.getEntityWorld());
        if (!Double.isNaN(ac$lastX)) {
            for (Barrier b : Ac.get().barriers.list()) {
                if (b.blockProjectiles && b.contains(w, ac$lastX, ac$lastY, ac$lastZ) != b.contains(w, self.getX(), self.getY(), self.getZ())) {
                    self.discard();
                    return;
                }
            }
        }
        ac$lastX = self.getX();
        ac$lastY = self.getY();
        ac$lastZ = self.getZ();
    }
}
