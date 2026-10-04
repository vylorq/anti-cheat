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
        // Very high ping: what the server sees is too far behind what the player saw to judge fairly.
        boolean laggy = p.networkHandler.getLatency() > 600;

        // Several attacks in one tick. Packets that were held up by lag arrive together, and Bedrock sends its
        // input in batches, so only an absurd number counts.
        long tick = Tps.tick();
        if (s.lastAttackTick == tick) {
            s.attacksThisTick++;
            if (s.attacksThisTick == 5 && !s.bedrock && !laggy) {
                flag(p, CheckType.ATTACK_COOLDOWN, 0.5, s.attacksThisTick + " attacks in one tick");
            }
        } else {
            s.lastAttackTick = tick;
            s.attacksThisTick = 1;
        }

        // Invalid actions: attacking with a container open, while eating/drinking or blocking.
        // Only when it has clearly been that way for a while: a menu the server just opened, or eating that just
        // started or stopped, can cross with a click on the way.
        String invalid = CombatTracker.invalidAction(p.currentScreenHandler != p.playerScreenHandler && s.ticksSinceScreenChange > 20,
                p.isUsingItem() && !p.getActiveItem().isOf(Items.SHIELD) && p.getItemUseTime() > 10, p.isBlocking() && p.getItemUseTime() > 10);
        if (invalid != null && !s.bedrock) {
            flag(p, CheckType.INVALID_ACTION, 1.0, invalid);
        }

        // Reach with lag compensation.
        double reach;
        PlayerSession ts = target instanceof ServerPlayerEntity tp ? Ac.sessionOrNull(tp.getUuid()) : null;
        if (ts != null && !ts.history.isEmpty()) {
            reach = ReachCheck.compensatedDistance(eye, ts.history, now, p.networkHandler.getLatency());
        } else {
            reach = com.vylorq.anticheat.core.util.Box.around(Mc.vec(target.getEntityPos()), target.getWidth(), target.getHeight())
                    .expand(0.1 + (target.getVelocity().horizontalLength() * 3)).distanceTo(eye);
        }
        double tol = s.bedrock ? cfg.combat.bedrockReachTolerance : cfg.combat.reachTolerance;
        // Creative mode and attribute changes give more reach; never check against less than what the game allows.
        double max = Math.max(cfg.combat.maxReach, p.getEntityInteractionRange()) + tol;
        if (reach > max && !laggy) {
            flag(p, CheckType.REACH, 1.0 + Math.min(2, (reach - max) * 2), String.format(Locale.ROOT, "%.2f blocks", reach));
            if (reach > max + 1.5) {
                allow = false;
            }
        }

        // Hits through walls: line of sight from the eyes to any point of the target's hitbox.
        if (cfg.combat.wallHitCheck && !laggy && !canSee(p, target, ts, now)) {
            flag(p, CheckType.WALL_HIT, 1.5, "no line of sight to " + targetName);
            allow = false;
        }

        // Multi-target.
        double spread = s.combat.onHitMultiTarget(now, target.getUuid(), eye, Mc.vec(target.getEntityPos()), cfg.combat.multiTargetWindowMs);
        // Bedrock touch controls attack whatever is tapped on screen, wherever the player is looking.
        if (spread > cfg.combat.multiTargetAngle && !s.bedrock && !laggy) {
            flag(p, CheckType.MULTI_TARGET, 1.5, String.format(Locale.ROOT, "targets %.0f° apart", spread));
        }

        // Aim analysis.
        // Look check: was the target anywhere near where they were looking? Judged with the next rotation packet,
        // which carries the rotation the game had when it clicked (the server's copy is a tick old at this point).
        if (!laggy) {
            double size = Math.max(target.getWidth(), target.getHeight()) * 0.75 + 0.1;
            s.lookTarget = targetCenter;
            s.lookTargetRadius = Math.toDegrees(Math.atan2(size, Math.max(0.5, dist)));
            s.lookYawAtHit = p.getYaw();
            s.lookPitchAtHit = p.getPitch();
        }

        // Criticals: the hit counts as critical, but the "fall" was a tiny hop right above the ground.
        if (!s.bedrock && !laggy && target instanceof LivingEntity && p.getAttackCooldownProgress(0.5f) > 0.9f && p.fallDistance > 0
                && !p.isOnGround() && !p.isClimbing() && !p.isTouchingWater() && !p.hasVehicle() && !p.isSprinting()
                && !p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)) {
            boolean groundClose = !p.getEntityWorld().isSpaceEmpty(p, p.getBoundingBox().offset(0, -0.25, 0));
            if (s.crits.onCrit(p.fallDistance, groundClose)) {
                flag(p, CheckType.CRITICALS, 1.5, "critical hits without a real fall (4 of the last 6)");
            }
        }

        // Bedrock (touch, controller) and Java controller mods don't turn like a mouse, so aim is judged for Java only.
        if (cfg.combat.aimCheck && !s.bedrock) {
            boolean switched = s.combat.switchedTarget(target.getUuid());
            double aim = s.combat.aim.onHit(s.rotationThisTick, angle, switched);
            if (aim >= 0.5) {
                flag(p, CheckType.AIM, aim * 1.5, "robotic aim " + String.format(Locale.ROOT, "%.2f", aim));
            }
            // Zoom mods and cinematic camera also lose the mouse step, so this only adds to other aim signs.
            double gcd = s.combat.aim.sensitivityScore();
            if (gcd > 0 && aim >= 0.3 && s.combat.aim.hits() % 20 == 0) {
                flag(p, CheckType.AIM, gcd, "rotation has no mouse step");
            }
        }
        return allow;
    }

    /** Arm swings (every left click): autoclicker analysis. */
    public static void onSwing(ServerPlayerEntity p) {
        onSwing(p, System.currentTimeMillis());
    }

    /** @param clickTime when the swing reached the server's network thread (wall clock) */
    public static void onSwing(ServerPlayerEntity p, long clickTime) {
        Ac ac = Ac.get();
        if (ac == null || p.isCreative() || p.isSpectator()) {
            return;
        }
        AcConfig cfg = Ac.config();
        PlayerSession s = Ac.session(p);
        long now = clickTime;
        ac.evidence.record(p.getUuid(), EvidenceEvent.Type.CLICK, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), "click");
        if (!cfg.combat.enabled || Tps.tps() < cfg.general.lagTpsThreshold) {
            return;
        }
        // Mining and building make the client swing continuously: only analyse clicks that aren't aimed at a block.
        if (p.raycast(5.0, 1.0f, false).getType() == HitResult.Type.BLOCK) {
            return;
        }
        // Bedrock input reaches the server in batches, so its click timing can't be judged: only the click rate.
        ClickAnalyzer.Settings st = s.bedrock
                ? new ClickAnalyzer.Settings(cfg.combat.bedrockMaxCps, cfg.combat.bedrockMinClickCv, cfg.combat.autoclickerStrictness, false)
                : new ClickAnalyzer.Settings(cfg.combat.maxCps, cfg.combat.minClickCv, cfg.combat.autoclickerStrictness, true);
        List<ClickAnalyzer.Finding> f = s.combat.clicks.onClick(now, Tps.tick(), st);
        for (ClickAnalyzer.Finding x : f) {
            flag(p, CheckType.AUTOCLICKER, x.points(), x.reason());
        }
    }

    /** Server-side line of sight to the target's hitbox (centre, eyes, feet and corners). */
    public static boolean canSee(ServerPlayerEntity p, Entity target) {
        return canSee(p, target, null, System.currentTimeMillis());
    }

    /**
     * Also tries where the target was a moment ago (what a lagging attacker saw). A point counts as hidden only
     * when both the block's collision box and its outline are in the way, so hitting over fences and walls (tall
     * collision, low outline) or through grass and flowers is fine.
     */
    public static boolean canSee(ServerPlayerEntity p, Entity target, PlayerSession ts, long now) {
        if (canSeeBox(p, target.getBoundingBox().expand(0.1 + target.getVelocity().horizontalLength() * 2))) {
            return true;
        }
        if (ts != null) {
            long back = p.networkHandler.getLatency() + 150L;
            for (var sample : ts.history.between(now - back, now)) {
                var b = sample.box();
                if (canSeeBox(p, new net.minecraft.util.math.Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).expand(0.1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean blocked(ServerPlayerEntity p, Vec3d from, Vec3d to, RaycastContext.ShapeType type) {
        return p.getEntityWorld().raycast(new RaycastContext(from, to, type, RaycastContext.FluidHandling.NONE, p)).getType()
                != HitResult.Type.MISS;
    }

    private static boolean canSeeBox(ServerPlayerEntity p, net.minecraft.util.math.Box b) {
        Vec3d eye = p.getEyePos();
        Vec3d[] points = {
                b.getCenter(),
                new Vec3d(b.getCenter().x, b.maxY - 0.05, b.getCenter().z),
                new Vec3d(b.getCenter().x, b.minY + 0.05, b.getCenter().z),
                new Vec3d(b.minX, b.getCenter().y, b.minZ), new Vec3d(b.maxX, b.getCenter().y, b.maxZ),
                new Vec3d(b.minX, b.getCenter().y, b.maxZ), new Vec3d(b.maxX, b.getCenter().y, b.minZ),
                new Vec3d(b.minX, b.maxY, b.minZ), new Vec3d(b.maxX, b.maxY, b.maxZ),
        };
        for (Vec3d pt : points) {
            if (!blocked(p, eye, pt, RaycastContext.ShapeType.COLLIDER) || !blocked(p, eye, pt, RaycastContext.ShapeType.OUTLINE)) {
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

    /** The server pushed this player (a hit, a fishing rod...): anti-knockback watch. */
    public static void knockback(ServerPlayerEntity p, Vec3d v) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s == null || !Ac.config().combat.enabled || Tps.tps() < Ac.config().general.lagTpsThreshold) {
            return;
        }
        net.minecraft.util.math.Box b = p.getBoundingBox();
        boolean headBlocked = !p.getEntityWorld().isSpaceEmpty(p, new net.minecraft.util.math.Box(b.minX, b.maxY, b.minZ, b.maxX, b.maxY + 0.6, b.maxZ));
        boolean excused = headBlocked || p.isTouchingWater() || p.isInLava() || p.isClimbing() || p.hasVehicle() || p.isGliding()
                || p.isSleeping() || p.isDead() || p.getAbilities().allowFlying || p.isSpectator()
                || p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.LEVITATION);
        s.velocity.onVelocity(System.currentTimeMillis(), v.y, p.networkHandler.getLatency(), s.bedrock, excused);
    }

    /** Every movement packet: a hit waiting for its look check. */
    public static void lookCheck(ServerPlayerEntity p, PlayerSession s, float yaw, float pitch) {
        Vec3 target = s.lookTarget;
        if (target == null) {
            return;
        }
        s.lookTarget = null;
        Vec3 eye = Mc.vec(p.getEyePos());
        double now = CombatTracker.angleTo(eye, Vec3.fromRotation(yaw, pitch), target);
        double atHit = CombatTracker.angleTo(eye, Vec3.fromRotation(s.lookYawAtHit, s.lookPitchAtHit), target);
        double off = Math.min(now, atHit) - s.lookTargetRadius;
        // Java aims with the crosshair; Bedrock touch can hit anything on screen, but nothing behind them.
        double limit = s.bedrock ? 100 : 45;
        if (off <= limit) {
            return;
        }
        long t = System.currentTimeMillis();
        if (t - s.badLookWindow > 20_000) {
            s.badLookWindow = t;
            s.badLooks = 0;
        }
        if (++s.badLooks == 3) {
            flag(p, CheckType.AIM, 1.5, String.format(Locale.ROOT, "hit targets %.0f° away from where they were looking (3 times)", off));
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
