package com.vylorq.anticheat.feature;

import com.google.gson.JsonObject;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mobs that wear a 3D model from the pack (bosses and their guards). On Java the mob itself is invisible (and its gear
 * hidden) and the model follows it. Every model is split into parts (body, head, jaw, arms, legs, wings, tail,
 * tentacles), each shown by its own item display and turned around its own pivot, so it walks, looks at you, swings,
 * flaps and roars.
 * Bedrock players can't see pack models, so they're shown the real mob and its gear instead.
 */
public final class ModelMobs {
    private ModelMobs() {
    }

    public static final String TAG = "vigil_model:";
    public static final String DISPLAY_TAG = "vigil_boss_model";

    /** How a body moves. */
    enum Style { BIPED, QUAD, CRAWLER, FLYER }

    /**
     * One moving piece of a model (head, jaw, arm, leg, wing, tail...). Its pivot is in blocks from the mob's feet
     * (before scaling); it turns around it, carried along by its parent.
     */
    record Part(String id, String role, int side, String parent, float phase, float px, float py, float pz) {
    }

    record Spec(float scale, Style style, List<Part> parts) {
    }

    private static Map<String, Spec> specs;

    private static final Map<String, Style> STYLES = Map.ofEntries(
            Map.entry("storm_phantom", Style.FLYER), Map.entry("gale_phantom", Style.FLYER), Map.entry("shadow_echo", Style.FLYER),
            Map.entry("ember_imp", Style.FLYER), Map.entry("hollow_watcher", Style.FLYER), Map.entry("sky_sentry", Style.FLYER),
            Map.entry("thornback_beast", Style.QUAD), Map.entry("frostbite_bear", Style.QUAD), Map.entry("jungle_stalker", Style.QUAD),
            Map.entry("stone_crawler", Style.CRAWLER), Map.entry("scarab", Style.CRAWLER), Map.entry("sculk_lurker", Style.CRAWLER),
            Map.entry("vine_creeper", Style.CRAWLER), Map.entry("sand_colossus", Style.CRAWLER));

    /** Scale and parts of every model (written by scripts/models/cubes.py). */
    static synchronized Map<String, Spec> specs() {
        if (specs == null) {
            specs = new HashMap<>();
            try (var in = ModelMobs.class.getResourceAsStream("/vigil/models.json")) {
                if (in != null) {
                    JsonObject o = ConfigManager.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    for (var e : o.entrySet()) {
                        JsonObject v = e.getValue().getAsJsonObject();
                        List<Part> parts = new ArrayList<>();
                        for (var pe : v.getAsJsonArray("parts")) {
                            JsonObject p = pe.getAsJsonObject();
                            var pv = p.getAsJsonArray("pivot");
                            parts.add(new Part(p.get("id").getAsString(), p.get("role").getAsString(), p.get("side").getAsInt(),
                                    p.get("parent").isJsonNull() ? null : p.get("parent").getAsString(), p.get("phase").getAsFloat(),
                                    pv.get(0).getAsFloat(), pv.get(1).getAsFloat(), pv.get(2).getAsFloat()));
                        }
                        specs.put(e.getKey(), new Spec(v.get("scale").getAsFloat(), STYLES.getOrDefault(e.getKey(), Style.BIPED),
                                List.copyOf(parts)));
                    }
                }
            } catch (Exception ex) {
                Ac.LOG.warn("Could not read model specs", ex);
            }
        }
        return specs;
    }

    public static boolean hasModel(String model) {
        return model != null && specs().containsKey(model);
    }

    private static final class Worn {
        final MobEntity mob;
        final String model;
        final Spec spec;
        /** Part id -> its display, and the last transformation sent for it. */
        final Map<String, UUID> displays = new HashMap<>();
        final Map<String, String> sent = new HashMap<>();
        double walk;

        Worn(MobEntity mob, String model, Spec spec) {
            this.mob = mob;
            this.model = model;
            this.spec = spec;
        }

        boolean owns(UUID id) {
            return displays.containsValue(id);
        }
    }

    private static final Map<UUID, Worn> WORN = new ConcurrentHashMap<>();
    private static final Set<Integer> IDS = ConcurrentHashMap.newKeySet();
    private static long now;

    /** Puts a model on a mob (call before spawning it). */
    public static void attach(MobEntity mob, String model) {
        if (!hasModel(model)) {
            return;
        }
        mob.addCommandTag(TAG + model);
        mob.setInvisible(true);
    }

    static String modelOf(Entity e) {
        for (String t : e.getCommandTags()) {
            if (t.startsWith(TAG)) {
                return t.substring(TAG.length());
            }
        }
        return null;
    }

    public static boolean wears(Entity e) {
        return WORN.containsKey(e.getUuid());
    }

    /** The main (body) display of this mob's model, or null. */
    public static Entity displayOf(Entity e) {
        Worn x = WORN.get(e.getUuid());
        if (x == null || x.spec.parts().isEmpty() || !(e.getEntityWorld() instanceof ServerWorld w)) {
            return null;
        }
        UUID id = x.displays.get(x.spec.parts().get(0).id());
        return id == null ? null : w.getEntity(id);
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (!Ac.running()) {
                return;
            }
            if (e.getCommandTags().contains(DISPLAY_TAG)) {
                // A model left over from before a restart: a fresh one is made for its mob.
                boolean owned = WORN.values().stream().anyMatch(x -> x.owns(e.getUuid()));
                if (!owned) {
                    // Not while the world is loading entities: a tick later.
                    OwnerPowers.later(1, () -> {
                        boolean claimed = WORN.values().stream().anyMatch(x -> x.owns(e.getUuid()));
                        if (!claimed && !e.isRemoved()) {
                            e.discard();
                        }
                    });
                }
                return;
            }
            String model = modelOf(e);
            if (model != null && e instanceof MobEntity mob && mob.isAlive() && hasModel(model)) {
                WORN.put(e.getUuid(), new Worn(mob, model, specs().get(model)));
                IDS.add(e.getId());
            }
        });
        // Unloading: just forget it (its model is saved with the chunk and cleaned up when the chunk loads again;
        // removing entities while the world is unloading them isn't allowed).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((e, w) -> {
            if (WORN.remove(e.getUuid()) != null) {
                IDS.remove(e.getId());
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (e.getEntityWorld() instanceof ServerWorld w) {
                drop(e, w);
            }
        });
    }

    private static void drop(Entity e, ServerWorld w) {
        Worn x = WORN.remove(e.getUuid());
        if (x != null) {
            IDS.remove(e.getId());
            for (UUID id : x.displays.values()) {
                Entity d = w.getEntity(id);
                if (d != null) {
                    d.discard();
                }
            }
        }
    }

    // ---------------------------------------------------------------- following and animation

    /** A part's place and turn in model space (blocks from the mob's feet; +z is the front). */
    private record Pose(Vector3f pos, Quaternionf rot) {
    }

    private static float wrap(float a) {
        a %= 360;
        return a >= 180 ? a - 360 : a < -180 ? a + 360 : a;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The part's own turn (pitch about x, yaw about y, roll about z, in degrees) for this moment. */
    private static Quaternionf local(Worn x, Part p, double t, float amp, float swing) {
        MobEntity m = x.mob;
        double walk = x.walk;
        float pitch = 0;
        float yaw = 0;
        float roll = 0;
        float attack = (float) Math.sin(Math.PI * swing);
        boolean flyer = x.spec.style() == Style.FLYER;
        switch (p.role()) {
            case "body" -> {
                if (flyer) {
                    pitch = (float) (Math.cos(t * 0.45) * 5) + attack * 20;
                    roll = (float) (Math.sin(t * 0.9) * 6);
                } else if (x.spec.style() == Style.QUAD) {
                    pitch = (float) (Math.sin(walk * 2) * 2 * amp) + attack * 8;
                } else if (x.spec.style() == Style.CRAWLER) {
                    roll = (float) (Math.sin(walk * 2) * 3 * amp);
                    pitch = attack * 10;
                } else {
                    pitch = 6 * amp + attack * 14 + (float) (Math.sin(t * 0.25) * 1.5);
                    roll = (float) (Math.sin(walk) * 4 * amp);
                }
            }
            case "torso" -> {
                roll = (float) (Math.sin(t * 0.35) * 5 + Math.sin(walk) * 6 * amp);
                pitch = attack * 22;
            }
            case "head" -> {
                yaw = -clamp(wrap(m.getHeadYaw() - m.getBodyYaw()), -55, 55);
                pitch = clamp(m.getPitch(), -35, 35) + (float) (Math.sin(t * 0.3) * 2) - attack * 10;
                if (m.hurtTime > 0) {
                    roll = (float) (Math.sin(m.hurtTime * 2.5) * 12);
                }
            }
            case "jaw" -> {
                double roar = Math.pow(Math.max(0, Math.sin(t * 0.08)), 12);
                pitch = 4 + attack * 32 + (float) (roar * 26) + (float) (Math.sin(t * 0.4) * 2);
            }
            case "arm", "arm2" -> {
                double ph = p.phase() + ("arm2".equals(p.role()) ? Math.PI : 0);
                pitch = (float) (-Math.sin(walk + ph) * 32 * amp) - attack * ("arm2".equals(p.role()) ? 45 : 110)
                        + (float) (Math.sin(t * 0.3 + ph) * 3);
                roll = p.side() * (float) (4 + Math.sin(t * 0.3) * 2);
            }
            case "leg", "leg_front", "leg_back" -> {
                if (x.spec.style() == Style.CRAWLER) {
                    yaw = (float) (Math.sin(walk * 2 + p.phase()) * 18 * amp);
                    roll = -p.side() * (float) (Math.max(0, Math.cos(walk * 2 + p.phase())) * 14 * amp);
                } else {
                    pitch = (float) (Math.sin(walk + p.phase()) * 32 * amp);
                }
            }
            case "wing" -> roll = p.side() * (float) (Math.sin(t * 1.6) * 32 + 8 + attack * 20);
            case "wingtip" -> roll = p.side() * (float) (Math.sin(t * 1.6 - 0.7) * 24);
            case "tail" -> {
                yaw = (float) (Math.sin(t * 0.5 + walk * 0.5) * 16);
                pitch = (float) (Math.sin(t * 0.3) * 4) - attack * 18;
            }
            case "tentacle" -> {
                pitch = (float) (Math.sin(t * 0.5 + p.phase()) * 16);
                roll = (float) (Math.cos(t * 0.4 + p.phase()) * 12);
            }
            default -> {
            }
        }
        return new Quaternionf().rotateY((float) Math.toRadians(yaw)).rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));
    }

    private static Pose pose(Worn x, Part p, Map<String, Part> byId, Map<String, Pose> done, double t, float amp, float swing) {
        Pose have = done.get(p.id());
        if (have != null) {
            return have;
        }
        float k = x.spec.scale();
        Quaternionf q = local(x, p, t, amp, swing);
        Pose out;
        Part parent = p.parent() == null ? null : byId.get(p.parent());
        if (parent == null) {
            Vector3f pos = new Vector3f(p.px(), p.py(), p.pz()).mul(k);
            if ("body".equals(p.role())) {
                float bob = x.spec.style() == Style.FLYER ? (float) (Math.sin(t * 0.9) * 0.15)
                        : (float) (Math.abs(Math.sin(x.walk)) * 0.05 * amp + Math.sin(t * 0.25) * 0.02) * k;
                pos.y += bob;
            }
            out = new Pose(pos, q);
        } else {
            Pose pp = pose(x, parent, byId, done, t, amp, swing);
            Vector3f off = new Vector3f(p.px() - parent.px(), p.py() - parent.py(), p.pz() - parent.pz()).mul(k);
            pp.rot().transform(off);
            out = new Pose(off.add(pp.pos()), new Quaternionf(pp.rot()).mul(q));
        }
        done.put(p.id(), out);
        return out;
    }

    /**
     * The display transformation for a part's pose. Item displays draw the model turned half way round, so the pose
     * is turned the same way first.
     */
    private static String transform(Spec s, Pose p) {
        Quaternionf q = p.rot();
        float k = s.scale();
        return String.format(java.util.Locale.ROOT,
                "{left_rotation:[%.4ff,%.4ff,%.4ff,%.4ff],right_rotation:[0f,0f,0f,1f],translation:[%.3ff,%.3ff,%.3ff],scale:[%.4ff,%.4ff,%.4ff]}",
                -q.x, q.y, -q.z, q.w, -p.pos().x, p.pos().y, -p.pos().z, k, k, k);
    }

    private static Map<String, Pose> poses(Worn x, double t, float amp, float swing) {
        Map<String, Part> byId = new HashMap<>();
        for (Part p : x.spec.parts()) {
            byId.put(p.id(), p);
        }
        Map<String, Pose> done = new HashMap<>();
        for (Part p : x.spec.parts()) {
            pose(x, p, byId, done, t, amp, swing);
        }
        return done;
    }

    private static void follow(ServerWorld w, Worn x) {
        float yaw = x.mob.getBodyYaw() + Bosses.state().yawOffset;
        Map<String, Pose> rest = null;
        for (Part p : x.spec.parts()) {
            UUID id = x.displays.get(p.id());
            Entity d = id == null ? null : w.getEntity(id);
            if (d == null || d.isRemoved()) {
                if (rest == null) {
                    rest = poses(x, now * 0.45, 0, 0);
                }
                id = OwnerCombat.Display.summon(w, x.mob.getEntityPos(), "minecraft:nautilus_shell", p.id(),
                        transform(x.spec, rest.get(p.id())));
                d = id == null ? null : w.getEntity(id);
                if (d == null) {
                    continue;
                }
                d.addCommandTag(DISPLAY_TAG);
                x.displays.put(p.id(), id);
                x.sent.remove(p.id());
            }
            d.refreshPositionAndAngles(x.mob.getX(), x.mob.getY(), x.mob.getZ(), yaw, 0);
        }
    }

    private static void animate(ServerWorld w, Worn x) {
        MobEntity m = x.mob;
        double speed = m.getVelocity().horizontalLength();
        // Big bodies take slower, longer steps.
        x.walk += speed * 5.0 / Math.max(0.6, x.spec.scale());
        float amp = (float) Math.min(1, speed * 9);
        float swing = m.handSwinging ? m.getHandSwingProgress(1f) : 0;
        double t = now * 0.45 + (m.getId() % 7);
        Map<String, Pose> all = poses(x, t, amp, swing);
        for (Part p : x.spec.parts()) {
            UUID id = x.displays.get(p.id());
            if (id == null) {
                continue;
            }
            String tr = transform(x.spec, all.get(p.id()));
            if (tr.equals(x.sent.get(p.id()))) {
                continue;
            }
            x.sent.put(p.id(), tr);
            String cmd = "data merge entity " + id + " {start_interpolation:0,interpolation_duration:3,transformation:" + tr + "}";
            try {
                Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(), cmd);
            } catch (Exception ignored) {
                // animation is cosmetic
            }
        }
    }

    public static void tick(long ticks) {
        now = ticks;
        for (Worn x : WORN.values()) {
            if (x.mob.isRemoved() || !x.mob.isAlive()) {
                continue;
            }
            ServerWorld w = (ServerWorld) x.mob.getEntityWorld();
            if (!x.mob.isInvisible()) {
                // The game turns invisibility off whenever its effects change (a boss's next phase, Resistance
                // running out...), which would show the plain mob inside its model.
                x.mob.setInvisible(true);
            }
            follow(w, x);
            // Only animate where someone can see it.
            if (ticks % 2 == 0 && w.getClosestPlayer(x.mob, 80) != null) {
                animate(w, x);
            }
        }
    }

    /** Removes every model display (owner cleanup). */
    public static void clearAll() {
        for (Worn x : new ArrayList<>(WORN.values())) {
            drop(x.mob, (ServerWorld) x.mob.getEntityWorld());
        }
    }

    // ---------------------------------------------------------------- what each player is sent

    /**
     * Bedrock players see the real mob (its invisibility is cleared for them). Java players see the model, so the
     * mob's held items and armour are hidden from them.
     */
    public static Packet<?> forViewer(ServerPlayerEntity to, Packet<?> packet) {
        if (packet instanceof EntityTrackerUpdateS2CPacket u && IDS.contains(u.id()) && com.vylorq.anticheat.ui.Viewer.isBedrock(to)) {
            List<net.minecraft.entity.data.DataTracker.SerializedEntry<?>> out = new ArrayList<>(u.trackedValues().size());
            boolean changed = false;
            for (var e : u.trackedValues()) {
                if (e.id() == 0 && e.value() instanceof Byte b) {
                    out.add(new net.minecraft.entity.data.DataTracker.SerializedEntry<>(0,
                            net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE, (byte) (b & ~0x20)));
                    changed = true;
                } else {
                    out.add(e);
                }
            }
            return changed ? new EntityTrackerUpdateS2CPacket(u.id(), out) : packet;
        }
        if (packet instanceof EntityEquipmentUpdateS2CPacket eq && IDS.contains(eq.getEntityId()) && !com.vylorq.anticheat.ui.Viewer.isBedrock(to)) {
            List<com.mojang.datafixers.util.Pair<net.minecraft.entity.EquipmentSlot, ItemStack>> empty = new ArrayList<>();
            for (var p : eq.getEquipmentList()) {
                empty.add(com.mojang.datafixers.util.Pair.of(p.getFirst(), ItemStack.EMPTY));
            }
            return new EntityEquipmentUpdateS2CPacket(eq.getEntityId(), empty);
        }
        return packet;
    }
}
