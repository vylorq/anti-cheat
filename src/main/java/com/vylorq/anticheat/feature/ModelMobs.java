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
 * Mobs that wear a pixel-art 3D model from the pack (bosses and their guards). On Java the mob itself is
 * invisible (and its gear hidden) and the model follows it, swaying, bobbing, lunging and flapping as it moves.
 * Bedrock players can't see pack models, so they're shown the real mob and its gear instead.
 */
public final class ModelMobs {
    private ModelMobs() {
    }

    public static final String TAG = "vigil_model:";
    public static final String DISPLAY_TAG = "vigil_boss_model";

    /** How a body moves. */
    enum Style { BIPED, QUAD, CRAWLER, FLYER }

    record Spec(float scale, float lift, Style style) {
    }

    private static Map<String, Spec> specs;

    private static final Map<String, Style> STYLES = Map.ofEntries(
            Map.entry("storm_phantom", Style.FLYER), Map.entry("gale_phantom", Style.FLYER), Map.entry("shadow_echo", Style.FLYER),
            Map.entry("ember_imp", Style.FLYER), Map.entry("hollow_watcher", Style.FLYER), Map.entry("sky_sentry", Style.FLYER),
            Map.entry("thornback_beast", Style.QUAD), Map.entry("frostbite_bear", Style.QUAD),
            Map.entry("stone_crawler", Style.CRAWLER), Map.entry("scarab", Style.CRAWLER), Map.entry("sculk_lurker", Style.CRAWLER),
            Map.entry("jungle_stalker", Style.CRAWLER), Map.entry("vine_creeper", Style.CRAWLER));

    /** Scale and lift of every model (written by scripts/models/sculpt.py). */
    static synchronized Map<String, Spec> specs() {
        if (specs == null) {
            specs = new HashMap<>();
            try (var in = ModelMobs.class.getResourceAsStream("/vigil/models.json")) {
                if (in != null) {
                    JsonObject o = ConfigManager.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    for (var e : o.entrySet()) {
                        JsonObject v = e.getValue().getAsJsonObject();
                        specs.put(e.getKey(), new Spec(v.get("scale").getAsFloat(), v.get("lift").getAsFloat(),
                                STYLES.getOrDefault(e.getKey(), Style.BIPED)));
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
        UUID display;
        String animKey = "";

        Worn(MobEntity mob, String model, Spec spec) {
            this.mob = mob;
            this.model = model;
            this.spec = spec;
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

    /** The model display following this mob, or null. */
    public static Entity displayOf(Entity e) {
        Worn x = WORN.get(e.getUuid());
        return x == null || x.display == null || !(e.getEntityWorld() instanceof ServerWorld w) ? null : w.getEntity(x.display);
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (!Ac.running()) {
                return;
            }
            if (e.getCommandTags().contains(DISPLAY_TAG)) {
                // A model left over from before a restart: a fresh one is made for its mob.
                boolean owned = WORN.values().stream().anyMatch(x -> e.getUuid().equals(x.display));
                if (!owned) {
                    // Not while the world is loading entities: a tick later.
                    OwnerPowers.later(1, () -> {
                        boolean claimed = WORN.values().stream().anyMatch(x -> e.getUuid().equals(x.display));
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
            Entity d = x.display == null ? null : w.getEntity(x.display);
            if (d != null) {
                d.discard();
            }
        }
    }

    // ---------------------------------------------------------------- following and animation

    private static String transform(Spec s, float pitch, float roll, float bob) {
        double hx = Math.toRadians(pitch) / 2;
        double hz = Math.toRadians(roll) / 2;
        double qx = Math.sin(hx) * Math.cos(hz);
        double qy = Math.sin(hx) * Math.sin(hz);
        double qz = Math.cos(hx) * Math.sin(hz);
        double qw = Math.cos(hx) * Math.cos(hz);
        float k = s.scale();
        return String.format(java.util.Locale.ROOT,
                "{left_rotation:[%.4ff,%.4ff,%.4ff,%.4ff],right_rotation:[0f,0f,0f,1f],translation:[0f,%.3ff,0f],scale:[%.4ff,%.4ff,%.4ff]}",
                qx, qy, qz, qw, s.lift() + bob, k, k, k);
    }

    private static void follow(ServerWorld w, Worn x) {
        Entity d = x.display == null ? null : w.getEntity(x.display);
        if (d == null || d.isRemoved()) {
            x.display = OwnerCombat.Display.summon(w, x.mob.getEntityPos(), "minecraft:nautilus_shell", x.model, transform(x.spec, 0, 0, 0));
            d = x.display == null ? null : w.getEntity(x.display);
            if (d == null) {
                return;
            }
            d.addCommandTag(DISPLAY_TAG);
            x.animKey = "";
        }
        d.refreshPositionAndAngles(x.mob.getX(), x.mob.getY(), x.mob.getZ(), x.mob.getBodyYaw() + Bosses.state().yawOffset, 0);
    }

    private static void animate(ServerWorld w, Worn x) {
        if (x.display == null) {
            return;
        }
        MobEntity m = x.mob;
        double speed = m.getVelocity().horizontalLength();
        float pitch = 0;
        float roll = 0;
        float bob = 0;
        double t = now * 0.45 + (m.getId() % 7);
        switch (x.spec.style()) {
            case FLYER -> {
                // Wings beat: the whole body rocks and rises and falls.
                roll = (float) (Math.sin(t * 0.9) * 9);
                pitch = (float) (Math.cos(t * 0.45) * 5 + (m.handSwinging ? 18 : 0));
                bob = (float) (Math.sin(t * 0.9) * 0.18);
            }
            case QUAD -> {
                if (m.handSwinging) {
                    pitch = 14;
                    bob = -0.06f;
                } else if (speed > 0.02) {
                    pitch = (float) (Math.sin(t * 1.3) * 5);
                    bob = (float) (Math.abs(Math.sin(t * 1.3)) * 0.1);
                } else {
                    bob = (float) (Math.sin(now * 0.08) * 0.03);
                }
            }
            case CRAWLER -> {
                if (speed > 0.01) {
                    roll = (float) (Math.sin(t * 2.2) * 6);
                    bob = (float) (Math.abs(Math.sin(t * 2.2)) * 0.05);
                }
                if (m.handSwinging) {
                    pitch = 12;
                }
            }
            default -> {
                if (m.handSwinging) {
                    pitch = 16;
                    bob = -0.08f;
                } else if (speed > 0.02) {
                    roll = (float) (Math.sin(t) * 7);
                    bob = (float) (Math.abs(Math.sin(t)) * 0.12);
                    pitch = 5;
                } else {
                    // Breathing
                    bob = (float) (Math.sin(now * 0.08) * 0.04);
                    pitch = (float) (Math.sin(now * 0.08) * 1.5);
                }
            }
        }
        String key = Math.round(pitch) + "," + Math.round(roll) + "," + Math.round(bob * 100);
        if (key.equals(x.animKey)) {
            return;
        }
        x.animKey = key;
        String cmd = "data merge entity " + x.display + " {start_interpolation:0,interpolation_duration:4,transformation:"
                + transform(x.spec, pitch, roll, bob) + "}";
        try {
            Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(), cmd);
        } catch (Exception ignored) {
            // animation is cosmetic
        }
    }

    public static void tick(long ticks) {
        now = ticks;
        for (Worn x : WORN.values()) {
            if (x.mob.isRemoved() || !x.mob.isAlive()) {
                continue;
            }
            ServerWorld w = (ServerWorld) x.mob.getEntityWorld();
            follow(w, x);
            if (ticks % 2 == 0) {
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
