package com.vylorq.anticheat.feature;

import net.minecraft.recipe.RecipeEntry;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's recipes (gems from their ores, armour sets, tools, gear, food, decor) show in everyone's recipe book: they
 * are unlocked for each player when they join. Vanilla only unlocks its own recipes through advancements.
 */
public final class RecipeBook {
    private RecipeBook() {
    }

    public static void unlock(ServerPlayerEntity p) {
        List<RecipeEntry<?>> mine = new ArrayList<>();
        for (RecipeEntry<?> r : p.getEntityWorld().getServer().getRecipeManager().values()) {
            if ("vigil".equals(r.id().getValue().getNamespace())) {
                mine.add(r);
            }
        }
        if (!mine.isEmpty()) {
            p.unlockRecipes(mine);
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> unlock(handler.player));
    }
}
