package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.PlayerSnapshot;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

/** Saves and restores a player's full state (arenas, spectating). Restores exactly once. */
public final class PlayerState {
    private PlayerState() {
    }

    public static PlayerSnapshot capture(ServerPlayerEntity p, String reason) {
        PlayerSnapshot s = new PlayerSnapshot();
        s.player = p.getUuid();
        s.reason = reason;
        s.createdAt = System.currentTimeMillis();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty()) {
                s.inventory.add(ItemConv.encodeSlot(i, st));
            }
        }
        s.health = p.getHealth();
        s.food = p.getHungerManager().getFoodLevel();
        s.saturation = p.getHungerManager().getSaturationLevel();
        s.xpLevel = p.experienceLevel;
        s.xpProgress = p.experienceProgress;
        s.totalXp = p.totalExperience;
        for (StatusEffectInstance e : p.getStatusEffects()) {
            NbtElement n = e.writeNbt();
            s.effects.add(n.asString());
        }
        s.location = Mc.location(p);
        s.gameMode = p.interactionManager.getGameMode().asString();
        s.fireTicks = p.getFireTicks();
        s.air = p.getAir();
        return s;
    }

    /** Puts a snapshot back. Inventory is replaced exactly (nothing is merged, nothing dropped). */
    public static void apply(ServerPlayerEntity p, PlayerSnapshot s, boolean teleport) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        for (String e : s.inventory) {
            int slot = ItemConv.slotOf(e);
            if (slot >= 0 && slot < inv.size()) {
                inv.setStack(slot, ItemConv.decodeSlot(e));
            }
        }
        inv.markDirty();
        p.clearStatusEffects();
        for (String e : s.effects) {
            try {
                NbtCompound n = StringNbtReader.parse(e);
                StatusEffectInstance inst = StatusEffectInstance.fromNbt(n);
                if (inst != null) {
                    p.addStatusEffect(inst);
                }
            } catch (Exception ignored) {
                // skip broken effect
            }
        }
        p.setHealth(Math.max(1f, Math.min(p.getMaxHealth(), s.health)));
        p.getHungerManager().setFoodLevel(s.food);
        p.getHungerManager().setSaturationLevel(s.saturation);
        p.setExperienceLevel(s.xpLevel);
        p.experienceProgress = s.xpProgress;
        p.totalExperience = s.totalXp;
        p.setFireTicks(0);
        p.setAir(s.air);
        p.fallDistance = 0;
        GameMode gm = GameMode.byName(s.gameMode, GameMode.SURVIVAL);
        p.changeGameMode(gm);
        if (teleport && s.location != null) {
            Mc.teleport(p, Ac.server(), s.location);
        }
        p.currentScreenHandler.sendContentUpdates();
    }

    /** Empties inventory, effects, fire; full health and hunger. */
    public static void reset(ServerPlayerEntity p) {
        p.getInventory().clear();
        p.clearStatusEffects();
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);
        p.getHungerManager().setSaturationLevel(20f);
        p.setFireTicks(0);
        p.fallDistance = 0;
        p.changeGameMode(GameMode.SURVIVAL);
    }
}
