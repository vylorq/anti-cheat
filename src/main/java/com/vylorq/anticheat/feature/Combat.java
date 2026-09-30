package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.combat.ClickAnalyzer;
import com.vylorq.anticheat.core.combat.CombatTracker;
import com.vylorq.anticheat.core.combat.ReachCheck;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Tps;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Locale;

/** Combat checks (section 8) and velocity tracking for the movement safeguards. */
public final class Combat {
    private Combat() {
    }

    private static void flag(ServerPlayerEntity p, CheckType c, double pts, String detail) {
        Ac.get().engine.flag(p.getUuid(), p.getGameProfile().name(), c, pts, detail, Ac.session(p).bedrock);
    }

    /**
     * Called from the attack callback, on the server thread, before the hit is applied.
     *
     * @return false to cancel the hit (only for impossible hits: through walls or far beyond reach)
     */
    public static boolean onAttack(ServerPlayerEntity p, Entity target) {
        Ac ac = Ac.get();
        AcConfig cfg = Ac.config();
        PlayerSession s = Ac.session(p);
        long now = System.currentTimeMillis();
        Vec3d eyeD = p.getEyePos();
        Vec3 eye = Mc.vec(eyeD);
        Vec3 look = Mc.vec(p.getRotationVec(1.0f));
        Vec3 targetCenter = Mc.vec(target.getBoundingBox().getCenter());
        double dist = eye.distance(targetCenter);
        double angle = CombatTracker.angleTo(eye, look, targetCenter);
        String targetName = target.getName().getString();

        ac.evidence.record(p.getUuid(), EvidenceEvent.Type.HIT, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(),
                String.format(Locale.ROOT, "hit %s from %.1f blocks, angle %.0f°", targetName, dist, angle));

        if (!cfg.combat.enabled || p.isCreative() || p.isSpectator() || Tps.tps() < cfg.general.lagTpsThreshold) {
            return true;
        }
        boolean allow = true;

        // Several attacks in one tick.
        long tick = Tps.tick();
        if (s.lastAttackTick == tick) {
            s.attacksThisTick++;
            if (s.attacksThisTick > 2) {
                flag(p, CheckType.ATTACK_COOLDOWN, 0.5, s.attacksThisTick + " attacks in one tick");
            }
        } else {
            s.lastAttackTick = tick;
            s.attacksThisTick = 1;
        }

        // Invalid actions: attacking with a container open, while eating/drinking or blocking.
        String invalid = CombatTracker.invalidAction(p.currentScreenHandler != p.playerScreenHandler,
                p.isUsingItem() && !p.getActiveItem().isOf(Items.SHIELD), p.isBlocking());
        if (invalid != null) {
            flag(p, CheckType.INVALID_ACTION, 1.0, invalid);
        }

        // Reach with lag compensation.
        double reach;
        PlayerSession ts = target instanceof ServerPlayerEntity tp ? Ac.sessionOrNull(tp.getUuid()) : null;
        if (ts != null && !ts.history.isEmpty()) {
            reach = ReachCheck.compensatedDistance(eye, ts.history, now, p.networkHandler.getLatency());
        } else {
            reach = com.vylorq.anticheat.core.util.Box.around(Mc.vec(target.getPos()), target.getWidth(), target.getHeight())
                    .expand(0.1 + (target.getVelocity().horizontalLength() * 3)).distanceTo(eye);
        }
        double tol = s.bedrock ? cfg.combat.bedrockReachTolerance : cfg.combat.reachTolerance;
        double max = cfg.combat.maxReach + tol;
        if (reach > max) {
            flag(p, CheckType.REACH, 1.0 + Math.min(2, (reach - max) * 2), String.format(Locale.ROOT, "%.2f blocks", reach));
            if (reach > max + 1.5) {
                allow = false;
            }
        }

        // Hits through walls: line of sight from the eyes to any point of the target's hitbox.
        if (cfg.combat.wallHitCheck && !canSee(p, target)) {
            flag(p, CheckType.WALL_HIT, 1.5, "no line of sight to " + targetName);
            allow = false;
        }

        // Multi-target.
        double spread = s.combat.onHitMultiTarget(now, target.getUuid(), eye, Mc.vec(target.getPos()), cfg.combat.multiTargetWindowMs);
        if (spread > cfg.combat.multiTargetAngle) {
            flag(p, CheckType.MULTI_TARGET, 1.5, String.format(Locale.ROOT, "targets %.0f° apart", spread));
        }

        // Aim analysis.
        if (cfg.combat.aimCheck) {
            boolean switched = s.combat.switchedTarget(target.getUuid());
            double aim = s.combat.aim.onHit(s.rotationThisTick, angle, switched);
            if (aim >= 0.5) {
                flag(p, CheckType.AIM, aim * 1.5, "robotic aim " + String.format(Locale.ROOT, "%.2f", aim));
            }
            double gcd = s.combat.aim.sensitivityScore();
            if (gcd > 0 && s.combat.aim.hits() % 20 == 0) {
                flag(p, CheckType.AIM, gcd, "rotation has no mouse step");
            }
        }
        return allow;
    }

    /** Arm swings (every left click): autoclicker analysis. */
    public static void onSwing(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        if (ac == null || p.isCreative() || p.isSpectator()) {
            return;
        }
        AcConfig cfg = Ac.config();
        PlayerSession s = Ac.session(p);
        long now = System.currentTimeMillis();
        ac.evidence.record(p.getUuid(), EvidenceEvent.Type.CLICK, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), "click");
        if (!cfg.combat.enabled || Tps.tps() < cfg.general.lagTpsThreshold) {
            return;
        }
        // Mining and building make the client swing continuously: only analyse clicks that aren't aimed at a block.
        if (p.raycast(5.0, 1.0f, false).getType() == HitResult.Type.BLOCK) {
            return;
        }
        ClickAnalyzer.Settings st = s.bedrock
                ? new ClickAnalyzer.Settings(cfg.combat.bedrockMaxCps, cfg.combat.bedrockMinClickCv, cfg.combat.autoclickerStrictness)
                : new ClickAnalyzer.Settings(cfg.combat.maxCps, cfg.combat.minClickCv, cfg.combat.autoclickerStrictness);
        List<ClickAnalyzer.Finding> f = s.combat.clicks.onClick(now, Tps.tick(), st);
        for (ClickAnalyzer.Finding x : f) {
            flag(p, CheckType.AUTOCLICKER, x.points(), x.reason());
        }
    }

    /** Server-side line of sight to the target's hitbox (centre, eyes, feet and corners). */
    public static boolean canSee(ServerPlayerEntity p, Entity target) {
        Vec3d eye = p.getEyePos();
        net.minecraft.util.math.Box b = target.getBoundingBox().expand(0.1);
        Vec3d[] points = {
                b.getCenter(),
                new Vec3d(b.getCenter().x, b.maxY - 0.05, b.getCenter().z),
                new Vec3d(b.getCenter().x, b.minY + 0.05, b.getCenter().z),
                new Vec3d(b.minX, b.getCenter().y, b.minZ), new Vec3d(b.maxX, b.getCenter().y, b.maxZ),
                new Vec3d(b.minX, b.getCenter().y, b.maxZ), new Vec3d(b.maxX, b.getCenter().y, b.minZ),
                new Vec3d(b.minX, b.maxY, b.minZ), new Vec3d(b.maxX, b.maxY, b.maxZ),
        };
        for (Vec3d pt : points) {
            BlockHitResult r = p.getWorld().raycast(new RaycastContext(eye, pt, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, p));
            if (r.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    /** The server sent this player a velocity (knockback, explosion, wind charge, breeze, fishing rod...). */
    public static void onVelocity(ServerPlayerEntity p, double magnitude) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s != null) {
            s.lastVelocity = Math.max(magnitude, s.ticksSinceVelocity < 5 ? s.lastVelocity : 0);
            s.ticksSinceVelocity = 0;
        }
    }

    /** The server teleported this player (any teleport, including other mods'). */
    public static void onTeleportPacket(ServerPlayerEntity p) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s != null) {
            s.teleported();
        }
    }

    public static boolean isShadowAttack(Entity attacker, LivingEntity victim) {
        return attacker instanceof ServerPlayerEntity sp && victim instanceof ServerPlayerEntity
                && Ac.get().shadow.isShadowed(sp.getUuid());
    }
}
