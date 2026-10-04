package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every system of the mod as one on/off switch (/features, or /settings → Features). A switched-off feature stops
 * working and its commands answer "this feature is turned off". Systems that already had their own on/off setting
 * share it, so both places stay in sync.
 */
public final class Features {
    private Features() {
    }

    public enum Group { SECURITY, WORLD, PLAYERS, ECONOMY, FUN, STAFF }

    public enum Feature {
        // Security
        MOVEMENT_CHECKS("movement-checks", Items.FEATHER, Group.SECURITY, c -> c.movement.enabled, (c, v) -> c.movement.enabled = v),
        COMBAT_CHECKS("combat-checks", Items.IRON_SWORD, Group.SECURITY, c -> c.combat.enabled, (c, v) -> c.combat.enabled = v),
        WARNINGS("warnings", Items.BELL, Group.SECURITY, c -> c.warnings.enabled, (c, v) -> c.warnings.enabled = v),
        XRAY("xray", Items.DIAMOND_ORE, Group.SECURITY, null, null),
        ANTI_ESP("anti-esp", Items.ENDER_EYE, Group.SECURITY, null, null),
        ILLEGAL_ITEMS("illegal-items", Items.BARRIER, Group.SECURITY, c -> c.illegalItems.enabled, (c, v) -> c.illegalItems.enabled = v),
        DUPE_WATCH("dupe-watch", Items.CHEST_MINECART, Group.SECURITY, c -> c.dupeWatch.enabled, (c, v) -> c.dupeWatch.enabled = v),
        CHAT_FILTER("chat-filter", Items.OAK_SIGN, Group.SECURITY, c -> c.chat.enabled, (c, v) -> c.chat.enabled = v),
        REDSTONE("redstone", Items.REDSTONE, Group.SECURITY, null, null, "lag"),
        ITEM_BLACKLIST("item-blacklist", Items.TNT, Group.SECURITY, c -> c.itemBlacklist.enabled, (c, v) -> c.itemBlacklist.enabled = v, "blacklist"),
        WAITING_ROOM("waiting-room", Items.BLACK_CANDLE, Group.SECURITY, c -> c.waitingRoom.enabled, (c, v) -> c.waitingRoom.enabled = v,
                "request", "requests", "waitingroom"),
        // World
        CLAIMS("claims", Items.GOLDEN_SHOVEL, Group.WORLD, null, null, "claim"),
        BARRIERS("barriers", Items.GLASS, Group.WORLD, null, null, "barrier", "lockedbox"),
        END_LOCK("end-lock", Items.END_PORTAL_FRAME, Group.WORLD, null, null, "end"),
        LOBBY("lobby", Items.BEACON, Group.WORLD, null, null, "lobby"),
        WORLD_EVENTS("world-events", Items.REDSTONE_BLOCK, Group.WORLD, c -> c.events.enabled, (c, v) -> c.events.enabled = v, "events"),
        WORLD_BACKUPS("world-backups", Items.ENDER_CHEST, Group.WORLD, c -> c.storage.nightlyWorldBackups, (c, v) -> c.storage.nightlyWorldBackups = v),
        RESTARTS("restarts", Items.CLOCK, Group.WORLD, c -> c.restarts.enabled, (c, v) -> c.restarts.enabled = v),
        // Players
        TEAMS("teams", Items.WHITE_BANNER, Group.PLAYERS, c -> c.teams.enabled, (c, v) -> c.teams.enabled = v, "team", "tc", "tca"),
        PLAYER_TRADES("player-trades", Items.EMERALD, Group.PLAYERS, null, null, "trade"),
        ARENAS("arenas", Items.DIAMOND_SWORD, Group.PLAYERS, null, null, "arena", "duel"),
        JAIL("jail", Items.IRON_BARS, Group.PLAYERS, null, null, "jail", "unjail"),
        DEATH_LOGS("death-logs", Items.SKELETON_SKULL, Group.PLAYERS, null, null, "deaths"),
        STATS("stats", Items.BOOK, Group.PLAYERS, null, null, "stats"),
        ANNOUNCEMENTS("announcements", Items.GOAT_HORN, Group.PLAYERS, null, null, "announce", "notify"),
        REPORTS("reports", Items.WRITABLE_BOOK, Group.PLAYERS, null, null, "report"),
        // Economy
        MARKET("market", Items.GOLD_INGOT, Group.ECONOMY, c -> c.market.enabled, (c, v) -> c.market.enabled = v,
                "balance", "bal", "pay", "baltop", "eco", "ah", "orders", "order", "collect"),
        TRADERS("traders", Items.VILLAGER_SPAWN_EGG, Group.ECONOMY, null, null, "value", "market"),
        SHOPS("shops", Items.CHEST, Group.ECONOMY, c -> c.market.shops, (c, v) -> c.market.shops = v, "shop", "shops"),
        BOOTHS("booths", Items.OAK_SIGN, Group.ECONOMY, c -> c.market.booths, (c, v) -> c.market.booths = v, "booth", "booths"),
        BOUNTIES("bounties", Items.WITHER_SKELETON_SKULL, Group.ECONOMY, c -> c.market.bounties, (c, v) -> c.market.bounties = v, "bounty", "bounties"),
        SERVER_SHOP("server-shop", Items.GOLD_BLOCK, Group.ECONOMY, null, null, "servershop", "sshop"),
        // Fun & scary
        WATCHER("watcher", Items.ENDER_EYE, Group.FUN, c -> c.watcher.enabled, (c, v) -> c.watcher.enabled = v),
        SCARE_WARNING("scare-warning", Items.CARVED_PUMPKIN, Group.FUN, c -> c.watcher.warnOnJoin, (c, v) -> c.watcher.warnOnJoin = v),
        // Staff tools
        BUILDER_MODE("builder-mode", Items.BRICKS, Group.STAFF, null, null, "builder", "build"),
        VANISH("vanish", Items.GLASS_PANE, Group.STAFF, null, null, "vanish"),
        INVENTORY_TOOLS("inventory-tools", Items.LAVA_BUCKET, Group.STAFF, null, null, "invtools", "snapshot", "snapshots"),
        AUTO_SNAPSHOTS("auto-snapshots", Items.CHEST, Group.STAFF, null, null),
        NOTES("notes", Items.PAPER, Group.STAFF, null, null, "note", "notes");

        public final String id;
        public final Item icon;
        public final Group group;
        final Function<AcConfig, Boolean> get;
        final BiConsumer<AcConfig, Boolean> set;
        public final List<String> commands;

        Feature(String id, Item icon, Group group, Function<AcConfig, Boolean> get, BiConsumer<AcConfig, Boolean> set, String... commands) {
            this.id = id;
            this.icon = icon;
            this.group = group;
            this.get = get;
            this.set = set;
            this.commands = List.of(commands);
        }
    }

    private static final Map<String, Feature> BY_COMMAND = new HashMap<>();
    private static final Map<String, Feature> BY_ID = new HashMap<>();

    static {
        for (Feature f : Feature.values()) {
            BY_ID.put(f.id, f);
            for (String c : f.commands) {
                BY_COMMAND.put(c, f);
            }
        }
    }

    public static Feature byId(String id) {
        return id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    /** Whether a feature is on (everything is on unless switched off). */
    public static boolean on(Feature f) {
        if (!Ac.running()) {
            return true;
        }
        AcConfig c = Ac.config();
        if (f.get != null) {
            return f.get.apply(c);
        }
        return c.features.getOrDefault(f.id, true);
    }

    public static void set(Feature f, boolean on) {
        AcConfig c = Ac.config();
        if (f.set != null) {
            f.set.accept(c, on);
        } else {
            c.features.put(f.id, on);
        }
        Ac.get().configManager.save();
    }

    /** The feature a command belongs to, or null. */
    public static Feature forCommand(String root) {
        return BY_COMMAND.get(root);
    }
}
