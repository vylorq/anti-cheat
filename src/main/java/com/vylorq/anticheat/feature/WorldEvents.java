package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.watcher.WatcherEffect;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Server-wide events, started by hand ({@code /events start <id>}) or on a schedule.
 * <ul>
 * <li><b>Scary</b> (only for players who accepted the scare warning): Blood Moon (every few days at nightfall),
 * Lockdown (everyone locked in the Locked Box).</li>
 * <li><b>Good</b>: Golden Hour (haste, speed, regeneration).</li>
 * </ul>
 * One event at a time. Every effect of the scary ones is fake and per player, like the Watcher; the good ones give
 * real effects and items.
 */
public final class WorldEvents {
    private WorldEvents() {
    }

    public enum Kind {
        BLOOD_MOON(true, 600, BossBar.Color.RED),
        LOCKDOWN(true, 300, BossBar.Color.RED),
        GOLDEN_HOUR(false, 600, BossBar.Color.YELLOW);

        public final boolean scary;
        public final int seconds;
        final BossBar.Color color;

        Kind(boolean scary, int seconds, BossBar.Color color) {
            this.scary = scary;
            this.seconds = seconds;
            this.color = color;
        }

        public String id() {
            return name().toLowerCase();
        }

        public static Kind byId(String id) {
            for (Kind k : values()) {
                if (k.id().equalsIgnoreCase(id) || k.id().replace("_", "").equalsIgnoreCase(id)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** Saved between restarts. */
    public static final class State {
        public long lastBloodMoonDay = -1;
        public long nextRandomAt;
    }

    private static final Random RANDOM = new Random();
    private static Kind active;
    private static int elapsed;
    private static ServerBossBar bar;
    private static String startedBy;
    private static boolean tempLockBox;

    private static AcConfig.Events cfg() {
        return Ac.config().events;
    }

    private static State state() {
        return Ac.get().misc.events;
    }

    public static Kind active() {
        return active;
    }

    public static int secondsLeft() {
        return active == null ? 0 : Math.max(0, active.seconds - elapsed);
    }

    private static List<ServerPlayerEntity> players() {
        return new ArrayList<>(Ac.server().getPlayerManager().getPlayerList());
    }

    /** Players the scary parts may reach (accepted the warning). */
    private static List<ServerPlayerEntity> brave() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : players()) {
            if (ScareWarning.accepted(p.getUuid()) && !ScareWarning.pending(p)) {
                out.add(p);
            }
        }
        return out;
    }

    public static void register() {
        // Nothing to hook yet (kept for future events).
    }

    // ---------------------------------------------------------------- start / stop

    /** @return a lang key for the error, or null when it started */
    public static String start(Kind k, String by) {
        if (!Ac.running()) {
            return "events.not-running";
        }
        if (active != null) {
            return "events.already";
        }
        if ((k == Kind.BLOOD_MOON || k == Kind.LOCKDOWN) && brave().isEmpty()) {
            return "events.nobody";
        }
        active = k;
        elapsed = 0;
        startedBy = by;
        bar = new ServerBossBar(Text.literal(title(k)), k.color, BossBar.Style.NOTCHED_10);
        for (ServerPlayerEntity p : players()) {
            if (!k.scary || brave().contains(p)) {
                bar.addPlayer(p);
            }
        }
        announce(k, true);
        switch (k) {
            case BLOOD_MOON -> {
                state().lastBloodMoonDay = day();
                Ac.markDirty("misc");
                run("time set night");
                for (ServerPlayerEntity p : brave()) {
                    fakeStorm(p, true);
                }
            }
            case LOCKDOWN -> startLockdown();
            default -> {
            }
        }
        Ac.LOG.info("Event {} started by {}", k.id(), by);
        return null;
    }

    public static void stop(String why) {
        Kind k = active;
        if (k == null) {
            return;
        }
        active = null;
        if (bar != null) {
            bar.clearPlayers();
            bar = null;
        }
        switch (k) {
            case BLOOD_MOON -> {
                for (ServerPlayerEntity p : players()) {
                    fakeStorm(p, false);
                }
                if (!"stopped".equals(why)) {
                    for (ServerPlayerEntity p : brave()) {
                        give(p, new ItemStack(Items.GOLDEN_APPLE));
                    }
                }
            }
            case LOCKDOWN -> {
                if (tempLockBox) {
                    LockedBox.remove(null);
                    tempLockBox = false;
                }
            }
            default -> {
            }
        }
        announce(k, false);
        Ac.LOG.info("Event {} ended ({})", k.id(), why);
    }

    private static String title(Kind k) {
        return Msg.tr("events." + k.id());
    }

    private static void announce(Kind k, boolean start) {
        for (ServerPlayerEntity p : players()) {
            if (k.scary && !brave().contains(p)) {
                continue;
            }
            String t = Msg.trFor(p, "events." + k.id());
            String sub = Msg.trFor(p, "events." + k.id() + (start ? ".start" : ".end"));
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal(sub)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal((k.scary ? "§4" : "§6") + t)));
            p.sendMessage(Msg.prefixed((k.scary ? "§c" : "§6") + t + " §7" + sub));
            sound(p, k.scary ? SoundEvents.ENTITY_WITHER_SPAWN : SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, p.getEyePos(), 0.6f,
                    k.scary ? 0.5f : 1.2f);
        }
    }

    // ---------------------------------------------------------------- every second

    public static void tick(MinecraftServer server) {
        if (!Ac.running()) {
            return;
        }
        if (active == null) {
            schedule(server);
            return;
        }
        elapsed++;
        if (bar != null) {
            bar.setPercent(Math.max(0f, 1f - (float) elapsed / active.seconds));
            for (ServerPlayerEntity p : players()) {
                if (!bar.getPlayers().contains(p) && (!active.scary || brave().contains(p))) {
                    bar.addPlayer(p);
                }
            }
        }
        switch (active) {
            case BLOOD_MOON -> bloodMoon();
            case LOCKDOWN -> lockdown();
            case GOLDEN_HOUR -> goldenHour();
        }
        if (active != null && elapsed >= active.seconds) {
            stop("time");
        }
    }

    private static long day() {
        return Ac.server().getOverworld().getTimeOfDay() / 24000L;
    }

    private static void schedule(MinecraftServer server) {
        AcConfig.Events c = cfg();
        if (!c.enabled) {
            return;
        }
        ServerWorld ow = server.getOverworld();
        long tod = ow.getTimeOfDay() % 24000L;
        long d = day();
        if (c.bloodMoon && c.bloodMoonEveryDays > 0 && d % c.bloodMoonEveryDays == 0 && d != state().lastBloodMoonDay
                && tod >= 13000 && tod < 14000 && !brave().isEmpty()) {
            start(Kind.BLOOD_MOON, "schedule");
            return;
        }
        long now = System.currentTimeMillis();
        if (state().nextRandomAt == 0) {
            planNext(now);
        }
        if (now >= state().nextRandomAt) {
            planNext(now);
            if (players().size() >= Math.max(1, c.minPlayers)) {
                List<Kind> pool = new ArrayList<>();
                for (String id : c.randomEvents) {
                    Kind k = Kind.byId(id);
                    if (k != null && k != Kind.BLOOD_MOON && k != Kind.LOCKDOWN && (!k.scary || !brave().isEmpty())) {
                        pool.add(k);
                    }
                }
                if (!pool.isEmpty()) {
                    start(pool.get(RANDOM.nextInt(pool.size())), "schedule");
                }
            }
        }
    }

    private static void planNext(long now) {
        int min = Math.max(10, cfg().randomMinMinutes);
        int max = Math.max(min, cfg().randomMaxMinutes);
        state().nextRandomAt = now + (min + RANDOM.nextInt(max - min + 1)) * 60_000L;
        Ac.markDirty("misc");
    }

    // ---------------------------------------------------------------- scary

    private static final WatcherEffect[] SCARES = {WatcherEffect.APPEAR, WatcherEffect.CLOSER, WatcherEffect.JUMPSCARE,
            WatcherEffect.BEHIND_YOU, WatcherEffect.FOOTSTEPS, WatcherEffect.TURN_AROUND, WatcherEffect.BLACKOUT};

    private static void scare(ServerPlayerEntity p, WatcherEffect[] pool) {
        if (Watcher.busy(p.getUuid())) {
            return;
        }
        Watcher.start(p, pool[RANDOM.nextInt(pool.length)], "event:" + (active == null ? "" : active.id()), false);
    }

    private static void distantScream(ServerPlayerEntity p) {
        double a = RANDOM.nextDouble() * Math.PI * 2;
        Vec3d at = p.getEyePos().add(Math.cos(a) * 30, 0, Math.sin(a) * 30);
        SoundEvent s = RANDOM.nextBoolean() ? SoundEvents.ENTITY_GHAST_SCREAM : SoundEvents.ENTITY_ENDERMAN_SCREAM;
        sound(p, s, at, 0.9f, 0.4f + RANDOM.nextFloat() * 0.3f);
    }

    private static void bloodMoon() {
        for (ServerPlayerEntity p : brave()) {
            if (RANDOM.nextInt(25) == 0) {
                distantScream(p);
            }
            if (RANDOM.nextInt(90) == 0) {
                scare(p, SCARES);
            }
            if (elapsed % 30 == 0) {
                fakeStorm(p, true);
            }
        }
    }

    private static void startLockdown() {
        if (LockedBox.get() == null) {
            ServerWorld w = Ac.server().getOverworld();
            BlockPos s = Mc.worldSpawn(Ac.server());
            Area a = new Area(Mc.worldId(w), s.getX() - 15, s.getY() - 1, s.getZ() - 15, s.getX() + 15, s.getY() + 20, s.getZ() + 15);
            LockedBox.create(null, w, a);
            tempLockBox = true;
        } else {
            LockedBox.create(null, Mc.world(Ac.server(), LockedBox.get().world), boxArea());
        }
    }

    private static Area boxArea() {
        var b = LockedBox.get();
        return new Area(b.world, (int) b.minX, (int) b.minY, (int) b.minZ, (int) b.maxX, (int) b.maxY, (int) b.maxZ);
    }

    private static void lockdown() {
        for (ServerPlayerEntity p : brave()) {
            if (elapsed % 3 == 0 && elapsed < 30) {
                sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, p.getEyePos(), 0.8f, 0.6f);
            }
            if (RANDOM.nextInt(70) == 0) {
                scare(p, SCARES);
            }
            if (RANDOM.nextInt(40) == 0) {
                distantScream(p);
            }
        }
    }

    // ---------------------------------------------------------------- good

    private static void goldenHour() {
        if (elapsed % 20 != 1) {
            return;
        }
        for (ServerPlayerEntity p : players()) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 30 * 20, 1, true, true));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 30 * 20, 0, true, true));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 30 * 20, 0, true, true));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 30 * 20, 0, true, true));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void give(ServerPlayerEntity p, ItemStack s) {
        if (!p.getInventory().insertStack(s)) {
            p.dropItem(s, false);
        }
    }

    private static void sound(ServerPlayerEntity p, SoundEvent s, Vec3d at, float vol, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(s), SoundCategory.HOSTILE,
                at.x, at.y, at.z, vol, pitch, RANDOM.nextLong()));
    }

    private static void sound(ServerPlayerEntity p, net.minecraft.registry.entry.RegistryEntry<SoundEvent> s, Vec3d at, float vol, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(s, SoundCategory.HOSTILE, at.x, at.y, at.z, vol, pitch, RANDOM.nextLong()));
    }

    /** A thunderstorm only this player sees (or their real weather back). */
    private static void fakeStorm(ServerPlayerEntity p, boolean on) {
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        if (on) {
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STARTED, 0));
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, 1));
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, 1));
        } else if (w.isRaining()) {
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, w.getRainGradient(1f)));
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, w.getThunderGradient(1f)));
        } else {
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STOPPED, 0));
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, 0));
            p.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, 0));
        }
    }

    private static void run(String command) {
        MinecraftServer s = Ac.server();
        s.getCommandManager().parseAndExecute(s.getCommandSource().withSilent(), command);
    }
}
