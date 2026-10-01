package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Drafts and lobby backups.
 *
 * <p><b>Drafts:</b> a builder doesn't build in the real lobby. A copy of the lobby is made far away (1,000,000 blocks
 * east, in the same world) and the builder works there. Nothing changes in the real lobby until the owner approves:
 * then only the blocks the builder actually changed are copied over (signs and banners included), so anything
 * others changed in the meantime is kept. Rejecting resets the copy to the real lobby.
 *
 * <p><b>Backups:</b> the whole lobby is saved when builder mode starts, every hour while a builder is active, and
 * before every approval or restore. The last 20 are kept and any of them can be put back.
 */
public final class BuilderDrafts {
    private BuilderDrafts() {
    }

    /** How far east drafts are made. */
    public static final int OFFSET = 1_000_000;
    /** Biggest lobby that can be copied or backed up. */
    public static final long MAX_VOLUME = 4_000_000;
    static final int KEEP_BACKUPS = 20;

    public static final class Draft {
        public String name;
        /** The real lobby box that is copied. */
        public Area area;
        public String base;
        /** The copy is complete. */
        public boolean ready;
        /** The builder asked for it to be approved. */
        public boolean submitted;
        public long created;
        public long submittedAt;
    }

    private static Map<UUID, Draft> drafts() {
        return Ac.get().misc.drafts;
    }

    public static Draft get(UUID id) {
        return drafts().get(id);
    }

    public static Map<UUID, Draft> all() {
        return drafts();
    }

    /**
     * The lobby box to copy and back up: the lobby, but only from a little below its lowest ground to 32 blocks above
     * its highest block (a lobby set from bedrock to the sky would be far too big otherwise). Null if no lobby is set.
     */
    public static Area lobbyBox() {
        Area a = Ac.get().lobby.data().area;
        if (a == null) {
            return null;
        }
        ServerWorld w = Mc.world(Ac.server(), a.world);
        if (w == null) {
            return null;
        }
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int step = Math.max(1, (int) Math.sqrt((double) (a.maxX - a.minX + 1) * (a.maxZ - a.minZ + 1) / 4096.0));
        for (int x = a.minX; x <= a.maxX; x += step) {
            for (int z = a.minZ; z <= a.maxZ; z += step) {
                int top = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                low = Math.min(low, top);
                high = Math.max(high, top);
            }
        }
        if (low == Integer.MAX_VALUE) {
            return null;
        }
        int minY = Math.max(a.minY, low - 16);
        int maxY = Math.min(a.maxY, high + 32);
        if (maxY < minY) {
            maxY = minY;
        }
        return new Area(a.world, a.minX, minY, a.minZ, a.maxX, maxY, a.maxZ);
    }

    static Area shifted(Area a) {
        return new Area(a.world, a.minX + OFFSET, a.minY, a.minZ, a.maxX + OFFSET, a.maxY, a.maxZ);
    }

    /** Whether a builder works in a draft (and it is ready). */
    public static boolean inDraftMode(UUID id) {
        Draft d = get(id);
        return d != null && d.ready;
    }

    /** Whether this position is inside the builder's draft copy. */
    public static boolean inDraft(UUID id, String world, BlockPos pos) {
        Draft d = get(id);
        return d != null && shifted(d.area).contains(world, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Makes (or reuses) a builder's draft and sends them there when it's ready.
     *
     * @return false when there's no lobby or it's too big (the builder then builds in the real lobby)
     */
    public static boolean start(ServerPlayerEntity p) {
        Draft d = get(p.getUuid());
        if (d != null) {
            if (d.ready) {
                teleportIn(p);
            } else {
                copy(p, d, p.getUuid());
            }
            return true;
        }
        Area box = lobbyBox();
        if (box == null || box.volume() > MAX_VOLUME) {
            return false;
        }
        d = new Draft();
        d.name = p.getGameProfile().name();
        d.area = box;
        d.created = System.currentTimeMillis();
        d.base = "builder-base-" + p.getUuid();
        drafts().put(p.getUuid(), d);
        Ac.saveNow("misc");
        copy(p, d, p.getUuid());
        return true;
    }

    /** Copies the real lobby into the draft (and remembers it as the base to compare against). */
    static void copy(ServerPlayerEntity p, Draft d, UUID id) {
        ServerWorld w = Mc.world(Ac.server(), d.area.world);
        if (w == null) {
            return;
        }
        try {
            BlockSnapshots.save(w, d.area, d.base);
        } catch (Exception e) {
            Ac.LOG.warn("Draft base snapshot failed", e);
        }
        d.ready = false;
        Area a = d.area;
        BlockPos min = new BlockPos(a.minX, a.minY, a.minZ);
        BlockPos max = new BlockPos(a.maxX, a.maxY, a.maxZ);
        BuilderTools.Source box = BuilderTools.boxSource(min, max, (pos, old) -> null);
        if (p != null) {
            Msg.send(p, "draft.copying");
        }
        BuilderTools.system(p, w, new BuilderTools.Source() {
            @Override
            public int size() {
                return box.size();
            }

            @Override
            public BlockPos pos(int i) {
                return box.pos(i).add(OFFSET, 0, 0);
            }

            @Override
            public BlockState state(int i, BlockState old) {
                return w.getBlockState(box.pos(i));
            }

            @Override
            public NbtCompound blockEntity(int i) {
                BlockEntity be = w.getBlockEntity(box.pos(i));
                return be == null || be instanceof Inventory ? null : be.createNbtWithIdentifyingData(w.getRegistryManager());
            }
        }, "draft-copy", null, null, job -> {
            d.ready = true;
            Ac.saveNow("misc");
            ServerPlayerEntity online = Ac.server().getPlayerManager().getPlayer(id);
            if (online != null && BuilderMode.is(online)) {
                Msg.send(online, "draft.ready");
                teleportIn(online);
            }
        });
    }

    /** Sends a builder into their draft (to the matching spot if they're in the real lobby). */
    public static void teleportIn(ServerPlayerEntity p) {
        Draft d = get(p.getUuid());
        if (d == null || !d.ready) {
            return;
        }
        ServerWorld w = Mc.world(Ac.server(), d.area.world);
        if (w == null) {
            return;
        }
        double x;
        double y;
        double z;
        if (d.area.contains(Mc.worldId(p.getEntityWorld()), p.getX(), p.getY(), p.getZ())) {
            x = p.getX() + OFFSET;
            y = p.getY();
            z = p.getZ();
        } else {
            x = (d.area.minX + d.area.maxX) / 2.0 + OFFSET + 0.5;
            z = (d.area.minZ + d.area.maxZ) / 2.0 + 0.5;
            y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + 1;
        }
        Mc.teleport(p, w, x, y, z, p.getYaw(), p.getPitch());
        BuilderLog.event(p, "DRAFT-ENTER", "");
    }

    /** Every second: builders with a draft stay in it. */
    public static void keepInside(ServerPlayerEntity p) {
        Draft d = get(p.getUuid());
        if (d == null || !d.ready) {
            return;
        }
        Area s = shifted(d.area);
        String w = Mc.worldId(p.getEntityWorld());
        int m = 24;
        boolean near = s.world.equals(w) && p.getX() >= s.minX - m && p.getX() <= s.maxX + 1 + m && p.getZ() >= s.minZ - m
                && p.getZ() <= s.maxZ + 1 + m && p.getY() >= s.minY - m && p.getY() <= s.maxY + 1 + m;
        if (!near) {
            Msg.actionBar(p, Msg.trFor(p, "draft.stay"));
            teleportIn(p);
        }
    }

    public static void submit(ServerPlayerEntity p) {
        Draft d = get(p.getUuid());
        if (d == null) {
            Msg.send(p, "draft.none");
            return;
        }
        d.submitted = true;
        d.submittedAt = System.currentTimeMillis();
        Ac.saveNow("misc");
        BuilderLog.event(p, "SUBMIT", "");
        Msg.send(p, "draft.submitted");
        String name = p.getGameProfile().name();
        for (ServerPlayerEntity s : Staff.online()) {
            if (!com.vylorq.anticheat.perm.Perms.has(s, com.vylorq.anticheat.core.perm.Perm.MANAGE_ADMINS)) {
                continue;
            }
            MutableText t = Msg.prefixed(Msg.trFor(s, "draft.submitted-staff", name));
            t.append(" ").append(Msg.button("§b[" + Msg.trFor(s, "draft.review") + "]", "/vigil builder review " + name, ""));
            t.append(" ").append(Msg.button("§a[" + Msg.trFor(s, "draft.approve") + "]", "/vigil builder approve " + name, ""));
            t.append(" ").append(Msg.button("§c[" + Msg.trFor(s, "draft.reject") + "]", "/vigil builder reject " + name, ""));
            s.sendMessage(t);
        }
    }

    /** The owner goes (invisibly) to look at a draft. */
    public static boolean review(ServerPlayerEntity owner, UUID id) {
        Draft d = get(id);
        if (d == null || !d.ready) {
            return false;
        }
        ServerWorld w = Mc.world(Ac.server(), d.area.world);
        double x = (d.area.minX + d.area.maxX) / 2.0 + OFFSET + 0.5;
        double z = (d.area.minZ + d.area.maxZ) / 2.0 + 0.5;
        double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + 2;
        StaffTools.teleportTo(owner, w, new com.vylorq.anticheat.core.util.Vec3(x, y, z), true, "draft of " + d.name);
        return true;
    }

    /** Copies the builder's changes into the real lobby. */
    public static boolean approve(ServerPlayerEntity owner, UUID id) {
        Draft d = get(id);
        if (d == null || !d.ready || BuilderTools.busy(id)) {
            return false;
        }
        ServerWorld w = Mc.world(Ac.server(), d.area.world);
        BlockSnapshots.Snap base;
        try {
            base = BlockSnapshots.read(d.base);
        } catch (Exception e) {
            base = null;
        }
        if (w == null || base == null || base.states().length != d.area.volume()) {
            return false;
        }
        backup("before-approve-" + d.name);
        BlockState[] was = base.states();
        Area a = d.area;
        BuilderTools.Source box = BuilderTools.boxSource(new BlockPos(a.minX, a.minY, a.minZ), new BlockPos(a.maxX, a.maxY, a.maxZ),
                (pos, old) -> null);
        String by = owner == null ? "console" : owner.getGameProfile().name();
        BuilderLog.event(id, "APPROVE-START", "by " + by);
        BuilderTools.system(owner, w, new BuilderTools.Source() {
            @Override
            public int size() {
                return box.size();
            }

            @Override
            public BlockPos pos(int i) {
                return box.pos(i);
            }

            @Override
            public BlockState state(int i, BlockState old) {
                BlockState draft = w.getBlockState(box.pos(i).add(OFFSET, 0, 0));
                return draft != was[i] ? draft : null;
            }

            @Override
            public NbtCompound blockEntity(int i) {
                BlockEntity be = w.getBlockEntity(box.pos(i).add(OFFSET, 0, 0));
                return be == null || be instanceof Inventory ? null : be.createNbtWithIdentifyingData(w.getRegistryManager());
            }
        }, "approve", id, "APPROVE", job -> {
            d.submitted = false;
            try {
                BlockSnapshots.save(w, d.area, d.base);
            } catch (Exception e) {
                Ac.LOG.warn("Draft base snapshot failed", e);
            }
            Ac.saveNow("misc");
            BuilderLog.event(id, "APPROVED", "by " + by + ", " + job.changed + " blocks copied to the lobby");
            Staff.log(owner, "builder-approve", id, d.name, job.changed + " blocks");
            ServerPlayerEntity b = Ac.server().getPlayerManager().getPlayer(id);
            if (b != null) {
                Msg.send(b, "draft.approved", job.changed);
            }
        });
        return true;
    }

    /** Throws the builder's changes away: the draft becomes a fresh copy of the real lobby. */
    public static boolean reject(ServerPlayerEntity owner, UUID id) {
        Draft d = get(id);
        if (d == null || BuilderTools.busy(id)) {
            return false;
        }
        d.submitted = false;
        BuilderLog.event(id, "REJECTED", "by " + (owner == null ? "console" : owner.getGameProfile().name()));
        Staff.log(owner, "builder-reject", id, d.name, "");
        ServerPlayerEntity b = Ac.server().getPlayerManager().getPlayer(id);
        if (b != null) {
            Msg.send(b, "draft.rejected");
        }
        copy(b, d, id);
        return true;
    }

    /** Removes a draft record (the blocks far away stay; nobody can reach them). */
    public static void discard(UUID id) {
        if (drafts().remove(id) != null) {
            Ac.saveNow("misc");
        }
    }

    // ---------------------------------------------------------------- backups

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static long lastHourly;

    /** Saves the lobby. @return the backup name, or null */
    public static String backup(String reason) {
        Area a = lobbyBox();
        if (a == null || a.volume() > MAX_VOLUME) {
            return null;
        }
        ServerWorld w = Mc.world(Ac.server(), a.world);
        if (w == null) {
            return null;
        }
        String name = "lobby-" + LocalDateTime.now().format(STAMP) + "-" + reason.replaceAll("[^a-zA-Z0-9_-]", "_");
        try {
            BlockSnapshots.save(w, a, name);
        } catch (Exception e) {
            Ac.LOG.warn("Lobby backup failed", e);
            return null;
        }
        List<String> all = backups();
        for (int i = KEEP_BACKUPS; i < all.size(); i++) {
            try {
                Files.deleteIfExists(BlockSnapshots.file(all.get(i)));
            } catch (Exception ignored) {
                // try again next time
            }
        }
        return name;
    }

    /** Lobby backups, newest first. */
    public static List<String> backups() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(BlockSnapshots.dir());
            try (Stream<java.nio.file.Path> s = Files.list(BlockSnapshots.dir())) {
                s.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("lobby-") && n.endsWith(".nbt"))
                        .map(n -> n.substring(0, n.length() - 4)).sorted((x, y) -> y.compareTo(x)).forEach(out::add);
            }
        } catch (Exception e) {
            Ac.LOG.warn("Could not list lobby backups", e);
        }
        return out;
    }

    /** Puts a backup back (after backing up the lobby as it is now). @return blocks changed, or -1 */
    public static int restore(ServerPlayerEntity by, String name) {
        if (!backups().contains(name)) {
            return -1;
        }
        backup("before-restore");
        try {
            BlockSnapshots.Snap s = BlockSnapshots.read(name);
            ServerWorld w = s == null ? null : Mc.world(Ac.server(), s.area().world);
            if (w == null) {
                return -1;
            }
            int n = BlockSnapshots.restore(w, name);
            Staff.log(by, "lobby-restore", null, name, n + " blocks");
            return n;
        } catch (Exception e) {
            Ac.LOG.warn("Lobby restore failed", e);
            return -1;
        }
    }

    /** Every second: the hourly backup while any builder is active. */
    public static void tick() {
        if (Ac.get().misc.builders.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastHourly == 0) {
            lastHourly = now;
        }
        if (now - lastHourly >= 3_600_000L) {
            lastHourly = now;
            backup("hourly");
        }
    }
}
