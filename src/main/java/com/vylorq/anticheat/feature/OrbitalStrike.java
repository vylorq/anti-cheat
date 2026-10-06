package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.TntEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The owner's orbital strike cannon: TNT raining from the sky in a chosen pattern. No warning to anyone: no beam, no
 * countdown, no sound for players. Everything about it (TNT count, pattern, where it lands, radius, height, power,
 * fuse, waves, delay, block damage) is set in the owner's orbital menu.
 */
public final class OrbitalStrike {
    private OrbitalStrike() {
    }

    public static final String TOOL = "orbital_cannon";
    public static final String[] PATTERNS = {"rings", "spiral", "random", "column", "line"};
    public static final String[] TARGETS = {"look", "self", "player", "coords"};
    public static final int MAX_TNT = 600;

    /** Saved with the owner's state. */
    public static final class Settings {
        public String pattern = "rings";
        public String target = "look";
        public String targetPlayer;
        public String targetName;
        public int x;
        public int z;
        /** Null: the ground at x/z. */
        public Integer y;
        public int tnt = 150;
        public int radius = 14;
        public int height = 60;
        public float power = 4f;
        /** Ticks until each TNT explodes; 0 = the moment it lands. */
        public int fuse;
        public int perWave = 30;
        public int waveTicks = 3;
        public int delay;
        public boolean breakBlocks = true;
        public boolean ignoreClaims;
        /** Player target: later waves follow them as they run. */
        public boolean follow = true;

        void clamp() {
            tnt = Math.max(1, Math.min(MAX_TNT, tnt));
            radius = Math.max(0, Math.min(80, radius));
            height = Math.max(5, Math.min(300, height));
            power = Math.max(0.5f, Math.min(12f, power));
            fuse = Math.max(0, Math.min(400, fuse));
            perWave = Math.max(1, Math.min(200, perWave));
            waveTicks = Math.max(1, Math.min(100, waveTicks));
            delay = Math.max(0, Math.min(600, delay));
            if (!List.of(PATTERNS).contains(pattern)) {
                pattern = "rings";
            }
            if (!List.of(TARGETS).contains(target)) {
                target = "look";
            }
        }
    }

    public static Settings settings() {
        var st = OwnerPowers.state();
        if (st.orbital == null) {
            st.orbital = new Settings();
        }
        st.orbital.clamp();
        return st.orbital;
    }

    public static void changed() {
        settings().clamp();
        OwnerPowers.save();
    }

    public static void preset(String name) {
        Settings s = settings();
        switch (name) {
            case "nuke" -> {
                s.pattern = "rings";
                s.tnt = 150;
                s.radius = 14;
                s.power = 4f;
                s.fuse = 0;
                s.perWave = 30;
                s.waveTicks = 3;
            }
            case "stab" -> {
                s.pattern = "column";
                s.tnt = 60;
                s.radius = 0;
                s.power = 4f;
                s.fuse = 0;
                s.perWave = 1;
                s.waveTicks = 3;
            }
            case "carpet" -> {
                s.pattern = "spiral";
                s.tnt = 220;
                s.radius = 28;
                s.power = 3f;
                s.fuse = 0;
                s.perWave = 40;
                s.waveTicks = 2;
            }
            case "doomsday" -> {
                s.pattern = "rings";
                s.tnt = 450;
                s.radius = 32;
                s.power = 6f;
                s.fuse = 0;
                s.perWave = 45;
                s.waveTicks = 4;
            }
            default -> {
            }
        }
        changed();
    }

    public static ItemStack item() {
        return OwnerTools.make(Items.PRISMARINE_CRYSTALS, TOOL, "§c◎ Orbital Strike Cannon",
                "Right-click: fire with your settings", "Sneak + right-click: settings menu", "No warning to anyone", "§8Owner only");
    }

    // ---------------------------------------------------------------- the pattern

    /**
     * Where each TNT lands, as offsets from the target: {dx, dy, dz}. dy is extra height (columns stack upwards so
     * they land one after another).
     */
    public static List<double[]> layout(String pattern, int count, int radius, double yaw, java.util.Random rnd) {
        List<double[]> out = new ArrayList<>(count);
        switch (pattern) {
            case "column" -> {
                for (int i = 0; i < count; i++) {
                    // All in one line, one after another (they come in waves): each digs deeper than the last.
                    out.add(new double[]{0, 0, 0});
                }
            }
            case "random" -> {
                for (int i = 0; i < count; i++) {
                    double r = radius * Math.sqrt(rnd.nextDouble());
                    double a = rnd.nextDouble() * Math.PI * 2;
                    out.add(new double[]{Math.cos(a) * r, rnd.nextDouble() * 3, Math.sin(a) * r});
                }
            }
            case "spiral" -> {
                for (int i = 0; i < count; i++) {
                    double r = radius * Math.sqrt((i + 0.5) / count);
                    double a = i * 2.399963;
                    out.add(new double[]{Math.cos(a) * r, rnd.nextDouble() * 2, Math.sin(a) * r});
                }
            }
            case "line" -> {
                double dx = -Math.sin(Math.toRadians(yaw));
                double dz = Math.cos(Math.toRadians(yaw));
                for (int i = 0; i < count; i++) {
                    double t = count == 1 ? 0 : -radius + 2.0 * radius * i / (count - 1);
                    out.add(new double[]{dx * t, rnd.nextDouble() * 2, dz * t});
                }
            }
            default -> {
                // Rings: one in the middle, then rings outward, each holding TNT in proportion to its size.
                out.add(new double[]{0, 0, 0});
                int left = count - 1;
                if (left <= 0) {
                    break;
                }
                int rings = radius <= 0 ? 1 : Math.max(1, Math.min(radius, (int) Math.round(Math.sqrt(left / 3.0))));
                int sum = rings * (rings + 1) / 2;
                int placed = 0;
                for (int k = 1; k <= rings; k++) {
                    int n = k == rings ? left - placed : (int) Math.round(left * k / (double) sum);
                    n = Math.min(n, left - placed);
                    double r = radius <= 0 ? 0 : radius * k / (double) rings;
                    double off = rnd.nextDouble() * Math.PI;
                    for (int i = 0; i < n; i++) {
                        double a = off + i * 2 * Math.PI / n;
                        out.add(new double[]{Math.cos(a) * r, rnd.nextDouble() * 1.5, Math.sin(a) * r});
                    }
                    placed += n;
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- firing

    /**
     * A strike's area: explosions inside keep the strike's rules for a while (block damage on or off, claims ignored
     * or not), and every block they destroy is remembered so the strike can be undone.
     */
    public static final class Zone {
        public String id;
        public String world;
        public double x;
        public double y;
        public double z;
        public double r;
        public long start;
        public long until;
        public boolean breakBlocks;
        public boolean ignoreClaims;
        public int tnt;
        /** Block position (packed) -> {state, block entity NBT or null}, as they were before the first blast. */
        public java.util.Map<Long, String[]> saved = new java.util.concurrent.ConcurrentHashMap<>();

        public boolean breakBlocks() {
            return breakBlocks;
        }

        public boolean ignoreClaims() {
            return ignoreClaims;
        }

        boolean contains(String w, BlockPos p) {
            double dx = p.getX() + 0.5 - x;
            double dz = p.getZ() + 0.5 - z;
            return world.equals(w) && dx * dx + dz * dz <= r * r && System.currentTimeMillis() < until;
        }
    }

    private static final List<Zone> ZONES = new CopyOnWriteArrayList<>();
    /** Finished strikes that can still be undone, oldest first. */
    private static List<Zone> history;
    static final int KEEP = 10;

    /** Called with the blocks an explosion is about to destroy: remember them for undo. */
    public static void record(World w, List<BlockPos> blocks) {
        if (ZONES.isEmpty() || blocks.isEmpty() || !(w instanceof ServerWorld sw)) {
            return;
        }
        String id = Mc.worldId(w);
        for (BlockPos pos : blocks) {
            for (Zone z : ZONES) {
                if (z.contains(id, pos)) {
                    long key = pos.asLong();
                    if (!z.saved.containsKey(key)) {
                        var st = sw.getBlockState(pos);
                        if (!st.isAir()) {
                            var be = sw.getBlockEntity(pos);
                            String nbt = be == null ? null : be.createNbtWithIdentifyingData(sw.getRegistryManager()).toString();
                            z.saved.put(key, new String[]{BlockLog.encode(st), nbt});
                        }
                    }
                    break;
                }
            }
        }
    }

    public static Zone zoneAt(World w, BlockPos p) {
        if (ZONES.isEmpty()) {
            return null;
        }
        String id = Mc.worldId(w);
        for (Zone z : ZONES) {
            if (z.contains(id, p)) {
                return z;
            }
        }
        return null;
    }

    private static final class Strike {
        final UUID owner;
        final ServerWorld world;
        Vec3d center;
        final List<double[]> offsets;
        final Settings s;
        final UUID follow;
        int next;
        long at;
        final List<TntEntity> live = new ArrayList<>();
        /** Where each TNT of a column (stab) must stay over (x, z), so the blasts before it can't push it aside. */
        final java.util.Map<TntEntity, double[]> aim = new java.util.HashMap<>();
        final List<Long> forced = new ArrayList<>();
        Zone zone;

        Strike(UUID owner, ServerWorld world, Vec3d center, List<double[]> offsets, Settings s, UUID follow, long at) {
            this.owner = owner;
            this.world = world;
            this.center = center;
            this.offsets = offsets;
            this.s = s;
            this.follow = follow;
            this.at = at;
        }
    }

    private static final List<Strike> STRIKES = new CopyOnWriteArrayList<>();
    private static long now;
    private static long lastUse;
    private static final DustParticleEffect MARK = new DustParticleEffect(0xFF2A2A, 1.4f);

    /** Right-click with the cannon. */
    static void use(ServerPlayerEntity p) {
        long t = System.currentTimeMillis();
        if (t - lastUse < 250) {
            return;
        }
        lastUse = t;
        if (p.isSneaking()) {
            com.vylorq.anticheat.gui.OwnerMenu.orbital(p);
        } else {
            fire(p);
        }
    }

    /** Fires with the current settings. */
    public static void fire(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Settings cfg = settings();
        Settings s = com.vylorq.anticheat.core.config.ConfigManager.GSON.fromJson(
                com.vylorq.anticheat.core.config.ConfigManager.GSON.toJson(cfg), Settings.class);
        ServerWorld w = p.getEntityWorld();
        Vec3d center;
        UUID follow = null;
        switch (s.target) {
            case "self" -> center = p.getEntityPos();
            case "player" -> {
                ServerPlayerEntity t = s.targetPlayer == null ? null : Ac.server().getPlayerManager().getPlayer(UUID.fromString(s.targetPlayer));
                if (t == null) {
                    Msg.send(p, "orbital.no-player");
                    return;
                }
                w = t.getEntityWorld();
                center = t.getEntityPos();
                follow = s.follow ? t.getUuid() : null;
            }
            case "coords" -> {
                w.getChunk(s.x >> 4, s.z >> 4);
                int y = s.y != null ? s.y : w.getTopY(Heightmap.Type.MOTION_BLOCKING, s.x, s.z);
                center = new Vec3d(s.x + 0.5, y, s.z + 0.5);
            }
            default -> {
                HitResult hit = p.raycast(300, 1f, false);
                if (hit.getType() == HitResult.Type.MISS) {
                    Msg.actionBar(p, "§7" + Msg.trFor(p, "orbital.miss"));
                    return;
                }
                center = hit.getPos();
            }
        }
        Zone zone;
        try {
            zone = launch(p.getUuid(), w, center, s, follow, p.getYaw());
        } catch (RuntimeException e) {
            // Never silently: the owner sees what went wrong (and it's in the log).
            Ac.LOG.warn("Orbital strike failed", e);
            Msg.send(p, "orbital.failed", e.toString());
            return;
        }
        // Only the owner sees and hears anything before the TNT arrives.
        markFor(p, center, Math.max(1.5, s.radius));
        OwnerPowers.sfx(p, "orbital_fire", null, 1f);
        OwnerPowers.usedTool();
        Msg.actionBar(p, "§c◎ " + Msg.trFor(p, "orbital.fired", s.tnt, (int) center.x + " " + (int) center.y + " " + (int) center.z));
        Staff.log(p, "owner-orbital", null, null, s.tnt + " TNT " + s.pattern + " r" + s.radius + " at " + Mc.worldId(w) + " "
                + BlockPos.ofFloored(center).toShortString() + " (" + zone.id + ")");
    }

    /** For the game tests: fires the current settings at a spot, for an owner who isn't there. */
    public static void launchForTest(ServerWorld w, Vec3d center) {
        Settings cfg = settings();
        Settings s = com.vylorq.anticheat.core.config.ConfigManager.GSON.fromJson(
                com.vylorq.anticheat.core.config.ConfigManager.GSON.toJson(cfg), Settings.class);
        s.delay = 0;
        launch(UUID.randomUUID(), w, center, s, null, 0);
    }

    /** Starts a strike: the TNT, the kept-loaded chunks and the area its rules (and undo) cover. */
    private static Zone launch(UUID owner, ServerWorld w, Vec3d center, Settings s, UUID follow, float yaw) {
        List<double[]> offsets = layout(s.pattern, s.tnt, s.radius, yaw, new java.util.Random());
        Strike st = new Strike(owner, w, center, offsets, s, follow, now + s.delay * 20L);
        // Keep the area loaded (and ticking) while TNT falls, even far from any player.
        int cr = (s.radius >> 4) + 1;
        int cx = (int) Math.floor(center.x) >> 4;
        int cz = (int) Math.floor(center.z) >> 4;
        for (int dx = -cr; dx <= cr; dx++) {
            for (int dz = -cr; dz <= cr; dz++) {
                long key = net.minecraft.util.math.ChunkPos.toLong(cx + dx, cz + dz);
                if (!w.getForcedChunks().contains(key)) {
                    w.setChunkForced(cx + dx, cz + dz, true);
                    st.forced.add(key);
                }
            }
        }
        STRIKES.add(st);
        double reach = s.radius + s.power * 2.5 + 4 + (follow != null ? 48 : 0);
        long life = (s.delay * 1000L) + (long) Math.ceil(s.tnt / (double) s.perWave) * s.waveTicks * 50L + 30_000;
        Zone zone = new Zone();
        zone.id = Long.toString(System.currentTimeMillis(), 36);
        zone.world = Mc.worldId(w);
        zone.x = center.x;
        zone.y = center.y;
        zone.z = center.z;
        zone.r = reach;
        zone.start = System.currentTimeMillis();
        zone.until = zone.start + life;
        zone.breakBlocks = s.breakBlocks;
        zone.ignoreClaims = s.ignoreClaims;
        zone.tnt = s.tnt;
        st.zone = zone;
        ZONES.add(zone);
        return zone;
    }

    private static void markFor(ServerPlayerEntity p, Vec3d c, double r) {
        if (p.getEntityWorld() != null && p.squaredDistanceTo(c) < 200 * 200) {
            int pts = (int) Math.min(120, 16 + r * 5);
            for (int i = 0; i < pts; i++) {
                double a = i * 2 * Math.PI / pts;
                Mc.particle(p, MARK, c.x + Math.cos(a) * r, c.y + 0.2, c.z + Math.sin(a) * r);
            }
            Mc.particle(p, MARK, c.x, c.y + 0.3, c.z);
        }
    }

    // ---------------------------------------------------------------- every tick

    private static Field powerField;
    private static boolean powerLooked;

    /** TntEntity keeps its explosion power in its only non-static float field. */
    static void setPower(TntEntity t, float power) {
        try {
            if (!powerLooked) {
                powerLooked = true;
                for (Field f : TntEntity.class.getDeclaredFields()) {
                    if (f.getType() == float.class && !Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        powerField = f;
                        break;
                    }
                }
            }
            if (powerField != null) {
                powerField.setFloat(t, power);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ac.LOG.debug("tnt power", e);
        }
    }

    public static void tick(long ticks) {
        now = ticks;
        if (STRIKES.isEmpty()) {
            if (!ZONES.isEmpty() && ticks % 100 == 0) {
                ZONES.removeIf(z -> System.currentTimeMillis() > z.until);
            }
            return;
        }
        for (Strike st : STRIKES) {
            Settings s = st.s;
            if (st.next < st.offsets.size() && ticks >= st.at) {
                if (st.follow != null) {
                    ServerPlayerEntity t = Ac.server().getPlayerManager().getPlayer(st.follow);
                    if (t != null && t.getEntityWorld() == st.world) {
                        st.center = t.getEntityPos();
                    }
                }
                int end = Math.min(st.offsets.size(), st.next + s.perWave);
                ServerPlayerEntity owner = Ac.server().getPlayerManager().getPlayer(st.owner);
                for (; st.next < end; st.next++) {
                    double[] o = st.offsets.get(st.next);
                    double x = st.center.x + o[0];
                    double z = st.center.z + o[2];
                    double y = st.center.y + s.height + o[1];
                    TntEntity tnt = new TntEntity(st.world, x, y, z, owner);
                    tnt.setVelocity(0, -0.6, 0);
                    tnt.setFuse(s.fuse > 0 ? s.fuse : 600);
                    setPower(tnt, s.power);
                    st.world.spawnEntity(tnt);
                    st.live.add(tnt);
                    if ("column".equals(s.pattern)) {
                        st.aim.put(tnt, new double[]{x, z});
                    }
                }
                st.at = ticks + s.waveTicks;
            }
            // A stab's TNT drops straight down its shaft: the blasts before it would otherwise knock it aside.
            if (!st.aim.isEmpty()) {
                for (TntEntity t : st.live) {
                    double[] a = st.aim.get(t);
                    if (a != null && !t.isRemoved()) {
                        t.setPosition(a[0], t.getY(), a[1]);
                        t.setVelocity(0, Math.min(t.getVelocity().y, -0.8), 0);
                        t.velocityModified = true;
                    }
                }
                st.aim.keySet().removeIf(TntEntity::isRemoved);
            }
            // Explode-on-landing: light the fuse the moment each one touches down.
            if (s.fuse == 0) {
                for (TntEntity t : st.live) {
                    if (!t.isRemoved() && t.age > 2 && t.getFuse() > 2 && (t.isOnGround() || t.isTouchingWater()
                            || Math.abs(t.getVelocity().y) < 0.01)) {
                        t.setFuse(1);
                    }
                }
            }
            st.live.removeIf(TntEntity::isRemoved);
            if (st.next >= st.offsets.size() && st.live.isEmpty()) {
                finish(st);
            }
        }
    }

    private static void finish(Strike st) {
        STRIKES.remove(st);
        if (st.zone != null) {
            // Explosions are over: stop collecting and keep it for undo.
            st.zone.until = Math.min(st.zone.until, System.currentTimeMillis() + 3000);
            OwnerPowers.later(80, () -> {
                ZONES.remove(st.zone);
                if (!st.zone.saved.isEmpty()) {
                    keep(st.zone);
                }
            });
        }
        for (long key : st.forced) {
            st.world.setChunkForced(net.minecraft.util.math.ChunkPos.getPackedX(key), net.minecraft.util.math.ChunkPos.getPackedZ(key), false);
        }
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(st.owner);
        if (p != null) {
            Msg.actionBar(p, "§c◎ " + Msg.trFor(p, "orbital.done", st.offsets.size()));
        }
    }

    /** Stops every strike still falling (TNT in the air is removed). */
    public static int cancelAll(ServerPlayerEntity p) {
        int n = 0;
        for (Strike st : STRIKES) {
            for (TntEntity t : st.live) {
                if (!t.isRemoved()) {
                    t.discard();
                    n++;
                }
            }
            st.live.clear();
            st.next = st.offsets.size();
            finish(st);
        }
        if (p != null) {
            Msg.send(p, "orbital.cancelled", n);
        }
        return n;
    }

    // ---------------------------------------------------------------- undo

    private static java.nio.file.Path undoDir() {
        return Ac.get().dir.resolve("orbital-undo");
    }

    static synchronized List<Zone> history() {
        if (history == null) {
            history = new CopyOnWriteArrayList<>();
            try {
                java.nio.file.Path dir = undoDir();
                if (java.nio.file.Files.isDirectory(dir)) {
                    try (var files = java.nio.file.Files.list(dir)) {
                        for (java.nio.file.Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                            Zone z = com.vylorq.anticheat.core.config.ConfigManager.GSON.fromJson(
                                    java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8), Zone.class);
                            if (z != null && z.saved != null) {
                                history.add(z);
                            }
                        }
                    }
                }
                history.sort(java.util.Comparator.comparingLong(z -> z.start));
            } catch (Exception e) {
                Ac.LOG.warn("Could not read orbital undo files", e);
            }
        }
        return history;
    }

    private static void keep(Zone z) {
        List<Zone> h = history();
        h.add(z);
        try {
            java.nio.file.Files.createDirectories(undoDir());
            java.nio.file.Files.writeString(undoDir().resolve(z.start + ".json"),
                    com.vylorq.anticheat.core.config.ConfigManager.GSON.toJson(z), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save orbital undo", e);
        }
        while (h.size() > KEEP) {
            forget(h.get(0));
        }
    }

    private static void forget(Zone z) {
        history().remove(z);
        try {
            java.nio.file.Files.deleteIfExists(undoDir().resolve(z.start + ".json"));
        } catch (Exception ignored) {
            // nothing to clean up
        }
    }

    public static int undoable() {
        return history().size();
    }

    /** Puts back everything the last strike (or every remembered strike) destroyed, and removes what it dropped. */
    public static void undo(ServerPlayerEntity p, boolean all) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (!STRIKES.isEmpty()) {
            Msg.send(p, "orbital.undo-wait");
            return;
        }
        List<Zone> h = history();
        if (h.isEmpty()) {
            Msg.send(p, "orbital.undo-none");
            return;
        }
        int strikes = 0;
        int blocks = 0;
        int items = 0;
        while (!h.isEmpty()) {
            Zone z = h.get(h.size() - 1);
            int[] done = restore(z);
            blocks += done[0];
            items += done[1];
            strikes++;
            forget(z);
            if (!all) {
                break;
            }
        }
        OwnerPowers.sfx(p, "orbital_undo", null, 1f);
        Msg.send(p, "orbital.undone", strikes, blocks, items);
        Staff.log(p, "owner-orbital-undo", null, null, strikes + " strikes, " + blocks + " blocks");
    }

    /** @return {blocks restored, dropped items removed} */
    static int[] restore(Zone z) {
        ServerWorld w = Mc.world(Ac.server(), z.world);
        if (w == null) {
            return new int[]{0, 0};
        }
        // Remove what the blasts dropped (otherwise putting the blocks back would duplicate them).
        long ageLimit = (System.currentTimeMillis() - z.start) / 50 + 40;
        int items = 0;
        var box = new net.minecraft.util.math.Box(z.x - z.r - 8, w.getBottomY(), z.z - z.r - 8, z.x + z.r + 8, z.y + 400, z.z + z.r + 8);
        for (var e : w.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, box, e -> e.age <= ageLimit)) {
            e.discard();
            items++;
        }
        for (var e : w.getEntitiesByClass(TntEntity.class, box, e -> true)) {
            e.discard();
        }
        int n = 0;
        // Bottom up, so blocks that hang on others have their support first; no neighbour updates while placing.
        List<java.util.Map.Entry<Long, String[]>> list = new ArrayList<>(z.saved.entrySet());
        list.sort(java.util.Comparator.comparingInt(en -> BlockPos.fromLong(en.getKey()).getY()));
        int flags = net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE | net.minecraft.block.Block.SKIP_DROPS;
        for (var en : list) {
            BlockPos pos = BlockPos.fromLong(en.getKey());
            var state = BlockLog.decode(en.getValue()[0]);
            if (state == null) {
                continue;
            }
            w.setBlockState(pos, state, flags);
            String nbt = en.getValue()[1];
            if (nbt != null && !nbt.isEmpty()) {
                var be = w.getBlockEntity(pos);
                if (be != null) {
                    try {
                        Mc.loadBlockEntity(be, com.vylorq.anticheat.util.ItemConv.parseSnbt(nbt), w.getRegistryManager());
                    } catch (Exception ignored) {
                        // keep the block, skip its contents
                    }
                }
            }
            n++;
        }
        return new int[]{n, items};
    }

    public static int active() {
        return STRIKES.size();
    }

    public static String describe(ServerPlayerEntity p) {
        Settings s = settings();
        return switch (s.target) {
            case "self" -> Msg.trFor(p, "orbital.target.self");
            case "player" -> Msg.trFor(p, "orbital.target.player") + ": " + (s.targetName == null ? "-" : s.targetName);
            case "coords" -> Msg.trFor(p, "orbital.target.coords") + ": " + s.x + " " + (s.y == null ? "~" : s.y) + " " + s.z;
            default -> Msg.trFor(p, "orbital.target.look");
        };
    }

    public static String fmtPower(float f) {
        return String.format(Locale.ROOT, f == Math.floor(f) ? "%.0f" : "%.1f", f);
    }
}
