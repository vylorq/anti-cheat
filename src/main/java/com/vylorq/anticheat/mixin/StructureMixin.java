package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.ContentToggles;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The owner's structure switches: a structure turned off doesn't generate in new chunks (built ones stay). */
@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "createStructureStart", at = @At("HEAD"), cancellable = true)
    private void vigil$structureOff(CallbackInfoReturnable<StructureStart> cir) {
        if (!Ac.running() || Ac.server() == null) {
            return;
        }
        var server = Ac.server();
        Identifier id = server.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE).getId((Structure) (Object) this);
        if (id != null && "vigil".equals(id.getNamespace()) && !ContentToggles.structureOn(id.getPath())) {
            cir.setReturnValue(StructureStart.DEFAULT);
        }
    }
}
