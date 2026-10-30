package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Team base upgrades, bought from the team bank (/team upgrade):
 * Shield - tier 1: no monsters appear on the team's land; tier 2: strangers on it glow and are weakened.
 * Buff - tier 1: members on their own land get Regeneration and Haste; tier 2: Speed and Resistance too.
 * Vault - each tier adds a row to the team vault (up to six rows in all).
 */
public final class TeamUpgrades {
    private TeamUpgrades() {
    }

    public record Upgrade(String id, int[] prices) {
        int max() {
            return prices.length;
        }
    }

    public static final Map<String, Upgrade> ALL = new LinkedHashMap<>();

    static {
        ALL.put("shield", new Upgrade("shield", new int[]{128, 256}));
        ALL.put("buff", new Upgrade("buff", new int[]{96, 192}));
        ALL.put("vault", new Upgrade("vault", new int[]{64, 128, 192}));
    }

    public static int tier(Team t, String what) {
        return t == null || t.upgrades == null ? 0 : t.upgrades.getOrDefault(what, 0);
    }

    /** The price of the next tier, or -1 at the top. */
    public static int nextPrice(Team t, String what) {
        Upgrade u = ALL.get(what);
        int tier = tier(t, what);
        return u == null || tier >= u.max() ? -1 : u.prices()[tier];
    }

    public static void list(ServerPlayerEntity p, Team t) {
        Msg.send(p, "team.up.header", Markets.money((int) Math.min(Integer.MAX_VALUE, t.bank)));
        for (Upgrade u : ALL.values()) {
            int tier = tier(t, u.id());
            int price = nextPrice(t, u.id());
            Msg.send(p, "team.up.line", Msg.trFor(p, "team.up." + u.id()), tier, u.max(),
                    price < 0 ? Msg.trFor(p, "team.up.maxed") : Markets.money(price), Msg.trFor(p, "team.up." + u.id() + ".desc"));
        }
        Msg.send(p, "team.up.how");
    }

    private static void effect(ServerPlayerEntity p, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect> e, int amp) {
        p.addStatusEffect(new StatusEffectInstance(e, 80, amp, true, false, true));
    }

    public static void tick(long ticks) {
        if (ticks % 40 != 0 || !Teams.enabled()) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Team land = Teams.at(p.getEntityWorld(), p.getBlockPos());
            if (land == null) {
                continue;
            }
            UUID id = p.getUuid();
            if (land.members.contains(id)) {
                int buff = tier(land, "buff");
                if (buff >= 1) {
                    effect(p, StatusEffects.REGENERATION, 0);
                    effect(p, StatusEffects.HASTE, 0);
                }
                if (buff >= 2) {
                    effect(p, StatusEffects.SPEED, 0);
                    effect(p, StatusEffects.RESISTANCE, 0);
                }
            } else if (tier(land, "shield") >= 2 && !Perms.isOwner(id)) {
                Team mine = Teams.tm().teamOf(id);
                boolean friend = mine != null && land.allies.contains(mine.id);
                if (!friend) {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 80, 0, true, false, true));
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80, 0, true, false, true));
                }
            }
        }
        // Shield: monsters that wandered in or appeared on shielded land go away (named or kept ones stay).
        if (ticks % 100 == 0) {
            for (ServerWorld w : Ac.server().getWorlds()) {
                List<Entity> gone = new ArrayList<>();
                for (Entity e : w.iterateEntities()) {
                    if (e instanceof HostileEntity h && !h.isPersistent() && !h.hasCustomName()) {
                        Team land = Teams.at(w, h.getBlockPos());
                        if (land != null && tier(land, "shield") >= 1) {
                            gone.add(h);
                        }
                    }
                }
                gone.forEach(Entity::discard);
            }
        }
    }
}
