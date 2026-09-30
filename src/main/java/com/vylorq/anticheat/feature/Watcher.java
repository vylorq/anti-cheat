package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.watcher.Gaze;
import com.vylorq.anticheat.core.watcher.WatcherEffect;
import com.vylorq.anticheat.core.watcher.WatcherEligibility;
import com.vylorq.anticheat.core.watcher.WatcherScheduler;
import com.vylorq.anticheat.gui.MenuHandler;
import com.vylorq.anticheat.util.Mc;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerSpawnPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.RemoveEntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The Watcher (section 33): a presence made only of fake, per-player packets. Nothing here touches the real world,
 * real items or the anti-cheat. Every effect remembers what it sent and puts it back when it ends, including when the
 * player leaves, dies, respawns, teleports or changes dimension mid-effect.
 */
public final class Watcher {
    private Watcher() {
    }

    /** Test hook: sees every packet the Watcher sends. */
    public static BiConsumer<ServerPlayerEntity, Packet<?>> spy;

    private static final Map<UUID, Effect> ACTIVE = new HashMap<>();
    /** Players with a chest note waiting for the next chest they open, and until when. */
    private static final Map<UUID, Long> CHEST_NOTES = new HashMap<>();
    /** How often an appearance was put off because the moment wasn't right (dark, alone, night). */
    private static final Map<UUID, Integer> PUT_OFF = new HashMap<>();
    private static final List<Delayed> DELAYED = new ArrayList<>();
    private static long ticks;
    private static String serverListText;
    private static long serverListUntil;

    private record Delayed(long at, Runnable run) {
    }

    // ---- lifecycle and hooks ----

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (Ac.running() && Ac.get().watcher != null) {
                onLeave(handler.player.getUuid());
            }
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> stopFor(player.getUuid(), true));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> stopFor(oldPlayer.getUuid(), true));
        EntitySleepEvents.START_SLEEPING.register((entity, pos) -> {
            if (entity instanceof ServerPlayerEntity p) {
                onSleep(p);
            }
        });
        EntitySleepEvents.STOP_SLEEPING.register((entity, pos) -> {
            if (entity instanceof ServerPlayerEntity p) {
                onWake(p, pos);
            }
        });
    }

    /** Called on server start: forget everything from a previous run. */
    public static void reset() {
        ACTIVE.clear();
        CHEST_NOTES.clear();
        PUT_OFF.clear();
        DELAYED.clear();
        serverListText = null;
        serverListUntil = 0;
    }

    private static WatcherScheduler sched() {
        return Ac.get().watcher;
    }

    private static AcConfig.Watcher cfg() {
        return Ac.config().watcher;
    }

    public static WatcherScheduler.Settings settings() {
        AcConfig.Watcher c = cfg();
        WatcherScheduler.Settings s = new WatcherScheduler.Settings();
        s.minMinutes = c.minMinutes;
        s.maxMinutes = c.maxMinutes;
        for (var e : c.weights.entrySet()) {
            WatcherEffect w = WatcherEffect.byId(e.getKey());
            if (w != null && e.getValue() != null) {
                s.weights.put(w, e.getValue());
            }
        }
        for (String id : c.disabledEffects) {
            WatcherEffect w = WatcherEffect.byId(id);
            if (w != null) {
                s.disabled.add(w);
            }
        }
        s.rareRush = c.rareRush;
        s.rareChance = c.rareChance;
        s.glitchChance = c.glitchChance;
        s.messageCooldownMinMinutes = c.messageCooldownMinMinutes;
        s.messageCooldownMaxMinutes = c.messageCooldownMaxMinutes;
        s.nightEnabled = c.nightEnabled;
        s.nightMinDays = c.nightMinDays;
        s.nightMaxDays = c.nightMaxDays;
        return s;
    }

    public static boolean effectOn(WatcherEffect e) {
        if (e == WatcherEffect.RUSH && !cfg().rareRush) {
            return false;
        }
        for (String id : cfg().disabledEffects) {
            if (e == WatcherEffect.byId(id)) {
                return false;
            }
        }
        return true;
    }

    public static String serverListText() {
        if (serverListText != null && System.currentTimeMillis() > serverListUntil) {
            serverListText = null;
        }
        return serverListText;
    }

    // ---- packets ----

    static void send(ServerPlayerEntity p, Packet<?> packet) {
        if (spy != null) {
            spy.accept(p, packet);
        }
        p.networkHandler.sendPacket(packet);
    }

    @SuppressWarnings("unchecked")
    private static RegistryEntry<SoundEvent> soundEntry(Object sound) {
        if (sound instanceof RegistryEntry<?> r) {
            return (RegistryEntry<SoundEvent>) r;
        }
        return Registries.SOUND_EVENT.getEntry((SoundEvent) sound);
    }

    private static void sound(ServerPlayerEntity p, Object sound, SoundCategory cat, Vec3d at, float volume, float pitch) {
        send(p, new PlaySoundS2CPacket(soundEntry(sound), cat, at.x, at.y, at.z, volume, pitch, p.getRandom().nextLong()));
    }

    /** Slow, low breathing coming from the figure's direction (33.3). */
    private static void breathe(ServerPlayerEntity p, Vec3d from) {
        Vec3d eye = p.getEyePos();
        Vec3d dir = from.subtract(eye);
        Vec3d at = dir.lengthSquared() < 1e-4 ? eye : eye.add(dir.normalize().multiply(Math.min(4.0, dir.length())));
        sound(p, SoundEvents.ENTITY_HORSE_BREATHE, SoundCategory.HOSTILE, at, 0.7f, 0.5f);
    }

    private static void heartbeat(ServerPlayerEntity p) {
        sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, p.getEyePos(), 0.6f, 0.6f);
    }

    /** One-second Darkness pulse when he vanishes. Skipped if the player already has real Darkness. */
    private static void darknessPulse(ServerPlayerEntity p) {
        if (p.hasStatusEffect(StatusEffects.DARKNESS)) {
            return;
        }
        send(p, new EntityStatusEffectS2CPacket(p.getId(), new StatusEffectInstance(StatusEffects.DARKNESS, 30, 0, false, false, false), false));
        UUID id = p.getUuid();
        later(25, () -> {
            ServerPlayerEntity now = Ac.server().getPlayerManager().getPlayer(id);
            if (now != null && !now.hasStatusEffect(StatusEffects.DARKNESS)) {
                send(now, new RemoveEntityStatusEffectS2CPacket(now.getId(), StatusEffects.DARKNESS));
            }
        });
    }

    private static void later(int delayTicks, Runnable r) {
        DELAYED.add(new Delayed(ticks + delayTicks, r));
    }

    private static void watchingText(ServerPlayerEntity p, boolean glitch) {
        if (glitch) {
            send(p, new TitleFadeS2CPacket(0, 12, 6));
            send(p, new TitleS2CPacket(Text.literal(cfg().glitchText).formatted(Formatting.GRAY)));
        } else {
            send(p, new net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket(
                    Text.literal(cfg().watchingText).formatted(Formatting.DARK_GRAY, Formatting.ITALIC)));
        }
        sched().messageShown(p.getUuid(), settings());
    }

    private static void sendWeather(ServerPlayerEntity p, ServerWorld w) {
        if (w.isRaining()) {
            send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STARTED, 0));
            send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, w.getRainGradient(1f)));
            send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, w.getThunderGradient(1f)));
        } else {
            send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STOPPED, 0));
            send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, 0));
            send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, 0));
        }
    }

    private static ItemStack fakePaper(String name) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
        return s;
    }

    // ---- eligibility (33.1). Nothing from the anti-cheat is read here (33.8). ----

    public static WatcherEligibility eligibility(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        UUID id = p.getUuid();
        WatcherEligibility e = new WatcherEligibility();
        e.enabled = cfg().enabled;
        e.excluded = ac.watcher.excluded(id);
        e.creativeOrSpectator = p.isCreative() || p.isSpectator();
        e.dead = p.isDead() || p.isRemoved();
        e.inCombat = inCombat(p);
        e.inArena = Arenas.inMatch(p) || Arenas.isSpectating(p);
        e.trading = Trades.inTrade(p);
        e.menuOpen = p.currentScreenHandler != p.playerScreenHandler;
        e.jailed = Jail.isJailed(p);
        e.frozen = StaffTools.isFrozen(p);
        e.waitingRoom = WaitingRoomFeature.waiting(p);
        e.staffSpectating = ac.staff.spectating(id) != null || beingSpectated(id);
        return e;
    }

    private static boolean inCombat(ServerPlayerEntity p) {
        int window = Math.max(1, cfg().combatSeconds) * 20;
        int hurt = p.getLastAttackedTime();
        int hit = p.getLastAttackTime();
        return (hurt > 0 && p.age - hurt < window) || (hit > 0 && p.age - hit < window);
    }

    private static boolean beingSpectated(UUID id) {
        for (var r : Ac.get().staff.data().spectating.values()) {
            if (id.equals(r.target)) {
                return true;
            }
        }
        return false;
    }

    /** Darkness, night, underground and being alone all make an appearance more likely (33.2). */
    private static boolean goodMoment(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        BlockPos head = p.getBlockPos().up();
        double chance = 0.3;
        if (w.isNight()) chance += 0.25;
        if (!w.isSkyVisible(head)) chance += 0.25;
        if (w.getLightLevel(head) < 8) chance += 0.2;
        if (w.getClosestPlayer(p.getX(), p.getY(), p.getZ(), 48, e -> e != p && !e.isSpectator()) == null) {
            chance += 0.2;
        }
        return sched().roll(chance);
    }

    // ---- ticking ----

    public static void tick(MinecraftServer server) {
        Ac ac = Ac.get();
        if (ac == null || ac.watcher == null) {
            return;
        }
        ticks++;
        runDelayed();
        for (Effect e : new ArrayList<>(ACTIVE.values())) {
            ServerPlayerEntity now = server.getPlayerManager().getPlayer(e.p.getUuid());
            if (now != e.p) {
                // Left or respawned as a new entity: the client has already forgotten the fakes.
                finish(e, true);
                continue;
            }
            step(e);
        }
        if (!CHEST_NOTES.isEmpty()) {
            chestNotes(server);
        }
        if (ticks % 20 == 0) {
            everySecond(server);
        }
        if (ticks % 1200 == 0) {
            everyMinute(server);
        }
    }

    /** One tick of an effect, ending it when it's done or no longer allowed. */
    static void step(Effect e) {
        ServerPlayerEntity p = e.p;
        if (!Mc.worldId(p.getEntityWorld()).equals(e.world)) {
            finish(e, true);
            return;
        }
        WatcherEligibility el = eligibility(p);
        if (e.allowMenus) {
            el.menuOpen = false;
        }
        if (e.forced) {
            // Summoned by the owner: only stop for things that would make it unsafe.
            el.enabled = true;
            el.inCombat = false;
            el.inArena = false;
            el.jailed = false;
            el.frozen = false;
            el.waitingRoom = false;
            el.menuOpen = false;
            if (!e.touchesInventory) {
                el.creativeOrSpectator = false;
            }
        }
        Vec3d pos = p.getEntityPos();
        boolean teleported = e.lastPos != null && e.lastPos.squaredDistanceTo(pos) > 16 * 16;
        e.lastPos = pos;
        if (!el.allowed() || teleported) {
            finish(e, false);
            return;
        }
        boolean more;
        try {
            more = e.tick();
        } catch (RuntimeException ex) {
            Ac.LOG.warn("Watcher effect {} failed", e.type, ex);
            more = false;
        }
        e.age++;
        if (!more) {
            finish(e, false);
        }
    }

    private static void finish(Effect e, boolean worldGone) {
        if (ACTIVE.get(e.p.getUuid()) == e) {
            ACTIVE.remove(e.p.getUuid());
            sched().end(e.p.getUuid(), settings());
        }
        e.stop(worldGone);
    }

    private static void runDelayed() {
        if (DELAYED.isEmpty()) {
            return;
        }
        List<Delayed> due = new ArrayList<>();
        for (Iterator<Delayed> it = DELAYED.iterator(); it.hasNext(); ) {
            Delayed d = it.next();
            if (d.at <= ticks) {
                due.add(d);
                it.remove();
            }
        }
        for (Delayed d : due) {
            try {
                d.run.run();
            } catch (RuntimeException ex) {
                Ac.LOG.warn("Watcher task failed", ex);
            }
        }
    }

    private static void everySecond(MinecraftServer server) {
        WatcherScheduler s = sched();
        WatcherScheduler.Settings set = settings();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            UUID id = p.getUuid();
            if (s.nextEventAt(id) == Long.MAX_VALUE) {
                s.onJoin(id, set);
                continue;
            }
            if (!cfg().enabled || ACTIVE.containsKey(id) || !s.due(id)) {
                continue;
            }
            if (!eligibility(p).allowed()) {
                s.postpone(id);
                continue;
            }
            WatcherEffect eff = s.pick(id, set);
            if (eff == null) {
                s.schedule(id, set);
                continue;
            }
            if ((eff == WatcherEffect.APPEAR || eff == WatcherEffect.DOPPELGANGER) && !goodMoment(p)
                    && PUT_OFF.merge(id, 1, Integer::sum) <= 3) {
                s.postpone(id);
                continue;
            }
            PUT_OFF.remove(id);
            if (start(p, eff, "schedule", false) == null) {
                s.schedule(id, set);
            }
        }
    }

    private static void everyMinute(MinecraftServer server) {
        AcConfig.Watcher c = cfg();
        if (!c.enabled) {
            return;
        }
        if (sched().nightDue(settings())) {
            Ac.markDirty("watcher");
            night("schedule");
        }
        if (serverListText() == null && !c.serverListMessages.isEmpty() && sched().roll(c.serverListChancePerHour / 60.0)) {
            serverListText = c.serverListMessages.get(sched().random().nextInt(c.serverListMessages.size()));
            serverListUntil = System.currentTimeMillis() + Math.max(1, c.serverListMinutes) * Durations.MINUTE;
        }
    }

    private static void onLeave(UUID id) {
        stopFor(id, true);
        sched().onLeave(id);
        CHEST_NOTES.remove(id);
        PUT_OFF.remove(id);
    }

    /** Ends whatever is running for the player. */
    public static void stopFor(UUID id, boolean worldGone) {
        if (!Ac.running() || Ac.get().watcher == null) {
            return;
        }
        Effect e = ACTIVE.get(id);
        if (e != null) {
            finish(e, worldGone);
        }
    }

    /** /watcher off: stop everything now. */
    public static void stopAll() {
        for (Effect e : new ArrayList<>(ACTIVE.values())) {
            finish(e, false);
        }
        CHEST_NOTES.clear();
        serverListText = null;
    }

    public static boolean busy(UUID id) {
        return ACTIVE.containsKey(id);
    }

    // ---- starting effects ----

    /**
     * Starts an effect for a player.
     *
     * @param forced summoned by the owner (skips timing and most situation checks)
     * @return the effect actually started (may be a fallback), or null if nothing could start
     */
    public static WatcherEffect start(ServerPlayerEntity p, WatcherEffect eff, String by, boolean forced) {
        if (ACTIVE.containsKey(p.getUuid())) {
            return null;
        }
        Effect e = create(p, eff);
        if (e == null && eff != WatcherEffect.MESSAGE && sched().messageAllowed(p.getUuid()) && effectOn(WatcherEffect.MESSAGE)) {
            // No place for this one here (no torches, no wall, no animals...): just the message.
            eff = WatcherEffect.MESSAGE;
            e = create(p, eff);
        }
        if (e == null) {
            return null;
        }
        e.forced = forced;
        if (e.touchesInventory && (p.isCreative() || p.isSpectator())) {
            // A fake slot could become real through the creative inventory.
            return null;
        }
        ACTIVE.put(p.getUuid(), e);
        sched().begin(p.getUuid());
        log(p, e.type, by);
        step(e);
        return e.type;
    }

    private static void log(ServerPlayerEntity p, WatcherEffect eff, String by) {
        sched().log(p.getUuid(), p.getGameProfile().name(), eff, by);
        Ac.markDirty("watcher");
    }

    /** Builds the effect, or null when it can't happen here. */
    static Effect create(ServerPlayerEntity p, WatcherEffect eff) {
        return switch (eff) {
            case APPEAR -> Appear.create(p, false);
            case DOPPELGANGER -> Appear.create(p, true);
            case MESSAGE -> new Instant(p, eff, () -> watchingText(p, sched().glitch(settings())));
            case GLITCH_TEXT -> new Instant(p, eff, () -> watchingText(p, true));
            case FOOTSTEPS -> new Footsteps(p);
            case TURN_AROUND -> TurnAround.create(p);
            case MIRRORING -> Mirroring.create(p);
            case FLICKER -> Flicker.create(p);
            case SIGN -> Sign.create(p);
            case WHISPER -> new Instant(p, eff, () -> p.sendMessage(Text.translatable("commands.message.display.incoming",
                    Text.literal(cfg().whisperFrom), Text.literal(cfg().whisperText)).formatted(Formatting.GRAY, Formatting.ITALIC)));
            case OWN_VOICE -> new Instant(p, eff, () -> p.sendMessage(Text.translatable("chat.type.text",
                    p.getDisplayName(), Text.literal(cfg().ownVoiceText))));
            case SILENCE -> new Silence(p);
            case KNOCKING -> new Knocking(p);
            case ANIMALS_STARE -> AnimalsStare.create(p);
            case STORM -> Storm.create(p);
            case WRONG_COMPASS -> WrongCompass.create(p);
            case GIFT -> Gift.create(p);
            case FAKE_JOIN -> new FakeJoin(p);
            case RUSH -> Rush.create(p);
            case BEDSIDE -> Bedside.create(p, p.getBlockPos());
            case SLEEP_WELL -> new Instant(p, eff, () -> sleepWell(p));
        };
    }

    // ---- server-wide ----

    /** Watcher Night: every eligible player sees the message at the same moment (33.5). */
    public static int night(String by) {
        int n = 0;
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (ACTIVE.containsKey(p.getUuid()) || !eligibility(p).allowed()) {
                continue;
            }
            watchingText(p, false);
            heartbeat(p);
            log(p, WatcherEffect.MESSAGE, by.equals("schedule") ? "night" : "night (" + by + ")");
            n++;
        }
        return n;
    }

    /**
     * The Watcher stands where a banned cheater was, visible to everyone nearby, just before the lightning (33.5).
     *
     * @return true if it appeared (the strike then runs a second later), false to strike now
     */
    public static boolean banAppearance(ServerWorld w, Vec3d pos, float yaw, Runnable strike) {
        AcConfig.Watcher c = cfg();
        if (!c.enabled || !c.banAppearance || Ac.get().watcher == null) {
            return false;
        }
        WatcherFigure fig = WatcherFigure.watcher(w).at(pos, yaw, 0);
        List<ServerPlayerEntity> viewers = new ArrayList<>();
        for (ServerPlayerEntity v : w.getPlayers()) {
            if (v.squaredDistanceTo(pos) < 64 * 64 && !sched().excluded(v.getUuid())) {
                fig.show(v);
                viewers.add(v);
            }
        }
        if (viewers.isEmpty()) {
            return false;
        }
        later(20, () -> {
            for (ServerPlayerEntity v : viewers) {
                if (!v.isRemoved()) {
                    fig.hide(v);
                }
            }
            strike.run();
        });
        return true;
    }

    // ---- sleep ----

    private static void sleepWell(ServerPlayerEntity p) {
        send(p, new net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket(
                Text.literal(cfg().sleepText).formatted(Formatting.GRAY, Formatting.ITALIC)));
    }

    private static void onSleep(ServerPlayerEntity p) {
        if (!Ac.running() || Ac.get().watcher == null || !effectOn(WatcherEffect.SLEEP_WELL) || !eligibility(p).allowed()) {
            return;
        }
        sleepWell(p);
        log(p, WatcherEffect.SLEEP_WELL, "bed");
    }

    private static void onWake(ServerPlayerEntity p, BlockPos bed) {
        if (!Ac.running() || Ac.get().watcher == null || ACTIVE.containsKey(p.getUuid()) || !effectOn(WatcherEffect.BEDSIDE)
                || p.isDead() || !eligibility(p).allowed() || !sched().roll(cfg().bedsideChance)) {
            return;
        }
        Effect e = Bedside.create(p, bed);
        if (e != null) {
            ACTIVE.put(p.getUuid(), e);
            sched().begin(p.getUuid());
            log(p, WatcherEffect.BEDSIDE, "bed");
        }
    }

    // ---- chest note (33.5 gifts) ----

    private static void chestNotes(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Long>> it = CHEST_NOTES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> en = it.next();
            if (now > en.getValue()) {
                it.remove();
                continue;
            }
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p == null || !(p.currentScreenHandler instanceof GenericContainerScreenHandler h) || h instanceof MenuHandler) {
                continue;
            }
            Inventory inv = h.getInventory();
            boolean realChest = inv instanceof ChestBlockEntity || inv instanceof BarrelBlockEntity || inv instanceof DoubleInventory
                    || inv instanceof EnderChestInventory;
            var claim = Ac.get().claims.at(Mc.worldId(p.getEntityWorld()), p.getX(), p.getZ());
            boolean own = inv instanceof EnderChestInventory || claim == null || p.getUuid().equals(claim.createdBy);
            WatcherEligibility el = eligibility(p);
            el.menuOpen = false;
            if (!realChest || !own || !el.allowed()) {
                continue;
            }
            for (int slot = 0; slot < inv.size(); slot++) {
                if (inv.getStack(slot).isEmpty()) {
                    // Client-side only: it vanishes when the chest closes, and any click makes the server resync.
                    send(p, new ScreenHandlerSlotUpdateS2CPacket(h.syncId, h.getRevision(), slot, fakePaper(cfg().chestNoteName)));
                    break;
                }
            }
            it.remove();
        }
    }

    // ---- spots ----

    private static boolean passable(ServerWorld w, BlockPos pos) {
        return w.getBlockState(pos).getCollisionShape(w, pos).isEmpty() && w.getFluidState(pos).isEmpty();
    }

    /** Standing spot at (x, z) near the given height, or null. */
    private static BlockPos ground(ServerWorld w, int x, int y0, int z) {
        for (int dy = 6; dy >= -10; dy--) {
            BlockPos pos = new BlockPos(x, y0 + dy, z);
            BlockPos below = pos.down();
            if (w.getBlockState(below).isSideSolidFullSquare(w, below, Direction.UP) && passable(w, pos) && passable(w, pos.up())) {
                return pos;
            }
        }
        return null;
    }

    private static boolean visible(ServerPlayerEntity p, Vec3d from, Vec3d to) {
        return p.getEntityWorld().raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.MISS;
    }

    /** The darkest visible standing spot at the edge of the player's view (33.2), or null. */
    static Vec3d findSpot(ServerPlayerEntity p, double min, double max) {
        ServerWorld w = p.getEntityWorld();
        Vec3d eye = p.getEyePos();
        Vec3d best = null;
        int bestLight = Integer.MAX_VALUE;
        for (double[] c : sched().spotCandidates(p.getYaw(), 12, min, max)) {
            int x = MathHelper.floor(p.getX() + c[0]);
            int z = MathHelper.floor(p.getZ() + c[1]);
            if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            BlockPos feet = ground(w, x, MathHelper.floor(p.getY()), z);
            if (feet == null) {
                continue;
            }
            Vec3d foot = new Vec3d(x + 0.5, feet.getY(), z + 0.5);
            if (!visible(p, eye, foot.add(0, 1.8, 0)) && !visible(p, eye, foot.add(0, 1.0, 0))) {
                continue;
            }
            int light = w.getLightLevel(feet.up());
            if (light < bestLight) {
                bestLight = light;
                best = foot;
            }
        }
        return best;
    }

    private static Vec3d look(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(r), 0, Math.cos(r));
    }

    private static com.vylorq.anticheat.core.util.Vec3 v(Vec3d d) {
        return Mc.vec(d);
    }

    // =====================================================================================================
    // Effects
    // =====================================================================================================

    abstract static class Effect {
        final ServerPlayerEntity p;
        final WatcherEffect type;
        final String world;
        Vec3d lastPos;
        int age;
        boolean forced;
        boolean allowMenus;
        boolean touchesInventory;
        private boolean stopped;

        Effect(ServerPlayerEntity p, WatcherEffect type) {
            this.p = p;
            this.type = type;
            this.world = Mc.worldId(p.getEntityWorld());
        }

        /** @return false when finished */
        abstract boolean tick();

        /** Undo everything sent. {@code worldGone}: the client dropped its world (left, respawned, changed dimension). */
        void revert(boolean worldGone) {
        }

        final void stop(boolean worldGone) {
            if (!stopped) {
                stopped = true;
                revert(worldGone);
            }
        }
    }

    static final class Instant extends Effect {
        private final Runnable action;

        Instant(ServerPlayerEntity p, WatcherEffect type, Runnable action) {
            super(p, type);
            this.action = action;
        }

        @Override
        boolean tick() {
            action.run();
            return false;
        }
    }

    /** Watcher or doppelgänger standing far away, staring (33.2). */
    static final class Appear extends Effect {
        final WatcherFigure fig;
        final boolean doppel;
        final Gaze gaze = new Gaze();
        boolean shown;

        private Appear(ServerPlayerEntity p, boolean doppel, WatcherFigure fig) {
            super(p, doppel ? WatcherEffect.DOPPELGANGER : WatcherEffect.APPEAR);
            this.doppel = doppel;
            this.fig = fig;
        }

        static Appear create(ServerPlayerEntity p, boolean doppel) {
            Vec3d spot = findSpot(p, 20, 40);
            if (spot == null) {
                return null;
            }
            WatcherFigure fig = doppel ? WatcherFigure.doppelganger(p) : WatcherFigure.watcher(p.getEntityWorld());
            fig.at(spot, 0, 0);
            return new Appear(p, doppel, fig);
        }

        @Override
        boolean tick() {
            Vec3d eye = p.getEyePos();
            if (!shown) {
                fig.show(p);
                shown = true;
            }
            if (age % 2 == 0) {
                fig.lookAt(p, eye);
            }
            if (age % 60 == 10) {
                if (doppel) {
                    heartbeat(p);
                } else {
                    breathe(p, fig.head());
                }
            }
            if (gaze.tick(v(eye), p.getYaw(), p.getPitch(), v(fig.head()))) {
                fig.hide(p);
                shown = false;
                darknessPulse(p);
                if (!doppel && sched().messageAllowed(p.getUuid()) && effectOn(WatcherEffect.MESSAGE) && sched().roll(0.35)) {
                    watchingText(p, sched().glitch(settings()));
                }
                return false;
            }
            return age < 600 && eye.distanceTo(fig.head()) < 80;
        }

        @Override
        void revert(boolean worldGone) {
            if (shown) {
                fig.hide(p);
                shown = false;
            }
        }
    }

    /** Slow steps following the player; nothing there when they turn. */
    static final class Footsteps extends Effect {
        int steps;

        Footsteps(ServerPlayerEntity p) {
            super(p, WatcherEffect.FOOTSTEPS);
        }

        @Override
        boolean tick() {
            Vec3d behind = p.getEntityPos().subtract(look(p.getYaw()).multiply(3));
            if (Gaze.angleTo(v(p.getEyePos()), p.getYaw(), p.getPitch(), v(behind.add(0, 1, 0))) < 70) {
                return false;
            }
            if (age % 12 == 0) {
                BlockState under = p.getEntityWorld().getBlockState(BlockPos.ofFloored(behind).down());
                SoundEvent step = under.isAir() ? SoundEvents.BLOCK_STONE_STEP : under.getSoundGroup().getStepSound();
                sound(p, step, SoundCategory.HOSTILE, behind, 0.35f, 0.8f);
                steps++;
            }
            return steps < 8;
        }
    }

    /** Right behind the player for a split second; gone the instant they turn. */
    static final class TurnAround extends Effect {
        final WatcherFigure fig;
        boolean shown;

        private TurnAround(ServerPlayerEntity p, WatcherFigure fig) {
            super(p, WatcherEffect.TURN_AROUND);
            this.fig = fig;
        }

        static TurnAround create(ServerPlayerEntity p) {
            Vec3d at = p.getEntityPos().subtract(look(p.getYaw()).multiply(2.2));
            BlockPos b = BlockPos.ofFloored(at);
            ServerWorld w = p.getEntityWorld();
            if (!passable(w, b) || !passable(w, b.up())) {
                return null;
            }
            return new TurnAround(p, WatcherFigure.watcher(w).at(at, p.getYaw(), 0));
        }

        @Override
        boolean tick() {
            if (!shown) {
                fig.show(p);
                shown = true;
                breathe(p, fig.head());
            }
            fig.lookAt(p, p.getEyePos());
            if (Gaze.angleTo(v(p.getEyePos()), p.getYaw(), p.getPitch(), v(fig.head())) < 75) {
                fig.hide(p);
                shown = false;
                darknessPulse(p);
                return false;
            }
            return age < 100;
        }

        @Override
        void revert(boolean worldGone) {
            if (shown) {
                fig.hide(p);
                shown = false;
            }
        }
    }

    /** Far away, a mirror image: moves and turns as the player does. */
    static final class Mirroring extends Effect {
        final WatcherFigure fig;
        final Vec3d spot;
        final Vec3d start;
        final Vec3d axis;
        final Gaze gaze = new Gaze();
        boolean shown;
        Vec3d lastPlayer;
        float lastYaw;
        float lastPitch;

        private Mirroring(ServerPlayerEntity p, Vec3d spot) {
            super(p, WatcherEffect.MIRRORING);
            this.spot = spot;
            this.start = p.getEntityPos();
            Vec3d d = new Vec3d(spot.x - start.x, 0, spot.z - start.z);
            this.axis = d.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : d.normalize();
            this.fig = WatcherFigure.watcher(p.getEntityWorld()).at(spot, 0, 0);
        }

        static Mirroring create(ServerPlayerEntity p) {
            Vec3d spot = findSpot(p, 20, 35);
            return spot == null ? null : new Mirroring(p, spot);
        }

        private Vec3d reflect(Vec3d d) {
            double k = d.x * axis.x + d.z * axis.z;
            return new Vec3d(d.x - 2 * k * axis.x, d.y, d.z - 2 * k * axis.z);
        }

        @Override
        boolean tick() {
            Vec3d pos = p.getEntityPos();
            if (!shown) {
                fig.show(p);
                shown = true;
            }
            if (lastPlayer == null || !lastPlayer.equals(pos) || lastYaw != p.getYaw() || lastPitch != p.getPitch()) {
                lastPlayer = pos;
                lastYaw = p.getYaw();
                lastPitch = p.getPitch();
                Vec3d to = spot.add(reflect(pos.subtract(start)));
                Vec3d l = reflect(look(p.getYaw()));
                float yaw = (float) (Math.toDegrees(Math.atan2(l.z, l.x)) - 90.0);
                fig.moveTo(p, to, yaw, p.getPitch());
            }
            if (gaze.tick(v(p.getEyePos()), p.getYaw(), p.getPitch(), v(fig.head()))) {
                fig.hide(p);
                shown = false;
                darknessPulse(p);
                return false;
            }
            return age < 400;
        }

        @Override
        void revert(boolean worldGone) {
            if (shown) {
                fig.hide(p);
                shown = false;
            }
        }
    }

    /** Nearby torches and lanterns seem to go out for a moment (fake block updates to this player only). */
    static final class Flicker extends Effect {
        final List<BlockPos> lights;

        private Flicker(ServerPlayerEntity p, List<BlockPos> lights) {
            super(p, WatcherEffect.FLICKER);
            this.lights = lights;
        }

        static boolean isLight(BlockState s) {
            String path = Registries.BLOCK.getId(s.getBlock()).getPath();
            if (path.contains("redstone")) {
                return false;
            }
            return path.endsWith("torch") || (path.endsWith("lantern") && !path.equals("jack_o_lantern") && !path.equals("sea_lantern"));
        }

        static Flicker create(ServerPlayerEntity p) {
            ServerWorld w = p.getEntityWorld();
            BlockPos c = p.getBlockPos();
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos pos : BlockPos.iterate(c.add(-10, -4, -10), c.add(10, 6, 10))) {
                if (isLight(w.getBlockState(pos))) {
                    found.add(pos.toImmutable());
                    if (found.size() >= 24) {
                        break;
                    }
                }
            }
            return found.isEmpty() ? null : new Flicker(p, found);
        }

        private void set(boolean off) {
            ServerWorld w = p.getEntityWorld();
            for (BlockPos pos : lights) {
                send(p, off ? new BlockUpdateS2CPacket(pos, Blocks.AIR.getDefaultState()) : new BlockUpdateS2CPacket(w, pos));
            }
        }

        @Override
        boolean tick() {
            switch (age) {
                case 0, 10, 30 -> set(true);
                case 6, 14, 60 -> set(false);
                default -> {
                }
            }
            return age < 60;
        }

        @Override
        void revert(boolean worldGone) {
            if (!worldGone) {
                set(false);
            }
        }
    }

    /** A fake sign saying "I SEE YOU" on a nearby wall, gone when approached. */
    static final class Sign extends Effect {
        final BlockPos pos;
        final BlockState state;
        boolean placed;

        private Sign(ServerPlayerEntity p, BlockPos pos, BlockState state) {
            super(p, WatcherEffect.SIGN);
            this.pos = pos;
            this.state = state;
        }

        static Sign create(ServerPlayerEntity p) {
            ServerWorld w = p.getEntityWorld();
            BlockPos base = p.getBlockPos().up();
            Direction facing = p.getHorizontalFacing();
            Direction side = facing.rotateYClockwise();
            for (int d = 4; d <= 10; d++) {
                for (int s : new int[]{0, 1, -1, 2, -2}) {
                    BlockPos pos = base.offset(facing, d).offset(side, s);
                    BlockPos wall = pos.offset(facing);
                    if (w.getBlockState(pos).isAir() && w.getBlockState(wall).isSideSolidFullSquare(w, wall, facing.getOpposite())
                            && visible(p, p.getEyePos(), Vec3d.ofCenter(pos))) {
                        return new Sign(p, pos, Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, facing.getOpposite()));
                    }
                }
            }
            return null;
        }

        @Override
        boolean tick() {
            if (!placed) {
                placed = true;
                ServerWorld w = p.getEntityWorld();
                send(p, new BlockUpdateS2CPacket(pos, state));
                SignText text = new SignText();
                List<String> lines = cfg().signLines;
                for (int i = 0; i < 4; i++) {
                    text = text.withMessage(i, Text.literal(i < lines.size() && lines.get(i) != null ? lines.get(i) : ""));
                }
                SignText t = text;
                SignBlockEntity be = new SignBlockEntity(pos, state);
                be.setWorld(w);
                send(p, BlockEntityUpdateS2CPacket.create(be, (b, registries) -> {
                    NbtCompound nbt = new NbtCompound();
                    var ops = registries.getOps(NbtOps.INSTANCE);
                    nbt.put("front_text", SignText.CODEC.encodeStart(ops, t).getOrThrow());
                    nbt.put("back_text", SignText.CODEC.encodeStart(ops, new SignText()).getOrThrow());
                    nbt.putBoolean("is_waxed", true);
                    return nbt;
                }));
            }
            return age < 1200 && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > 4.5 * 4.5;
        }

        @Override
        void revert(boolean worldGone) {
            if (placed && !worldGone) {
                send(p, new BlockUpdateS2CPacket(p.getEntityWorld(), pos));
            }
        }
    }

    /** All music and ambience stops for ~10 seconds, then the breathing. */
    static final class Silence extends Effect {
        Silence(ServerPlayerEntity p) {
            super(p, WatcherEffect.SILENCE);
        }

        @Override
        boolean tick() {
            if (age <= 200 && age % 20 == 0) {
                send(p, new StopSoundS2CPacket(null, null));
            }
            if (age == 210 || age == 250) {
                breathe(p, p.getEyePos().subtract(look(p.getYaw()).multiply(3)));
            }
            return age < 260;
        }
    }

    /** Slow knocking on a nearby door (or the wall behind the player). */
    static final class Knocking extends Effect {
        final Vec3d at;

        Knocking(ServerPlayerEntity p) {
            super(p, WatcherEffect.KNOCKING);
            ServerWorld w = p.getEntityWorld();
            BlockPos c = p.getBlockPos();
            Vec3d door = null;
            for (BlockPos pos : BlockPos.iterate(c.add(-8, -3, -8), c.add(8, 3, 8))) {
                if (w.getBlockState(pos).getBlock() instanceof DoorBlock) {
                    door = Vec3d.ofCenter(pos);
                    break;
                }
            }
            this.at = door != null ? door : p.getEyePos().subtract(look(p.getYaw()).multiply(4));
        }

        @Override
        boolean tick() {
            switch (age) {
                case 0, 9, 18, 70, 79 -> sound(p, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.BLOCKS, at, 0.35f, 0.7f);
                default -> {
                }
            }
            return age < 80;
        }
    }

    /** Nearby animals all turn their heads toward one spot (rotation packets to this player only). */
    static final class AnimalsStare extends Effect {
        final List<AnimalEntity> animals;
        final Vec3d target;

        private AnimalsStare(ServerPlayerEntity p, List<AnimalEntity> animals, Vec3d target) {
            super(p, WatcherEffect.ANIMALS_STARE);
            this.animals = animals;
            this.target = target;
        }

        static AnimalsStare create(ServerPlayerEntity p) {
            List<AnimalEntity> list = p.getEntityWorld().getEntitiesByClass(AnimalEntity.class, p.getBoundingBox().expand(24), a -> a.isAlive());
            if (list.isEmpty()) {
                return null;
            }
            if (list.size() > 10) {
                list = new ArrayList<>(list.subList(0, 10));
            }
            Vec3d spot = findSpot(p, 20, 40);
            Vec3d target = spot != null ? spot.add(0, 1.8, 0) : p.getEyePos().subtract(look(p.getYaw()).multiply(25));
            return new AnimalsStare(p, list, target);
        }

        private static byte angle(float deg) {
            return (byte) MathHelper.floor(deg * 256.0F / 360.0F);
        }

        @Override
        boolean tick() {
            if (age % 2 == 0) {
                for (AnimalEntity a : animals) {
                    if (a.isRemoved()) {
                        continue;
                    }
                    Vec3d e = a.getEyePos();
                    double dx = target.x - e.x;
                    double dz = target.z - e.z;
                    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    float pitch = (float) -Math.toDegrees(Math.atan2(target.y - e.y, Math.sqrt(dx * dx + dz * dz)));
                    send(p, new EntitySetHeadYawS2CPacket(a, angle(yaw)));
                    send(p, new EntityS2CPacket.Rotate(a.getId(), angle(yaw), angle(pitch), a.isOnGround()));
                }
            }
            return age < 120;
        }

        @Override
        void revert(boolean worldGone) {
            if (worldGone) {
                return;
            }
            for (AnimalEntity a : animals) {
                if (!a.isRemoved()) {
                    send(p, new EntitySetHeadYawS2CPacket(a, angle(a.getHeadYaw())));
                    send(p, new EntityS2CPacket.Rotate(a.getId(), angle(a.getYaw()), angle(a.getPitch()), a.isOnGround()));
                }
            }
        }
    }

    /** Rain and distant thunder for this player only, ending suddenly. */
    static final class Storm extends Effect {
        private Storm(ServerPlayerEntity p) {
            super(p, WatcherEffect.STORM);
        }

        static Storm create(ServerPlayerEntity p) {
            ServerWorld w = p.getEntityWorld();
            if (!w.getDimension().hasSkyLight() || w.isRaining()) {
                return null;
            }
            return new Storm(p);
        }

        @Override
        boolean tick() {
            if (age == 0) {
                send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STARTED, 0));
            }
            if (age <= 40) {
                send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, age / 40f));
                send(p, new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, age / 40f * 0.8f));
            }
            if (age == 60 || age == 230 || age == 330) {
                double a = sched().random().nextDouble() * Math.PI * 2;
                Vec3d at = p.getEyePos().add(Math.cos(a) * 60, 10, Math.sin(a) * 60);
                sound(p, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, at, 8f, 0.6f + sched().random().nextFloat() * 0.2f);
            }
            return age < 400;
        }

        @Override
        void revert(boolean worldGone) {
            sendWeather(p, p.getEntityWorld());
        }
    }

    /** The compass briefly points at the Watcher. */
    static final class WrongCompass extends Effect {
        final BlockPos target;

        private WrongCompass(ServerPlayerEntity p, BlockPos target) {
            super(p, WatcherEffect.WRONG_COMPASS);
            this.target = target;
        }

        static WrongCompass create(ServerPlayerEntity p) {
            boolean hasCompass = false;
            for (int i = 0; i < p.getInventory().size(); i++) {
                if (p.getInventory().getStack(i).isOf(Items.COMPASS)) {
                    hasCompass = true;
                    break;
                }
            }
            if (!hasCompass) {
                return null;
            }
            Vec3d spot = findSpot(p, 20, 40);
            if (spot == null) {
                spot = p.getEntityPos().subtract(look(p.getYaw()).multiply(30));
            }
            return new WrongCompass(p, BlockPos.ofFloored(spot));
        }

        @Override
        boolean tick() {
            if (age == 0) {
                send(p, new PlayerSpawnPositionS2CPacket(WorldProperties.SpawnPoint.create(p.getEntityWorld().getRegistryKey(), target, 0f, 0f)));
            }
            return age < 400;
        }

        @Override
        void revert(boolean worldGone) {
            send(p, new PlayerSpawnPositionS2CPacket(Ac.server().getSpawnPoint()));
        }
    }

    /** Pocket gift (fake slot for 60 s) or a chest note waiting for the next chest they open. */
    static final class Gift extends Effect {
        final int slot;
        boolean shown;

        private Gift(ServerPlayerEntity p, int slot) {
            super(p, WatcherEffect.GIFT);
            this.slot = slot;
            this.allowMenus = true;
            this.touchesInventory = true;
        }

        static Effect create(ServerPlayerEntity p) {
            int free = -1;
            for (int i = 9; i < 36; i++) {
                if (p.getInventory().getStack(i).isEmpty()) {
                    free = i;
                    break;
                }
            }
            if (free < 0 || sched().random().nextBoolean()) {
                return new Instant(p, WatcherEffect.GIFT,
                        () -> CHEST_NOTES.put(p.getUuid(), System.currentTimeMillis() + 30 * Durations.MINUTE));
            }
            return new Gift(p, free);
        }

        @Override
        boolean tick() {
            if (!shown) {
                shown = true;
                // Player inventory slots 9-35 have the same index in the player's own screen handler.
                send(p, new ScreenHandlerSlotUpdateS2CPacket(0, p.playerScreenHandler.getRevision(), slot, fakePaper(cfg().pocketGiftName)));
            }
            return age < 1200;
        }

        @Override
        void revert(boolean worldGone) {
            if (shown) {
                send(p, new ScreenHandlerSlotUpdateS2CPacket(0, p.playerScreenHandler.getRevision(), slot,
                        p.playerScreenHandler.getSlot(slot).getStack().copy()));
            }
        }
    }

    /** "▒▒▒▒▒ joined the game", then left, for one player only. */
    static final class FakeJoin extends Effect {
        static final String NAME = "▒▒▒▒▒";

        FakeJoin(ServerPlayerEntity p) {
            super(p, WatcherEffect.FAKE_JOIN);
        }

        @Override
        boolean tick() {
            if (age == 0) {
                p.sendMessage(Text.translatable("multiplayer.player.joined", NAME).formatted(Formatting.GRAY));
            } else if (age == 90) {
                p.sendMessage(Text.translatable("multiplayer.player.left", NAME).formatted(Formatting.GRAY));
                return false;
            }
            return true;
        }

        @Override
        void revert(boolean worldGone) {
            if (age > 0 && age <= 90 && !p.isRemoved()) {
                p.sendMessage(Text.translatable("multiplayer.player.left", NAME).formatted(Formatting.GRAY));
            }
        }
    }

    /** Runs at the player and vanishes just before reaching them (off by default). */
    static final class Rush extends Effect {
        final WatcherFigure fig;
        boolean shown;

        private Rush(ServerPlayerEntity p, WatcherFigure fig) {
            super(p, WatcherEffect.RUSH);
            this.fig = fig;
        }

        static Rush create(ServerPlayerEntity p) {
            Vec3d spot = findSpot(p, 22, 30);
            return spot == null ? null : new Rush(p, WatcherFigure.watcher(p.getEntityWorld()).at(spot, 0, 0));
        }

        @Override
        boolean tick() {
            if (!shown) {
                fig.show(p);
                shown = true;
            }
            Vec3d target = p.getEntityPos();
            Vec3d d = target.subtract(fig.pos);
            double dist = Math.sqrt(d.x * d.x + d.z * d.z);
            if (dist < 3) {
                fig.hide(p);
                shown = false;
                darknessPulse(p);
                return false;
            }
            if (age < 20) {
                fig.lookAt(p, p.getEyePos());
                return true;
            }
            double speed = Math.min(0.6, dist);
            Vec3d next = fig.pos.add(d.x / dist * speed, (target.y - fig.pos.y) * 0.1, d.z / dist * speed);
            float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
            fig.moveTo(p, next, yaw, 0);
            if (age % 5 == 0) {
                sound(p, SoundEvents.BLOCK_STONE_STEP, SoundCategory.HOSTILE, next, 0.5f, 0.7f);
            }
            return age < 200;
        }

        @Override
        void revert(boolean worldGone) {
            if (shown) {
                fig.hide(p);
                shown = false;
            }
        }
    }

    /** Standing next to the bed for a split second on waking (rare). */
    static final class Bedside extends Effect {
        final WatcherFigure fig;
        boolean shown;

        private Bedside(ServerPlayerEntity p, WatcherFigure fig) {
            super(p, WatcherEffect.BEDSIDE);
            this.fig = fig;
        }

        static Bedside create(ServerPlayerEntity p, BlockPos bed) {
            ServerWorld w = p.getEntityWorld();
            for (Direction d : Direction.Type.HORIZONTAL) {
                for (int dist = 1; dist <= 2; dist++) {
                    BlockPos at = bed.offset(d, dist);
                    if (passable(w, at) && passable(w, at.up()) && !passable(w, at.down())) {
                        return new Bedside(p, WatcherFigure.watcher(w).at(Vec3d.ofBottomCenter(at), 0, 0));
                    }
                }
            }
            return null;
        }

        @Override
        boolean tick() {
            if (!shown) {
                fig.show(p);
                shown = true;
            }
            fig.lookAt(p, p.getEyePos());
            if (age >= 8) {
                fig.hide(p);
                shown = false;
                return false;
            }
            return true;
        }

        @Override
        void revert(boolean worldGone) {
            if (shown) {
                fig.hide(p);
                shown = false;
            }
        }
    }

    // ---- test support ----

    /**
     * Runs one effect to completion (or for {@code maxTicks}) for a player outside the normal schedule, then reverts
     * it. Used by the game tests with a fake player.
     *
     * @return false if the effect couldn't start there
     */
    public static boolean runForTest(ServerPlayerEntity p, WatcherEffect eff, int maxTicks) {
        Effect e = create(p, eff);
        if (e == null) {
            return false;
        }
        e.forced = true;
        for (int i = 0; i < maxTicks; i++) {
            boolean more = e.tick();
            e.age++;
            if (!more) {
                break;
            }
        }
        e.stop(false);
        return true;
    }
}
