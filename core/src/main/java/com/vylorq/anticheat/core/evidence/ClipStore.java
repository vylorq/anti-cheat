package com.vylorq.anticheat.core.evidence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vylorq.anticheat.core.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Stores evidence clips as one JSON file each, which doubles as the export format. */
public final class ClipStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path dir;

    public ClipStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public Path save(EvidenceClip clip) throws IOException {
        Path file = dir.resolve(clip.player + "_" + clip.id + ".json");
        AtomicFiles.writeString(file, GSON.toJson(clip));
        return file;
    }

    public EvidenceClip load(String id) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().endsWith("_" + id + ".json")) {
                    return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), EvidenceClip.class);
                }
            }
        } catch (IOException ignored) {
            // missing directory -> no clip
        }
        return null;
    }

    public List<EvidenceClip> forPlayer(UUID player) {
        List<EvidenceClip> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().startsWith(player + "_")) {
                    out.add(GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), EvidenceClip.class));
                }
            }
        } catch (IOException ignored) {
            // none
        }
        out.sort(Comparator.comparingLong((EvidenceClip c) -> c.createdAt).reversed());
        return out;
    }

    public void setPinned(String id, boolean pinned) throws IOException {
        EvidenceClip c = load(id);
        if (c != null && c.pinned != pinned) {
            c.pinned = pinned;
            save(c);
        }
    }

    /** Deletes unpinned clips older than the cutoff and trims each player to {@code maxPerPlayer}. */
    public int purge(long olderThan, int maxPerPlayer) {
        int removed = 0;
        java.util.Map<String, List<Path>> byPlayer = new java.util.HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".json")) {
                    continue;
                }
                EvidenceClip c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), EvidenceClip.class);
                if (c == null || c.pinned) {
                    continue;
                }
                if (c.createdAt < olderThan) {
                    Files.deleteIfExists(p);
                    removed++;
                } else {
                    byPlayer.computeIfAbsent(String.valueOf(c.player), k -> new ArrayList<>()).add(p);
                }
            }
            for (List<Path> list : byPlayer.values()) {
                if (list.size() > maxPerPlayer) {
                    list.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
                    for (int i = 0; i < list.size() - maxPerPlayer; i++) {
                        Files.deleteIfExists(list.get(i));
                        removed++;
                    }
                }
            }
        } catch (IOException ignored) {
            // directory missing
        }
        return removed;
    }
}
