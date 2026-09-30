package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.deaths.DeathLog;
import com.vylorq.anticheat.core.deaths.DeathRecord;
import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.rule.GameRules;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Death logs (section 16). */
public final class Deaths {
    public static final String DROP_TAG = "ac_death";

    private Deaths() {
    }

    public static void onDamage(ServerPlayerEntity p, DamageSource source, float amount) {
        PlayerSession s = Ac.session(p);
        DeathRecord.DamageEntry e = new DeathRecord.DamageEntry();
        e.at = System.currentTimeMillis();
        e.source = source.getName();
        Entity att = source.getAttacker();
        e.attacker = att == null ? null : att.getName().getString();
        e.amount = amount;
        s.recentDamage.addLast(e);
        long cutoff = e.at - 10_000;
        for (Iterator<DeathRecord.DamageEntry> it = s.recentDamage.iterator(); it.hasNext(); ) {
            if (it.next().at < cutoff) {
                it.remove();
            }
        }
    }

    /** Called just before the player dies: record everything and tag the items that will drop. */
    public static void onDeath(ServerPlayerEntity p, DamageSource source) {
        Ac ac = Ac.get();
        PlayerSession s = Ac.session(p);
        DeathRecord r = new DeathRecord();
        r.player = p.getUuid();
        r.playerName = p.getGameProfile().name();
        r.at = System.currentTimeMillis();
        r.world = Mc.worldId(p.getEntityWorld());
        r.pos = Mc.vec(p.getEntityPos());
        r.biome = p.getEntityWorld().getBiome(p.getBlockPos()).getKey().map(k -> k.getValue().toString()).orElse("?");
        r.cause = source.getName();
        Entity attacker = source.getAttacker();
        if (attacker != null) {
            r.killer = attacker.getName().getString();
            r.killerUuid = attacker.getUuid();
            r.killerDistance = attacker.distanceTo(p);
            if (attacker instanceof ServerPlayerEntity k) {
                ItemInfo weapon = ItemConv.info(k.getMainHandStack());
                r.weapon = k.getMainHandStack().isEmpty() ? "fist" : weapon.describe();
            }
        }
        r.fallHeight = p.fallDistance;
        long cutoff = r.at - 10_000;
        for (DeathRecord.DamageEntry e : s.recentDamage) {
            if (e.at >= cutoff) {
                r.lastDamage.add(e);
            }
        }
        s.recentDamage.clear();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty()) {
                ItemInfo info = ItemConv.info(st);
                info.slot = i;
                info.serialized = ItemConv.encode(st);
                r.inventory.add(info);
            }
        }
        r.xpLevel = p.experienceLevel;
        r.xpProgress = p.experienceProgress;
        r.totalXp = p.totalExperience;
        ac.deaths.add(r);
        boolean keep = p.getEntityWorld().getGameRules().getValue(GameRules.KEEP_INVENTORY);
        if (!keep) {
            // Tag what will drop so we can see who picks it up.
            for (int i = 0; i < inv.size(); i++) {
                ItemStack st = inv.getStack(i);
                if (!st.isEmpty()) {
                    ItemConv.setTag(st, DROP_TAG, r.dropTag);
                }
            }
        }
        Ac.markDirty("deaths");
        ac.logs.activity(r.at, p.getUuid(), "death", r.cause + (r.killer != null ? " by " + r.killer : "") + " at " + r.pos.formatExact());
    }

    /** Items that didn't drop (keep inventory, soulbound mods) must not keep the tag. */
    public static void afterRespawn(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && ItemConv.tag(st, DROP_TAG) != null) {
                ItemConv.removeTag(st, DROP_TAG);
            }
        }
    }

    /** An item entity is being picked up. Strips the death tag and records who took it. */
    public static void onPickup(ServerPlayerEntity p, ItemStack stack) {
        String tag = ItemConv.tag(stack, DROP_TAG);
        if (tag == null) {
            return;
        }
        ItemConv.removeTag(stack, DROP_TAG);
        Ac.get().deaths.recordPickup(tag, p.getUuid(), p.getGameProfile().name(), ItemConv.info(stack).describe());
        Ac.markDirty("deaths");
    }

    /** Restore a death's items once (section 16). */
    public static void restore(ServerPlayerEntity admin, long deathId) {
        Ac ac = Ac.get();
        DeathRecord r = ac.deaths.get(deathId);
        if (r == null) {
            Msg.send(admin, "deaths.not-found");
            return;
        }
        ServerPlayerEntity target = ac.server.getPlayerManager().getPlayer(r.player);
        if (target == null) {
            Msg.send(admin, "deaths.must-be-online", r.playerName);
            return;
        }
        int needed = r.inventory.size();
        int free = 0;
        for (int i = 0; i < target.getInventory().size(); i++) {
            if (target.getInventory().getStack(i).isEmpty()) {
                free++;
            }
        }
        if (free < needed) {
            Msg.send(admin, "deaths.no-room", r.playerName, needed - free);
            return;
        }
        DeathLog.RestoreResult res = ac.deaths.markRestored(deathId, admin.getGameProfile().name());
        switch (res) {
            case ALREADY_RESTORED -> {
                Msg.send(admin, "deaths.already");
                return;
            }
            case ITEMS_RECOVERED -> {
                Msg.send(admin, "deaths.recovered");
                return;
            }
            case NOT_FOUND -> {
                Msg.send(admin, "deaths.not-found");
                return;
            }
            default -> {
            }
        }
        List<ItemStack> given = new ArrayList<>();
        var inv = target.getInventory();
        for (ItemInfo i : r.inventory) {
            ItemStack st = ItemConv.decode(i.serialized);
            ItemConv.removeTag(st, DROP_TAG);
            if (i.slot >= 0 && i.slot < inv.size() && inv.getStack(i.slot).isEmpty()) {
                inv.setStack(i.slot, st);
            } else {
                inv.insertStack(st);
            }
            given.add(st);
        }
        target.addExperience(r.totalXp);
        Dupes.legit(target, given);
        Ac.markDirty("deaths");
        Staff.log(admin, "death-restore", r.player, r.playerName, "death #" + r.id + ", " + given.size() + " stacks");
        Msg.send(admin, "deaths.restored", r.playerName);
        Msg.send(target, "deaths.restored-you");
    }

    public static String summary(DeathRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.cause);
        if (r.killer != null) {
            sb.append(" by ").append(r.killer);
            if (r.weapon != null) {
                sb.append(" with ").append(r.weapon);
            }
            sb.append(String.format(" (%.1f blocks)", r.killerDistance));
        }
        if (r.cause.contains("fall")) {
            sb.append(String.format(" from %.1f blocks", r.fallHeight));
        }
        return sb.toString();
    }
}
