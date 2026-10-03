package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.BuilderMode;
import com.vylorq.anticheat.feature.Deaths;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item pickups: records who picks up items dropped at a death (section 16) and marks item types as obtained
 * naturally, which is what lets traders sell them (section 23.5).
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow
    public abstract ItemStack getStack();

    @Shadow
    public abstract Entity getOwner();

    /** Builders can't pass items to anyone, or pick up anything that isn't a building block. */
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void ac$builderItems(PlayerEntity player, CallbackInfo ci) {
        if (!Ac.running() || !(player instanceof ServerPlayerEntity p)) {
            return;
        }
        if (getOwner() instanceof ServerPlayerEntity thrower && BuilderMode.is(thrower)) {
            ci.cancel();
            ((Entity) (Object) this).discard();
        } else if (BuilderMode.is(p) && !BuilderMode.allowed(getStack())) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private int ac$countBefore = -1;
    @org.spongepowered.asm.mixin.Unique
    private ItemStack ac$stackBefore = ItemStack.EMPTY;

    /** Items picked up from the ground (mined drops, mob loot, your own death pile) are real gains, not a dupe. */
    @Inject(method = "onPlayerCollision", at = @At("TAIL"))
    private void ac$pickedUp(PlayerEntity player, CallbackInfo ci) {
        int before = ac$countBefore;
        ac$countBefore = -1;
        if (before <= 0 || !Ac.running() || !(player instanceof ServerPlayerEntity p)) {
            return;
        }
        ItemStack was = ac$stackBefore;
        ac$stackBefore = ItemStack.EMPTY;
        int taken = before - (((Entity) (Object) this).isRemoved() ? 0 : getStack().getCount());
        if (taken > 0 && !was.isEmpty()) {
            com.vylorq.anticheat.feature.Dupes.legit(p, java.util.List.of(was.copyWithCount(taken)));
            com.vylorq.anticheat.feature.PacketChecks.watchedItem(p, "picked up", was.copyWithCount(taken));
        }
    }

    @Inject(method = "onPlayerCollision", at = @At("HEAD"))
    private void ac$pickup(PlayerEntity player, CallbackInfo ci) {
        ac$countBefore = -1;
        if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || p.isCreative() || p.isSpectator()) {
            return;
        }
        ItemStack stack = getStack();
        if (stack.isEmpty()) {
            return;
        }
        Deaths.onPickup(p, stack);
        ac$countBefore = stack.getCount();
        ac$stackBefore = stack.copy();
        Entity thrower = getOwner();
        boolean fromStaffOrCreative = thrower instanceof ServerPlayerEntity t && (t.isCreative() || Perms.isStaff(t));
        if (!fromStaffOrCreative && !Ac.get().economy.isObtained(Mc.itemId(stack.getItem()))) {
            Ac.get().economy.markObtained(Mc.itemId(stack.getItem()));
            Ac.markDirty("economy");
        }
    }
}
