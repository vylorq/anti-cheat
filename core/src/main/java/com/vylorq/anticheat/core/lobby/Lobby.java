package com.vylorq.anticheat.core.lobby;

import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.util.Location;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The lobby (section 20). Breaking and placing are always blocked, even for admins, unless they are in edit mode.
 */
public final class Lobby {
    public enum ChestMode { VIEW_ONLY, TAKE, LOOT }

    public enum Action { BREAK, PLACE, CHEST, DOOR, BUTTON, PRESSURE_PLATE, SIT, ITEM_FRAME, ARMOR_STAND, PAINTING,
        FLOWER_POT, SIGN, BUCKET, FIRE, TRAMPLE, PVP, OTHER_INTERACT }

    public static final class ChestConfig {
        public ChestMode mode = ChestMode.VIEW_ONLY;
        /** Loot chests: serialized contents that are restored every refill. */
        public List<String> lootContents;
        public long lastRefill;
    }

    public static final class Data {
        public Area area;
        public Location spawn;
        /** "x,y,z" -> chest config */
        public Map<String, ChestConfig> chests = new LinkedHashMap<>();
        public Set<UUID> editing = new HashSet<>();
    }

    private final Data data;

    public Lobby(Data data) {
        this.data = data == null ? new Data() : data;
    }

    public Data data() {
        return data;
    }

    public boolean isSet() {
        return data.area != null && data.spawn != null;
    }

    public boolean inLobby(String world, double x, double y, double z) {
        return data.area != null && data.area.contains(world, x, y, z);
    }

    public boolean inLobby(String world, int x, int y, int z) {
        return data.area != null && data.area.contains(world, x, y, z);
    }

    public boolean isEditing(UUID id) {
        return data.editing.contains(id);
    }

    /** @return the new state. */
    public boolean toggleEdit(UUID id) {
        if (!data.editing.remove(id)) {
            data.editing.add(id);
            return true;
        }
        return false;
    }

    /** Whether an action is allowed in the lobby. Staff are NOT exempt: only edit mode is. */
    public boolean allowed(UUID player, Action action) {
        if (isEditing(player)) {
            return true;
        }
        return switch (action) {
            case CHEST, DOOR, BUTTON, PRESSURE_PLATE, SIT -> true;
            default -> false;
        };
    }

    public static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    public ChestConfig chest(int x, int y, int z) {
        return data.chests.get(key(x, y, z));
    }

    public ChestConfig chestOrDefault(int x, int y, int z) {
        ChestConfig c = chest(x, y, z);
        return c == null ? new ChestConfig() : c;
    }

    public void setChest(int x, int y, int z, ChestConfig c) {
        data.chests.put(key(x, y, z), c);
    }

    /** Loot chests due for a refill. */
    public Map<String, ChestConfig> dueRefills(long now, long intervalMillis) {
        Map<String, ChestConfig> out = new LinkedHashMap<>();
        for (Map.Entry<String, ChestConfig> e : data.chests.entrySet()) {
            ChestConfig c = e.getValue();
            if (c.mode == ChestMode.LOOT && now - c.lastRefill >= intervalMillis) {
                c.lastRefill = now;
                out.put(e.getKey(), c);
            }
        }
        return out;
    }
}
