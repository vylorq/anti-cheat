package com.vylorq.anticheat.core.staff;

import com.vylorq.anticheat.core.util.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent staff toggles: frozen players, vanished admins, staff chat toggles, spectate return points,
 * maintenance mode.
 */
public final class StaffState {
    public static final class SpectateReturn {
        public String world;
        public Vec3 pos;
        public float yaw;
        public float pitch;
        public String gameMode;
        public UUID target;
        public boolean wasVanished;
    }

    public static final class Data {
        public Set<UUID> frozen = new HashSet<>();
        public Set<UUID> vanished = new HashSet<>();
        public Set<UUID> staffChatToggled = new HashSet<>();
        public Map<UUID, SpectateReturn> spectating = new HashMap<>();
        public boolean maintenance;
        public Set<UUID> inspectorTool = new HashSet<>();
        /** Admins who chose "Invisible" as their default teleport. */
        public Map<UUID, Boolean> teleportInvisible = new HashMap<>();
    }

    private final Data data;

    public StaffState(Data data) {
        this.data = data == null ? new Data() : data;
    }

    public Data data() {
        return data;
    }

    public synchronized boolean isFrozen(UUID id) {
        return data.frozen.contains(id);
    }

    public synchronized boolean toggleFrozen(UUID id) {
        if (!data.frozen.remove(id)) {
            data.frozen.add(id);
            return true;
        }
        return false;
    }

    public synchronized void setFrozen(UUID id, boolean on) {
        if (on) {
            data.frozen.add(id);
        } else {
            data.frozen.remove(id);
        }
    }

    public synchronized boolean isVanished(UUID id) {
        return data.vanished.contains(id);
    }

    public synchronized boolean toggleVanish(UUID id) {
        if (!data.vanished.remove(id)) {
            data.vanished.add(id);
            return true;
        }
        return false;
    }

    public synchronized void setVanished(UUID id, boolean on) {
        if (on) {
            data.vanished.add(id);
        } else {
            data.vanished.remove(id);
        }
    }

    public synchronized boolean toggleStaffChat(UUID id) {
        if (!data.staffChatToggled.remove(id)) {
            data.staffChatToggled.add(id);
            return true;
        }
        return false;
    }

    public synchronized boolean staffChatOn(UUID id) {
        return data.staffChatToggled.contains(id);
    }

    public synchronized void startSpectate(UUID admin, SpectateReturn ret) {
        // Keep the very first return point if they spectate someone else while already spectating.
        data.spectating.putIfAbsent(admin, ret);
        data.spectating.get(admin).target = ret.target;
    }

    public synchronized SpectateReturn endSpectate(UUID admin) {
        return data.spectating.remove(admin);
    }

    public synchronized SpectateReturn spectating(UUID admin) {
        return data.spectating.get(admin);
    }

    public synchronized boolean maintenance() {
        return data.maintenance;
    }

    public synchronized void setMaintenance(boolean on) {
        data.maintenance = on;
    }

    public synchronized boolean teleportInvisible(UUID admin, boolean defaultValue) {
        return data.teleportInvisible.getOrDefault(admin, defaultValue);
    }

    public synchronized void setTeleportInvisible(UUID admin, boolean on) {
        data.teleportInvisible.put(admin, on);
    }
}
