package com.vylorq.anticheat.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Skin layer data id, used to show every skin layer on the Watcher's fake figure. */
@Mixin(PlayerEntity.class)
public interface PlayerEntityAccessor {
    @Accessor("PLAYER_MODE_CUSTOMIZATION_ID")
    static TrackedData<Byte> ac$modelParts() {
        throw new AssertionError();
    }
}
