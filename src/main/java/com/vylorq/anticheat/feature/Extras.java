package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.extras.RestartScheduler;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Event tools, scheduled restarts with backups (sections 15, 28). */
public final class Extras {
    private static long countdownEnd = -1;
    private static String countdownText;
    private static int lastShown = -1;

    private static final List<ItemStack> DROP_ITEMS = new ArrayList<>();
    private static ServerWorld dropWorld;
    private static Vec3d dropAt;
    private static long dropEnd = -1;
    private static final Random RANDOM = new Random();

    private static long nextRestart = -1;
    private static long lastRestartTick;

    private Extras() {
    }

    public static void title(String title, String subtitle) {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Mc.title(p, title, subtitle, 10, 70, 20);
            Mc.sound(p, SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
    }

    public static void countdown(int seconds, String text) {
        countdownEnd = System.currentTimeMillis() + seconds * 1000L;
        countdownText = text;
        lastShown = -1;
    }

    /** Stores the items for a drop party. */
    public static void setDropItems(List<ItemStack> items) {
        DROP_ITEMS.clear();
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                DROP_ITEMS.add(s.copy());
            }
        }
    }

    public static int dropItemCount() {
        return DROP_ITEMS.size();
    }

    public static void startDropParty(ServerPlayerEntity admin, int seconds) {
        dropWorld = admin.getServerWorld();
        dropAt = admin.getPos();
        dropEnd = System.currentTimeMillis() + seconds * 1000L;
        Ac.server().getPlayerManager().broadcast(Text.literal(Msg.tr("event.drop-party", seconds)), false);
    }

    /** Every tick. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (countdownEnd > 0) {
            int left = (int) Math.ceil((countdownEnd - now) / 1000.0);
            if (left != lastShown) {
                lastShown = left;
                for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                    if (left > 0) {
                        Mc.title(p, "§e" + left, countdownText, 0, 25, 0);
                        Mc.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1f, 1f);
                    } else {
                        Mc.title(p, "§a" + (countdownText == null || countdownText.isEmpty() ? "Go!" : countdownText), "", 0, 40, 10);
                        Mc.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1f, 2f);
                    }
                }
                if (left <= 0) {
                    countdownEnd = -1;
                }
            }
        }
        if (dropEnd > 0) {
            if (now >= dropEnd || DROP_ITEMS.isEmpty()) {
                dropEnd = -1;
            } else if (RANDOM.nextInt(4) == 0) {
                ItemStack s = DROP_ITEMS.remove(RANDOM.nextInt(DROP_ITEMS.size()));
                double x = dropAt.x + (RANDOM.nextDouble() - 0.5) * 12;
                double z = dropAt.z + (RANDOM.nextDouble() - 0.5) * 12;
                ItemEntity e = new ItemEntity(dropWorld, x, dropAt.y + 8, z, s);
                e.setToDefaultPickupDelay();
                dropWorld.spawnEntity(e);
            }
        }
    }

    /** Every second: restart schedule. */
    public static void tickRestarts() {
        var cfg = Ac.config().restarts;
        if (!cfg.enabled) {
            nextRestart = -1;
            return;
        }
        long now = System.currentTimeMillis();
        if (nextRestart < 0) {
            nextRestart = RestartScheduler.next(cfg.times, now, ZoneId.systemDefault());
            lastRestartTick = now;
            return;
        }
        int warn = RestartScheduler.warningDue(nextRestart, lastRestartTick, now, cfg.warnMinutes);
        lastRestartTick = now;
        if (warn > 0) {
            Ac.server().getPlayerManager().broadcast(Text.literal(Msg.tr("restart.warning", warn)), false);
        }
        if (now >= nextRestart) {
            nextRestart = -1;
            restartNow(cfg.backupBeforeRestart);
        }
    }

    /** Saves, backs up the world, then stops (the host's restart script brings it back). */
    public static void restartNow(boolean backup) {
        var server = Ac.server();
        server.getPlayerManager().broadcast(Text.literal(Msg.tr("restart.now")), false);
        server.saveAll(true, true, true);
        if (backup) {
            try {
                backupWorld();
            } catch (Exception e) {
                Ac.LOG.error("World backup failed", e);
            }
        }
        for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            p.networkHandler.disconnect(Text.literal(Msg.tr("restart.kick")));
        }
        server.stop(false);
    }

    public static Path backupWorld() throws Exception {
        Path world = Ac.server().getSavePath(net.minecraft.util.WorldSavePath.ROOT).normalize();
        Path dir = Ac.get().dir.resolve("backups");
        Files.createDirectories(dir);
        Path zip = dir.resolve("world-" + java.time.LocalDateTime.now().toString().replace(':', '-') + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)); Stream<Path> files = Files.walk(world)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (Files.isDirectory(f) || f.getFileName().toString().equals("session.lock")) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(world.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, out);
                out.closeEntry();
            }
        }
        // Keep the newest few backups.
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.filter(p -> p.getFileName().toString().startsWith("world-"))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified())).toList();
            for (int i = Ac.config().storage.backupsToKeep; i < all.size(); i++) {
                Files.deleteIfExists(all.get(i));
            }
        }
        return zip;
    }
}
