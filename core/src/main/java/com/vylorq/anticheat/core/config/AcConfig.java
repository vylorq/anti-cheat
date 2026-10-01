package com.vylorq.anticheat.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole mod configuration. Serialized to {@code config/vigil/config.json} with Gson.
 * Every field has a sensible default so a missing or partial file still works.
 */
public class AcConfig {
    public int configVersion = 1;

    public General general = new General();
    public Permissions permissions = new Permissions();
    public Detection detection = new Detection();
    public Warnings warnings = new Warnings();
    public Movement movement = new Movement();
    public Combat combat = new Combat();
    public Evidence evidence = new Evidence();
    public Watchlist watchlist = new Watchlist();
    public Exempt exempt = new Exempt();
    public Xray xray = new Xray();
    public IllegalItems illegalItems = new IllegalItems();
    public DupeWatch dupeWatch = new DupeWatch();
    public Chat chat = new Chat();
    public Joins joins = new Joins();
    public Staff staff = new Staff();
    public Deaths deaths = new Deaths();
    public Claims claims = new Claims();
    public Redstone redstone = new Redstone();
    public Lobby lobby = new Lobby();
    public Jail jail = new Jail();
    public WaitingRoom waitingRoom = new WaitingRoom();
    public Arenas arenas = new Arenas();
    public Traders traders = new Traders();
    public PlayerTrade playerTrade = new PlayerTrade();
    public Storage storage = new Storage();
    public Discord discord = new Discord();
    public Fun fun = new Fun();
    public Restarts restarts = new Restarts();
    public Watcher watcher = new Watcher();

    public static class General {
        /** Owner UUID. The owner has every power. Leave empty until set. */
        public String ownerUuid = "";
        /**
         * Owner by in-game name, for when the UUID isn't known yet (Bedrock players, or set up from the phone).
         * The first player with this name to join becomes the owner and ownerUuid is filled in.
         */
        public String ownerName = "";
        /** Default language: en_us or ar_sa. Each player sees their own game language when it's available. */
        public String language = "en_us";
        /** Server name shown in the ban screen, the waiting room and welcome messages. */
        public String serverName = "our server";
        /** "Vigil »" in front of mod messages. */
        public boolean showPrefix = true;
        /** Menu and message sounds (each admin can also turn them off for themselves). */
        public boolean sounds = true;
        /** Boss bars for jail time, claim entry, restarts and maintenance. */
        public boolean bossBars = true;
        /**
         * Whether players can go to the End. While closed, eyes of ender can't be put into portal frames and no
         * portal takes anyone there (leaving the End still works). Open it in /settings or here.
         */
        public boolean endOpen = false;
        /** Checks pause/loosen when server TPS drops below this. */
        public double lagTpsThreshold = 18.0;
        public boolean debug = false;
    }

    public static class Permissions {
        /** Op level treated as admin when no permissions API is installed. */
        public int adminOpLevel = 3;
        /** Whether admins may see IPs and alt accounts. Owner always can. */
        public boolean adminsSeePrivateInfo = false;
        /** Whether admins (not just the owner) may open /settings. */
        public boolean adminsUseSettings = true;
        /** Flag clients that send GUI actions or commands they aren't allowed to use. */
        public boolean flagUnauthorizedActions = true;
    }

    public static class Detection {
        /** Violation points removed per minute from each check. */
        public double decayPerMinute = 2.0;
        /** Total points that map to suspicion ~63. Larger = slower to turn red. */
        public double suspicionScale = 40.0;
        /** Suspicion score at which a review case opens. */
        public int reviewThreshold = 60;
        /** Suspicion score at which the player is automatically watched. */
        public int autoWatchScore = 45;
        /** Number of flags within {@link #autoWatchWindowMinutes} that auto-watches. */
        public int autoWatchFlagCount = 25;
        public int autoWatchWindowMinutes = 10;
        public boolean autoWatchEnabled = true;
        /** Suspicion at which admins get a (grouped) alert. */
        public int alertScore = 25;
        /** Per-check multiplier for points (sensitivity). 1.0 = default, 0 = check disabled. */
        public Map<String, Double> sensitivity = new LinkedHashMap<>();
        /** Checks that are fully disabled. */
        public List<String> disabledChecks = new ArrayList<>();
        /** Minimum seconds between admin alerts for the same player and check (watched players ignore this). */
        public int alertCooldownSeconds = 10;
    }

    public static class Warnings {
        public boolean enabled = true;
        /** Suspicion score at which the player gets the generic warning. */
        public int warnScore = 40;
        public int cooldownMinutes = 15;
        public int maxPerSession = 3;
    }

    public static class Movement {
        public boolean enabled = true;
        public boolean setbacks = true;
        /** Seconds of grace after join, respawn, teleport, dimension change. */
        public double graceSeconds = 3.0;
        /** Buffer size before a movement violation adds points. */
        public double bufferLimit = 6.0;
        /** How fast the buffer drains per legit move. */
        public double bufferDecay = 0.25;
        /** Extra horizontal tolerance in blocks/tick. */
        public double speedTolerance = 0.03;
        /** Extra vertical tolerance in blocks/tick. */
        public double verticalTolerance = 0.05;
        /** Tolerance multiplier applied to Bedrock players. */
        public double bedrockLeniency = 1.6;
        /** Ticks after an external velocity (knockback, wind charge, explosion) during which limits are relaxed. */
        public int velocityGraceTicks = 40;
        /** Max ping (ms) used for compensation. */
        public int maxPingCompensationMs = 500;
    }

    public static class Combat {
        public boolean enabled = true;
        public double maxReach = 3.0;
        public double reachTolerance = 0.3;
        public double bedrockReachTolerance = 0.6;
        /** Creative players can reach further; they are skipped. */
        public boolean wallHitCheck = true;
        public boolean aimCheck = true;
        public int maxCps = 20;
        public int bedrockMaxCps = 24;
        /** Click interval coefficient of variation below this is "too consistent". */
        public double minClickCv = 0.08;
        public double bedrockMinClickCv = 0.05;
        /** Multiplier on autoclicker points ("strict"). */
        public double autoclickerStrictness = 1.5;
        /** Degrees between targets hit in one window considered impossible. */
        public double multiTargetAngle = 90.0;
        public int multiTargetWindowMs = 150;
    }

    public static class Evidence {
        public int clipSeconds = 30;
        public int watchedClipSeconds = 60;
        public int retentionDays = 30;
        /** Minimum seconds between clips for the same player. Watched players use a quarter of this. */
        public int clipCooldownSeconds = 20;
        public int maxClipsPerPlayer = 50;
    }

    public static class Watchlist {
        /** Multiplier on points for watched players (stricter). */
        public double pointMultiplier = 1.5;
        /** Review threshold for watched players. */
        public int reviewThreshold = 45;
        public boolean logBlocks = true;
        public boolean logContainers = true;
        public boolean logCommands = true;
        public boolean logItems = true;
    }

    public static class Exempt {
        /** Record flags for exempt players silently. */
        public boolean recordFlags = true;
    }

    public static class Xray {
        public boolean oreHiding = true;
        public boolean trapEnabled = true;
        /** Fake veins placed per chunk (0-3). */
        public int trapVeinsPerChunk = 1;
        public int trapMinY = -58;
        public int trapMaxY = 16;
        public boolean oreAlerts = true;
        /** Ore alerts are grouped for this many seconds. */
        public int oreAlertGroupSeconds = 30;
        /** Suspicious mining ratio: rare ores per 100 stone-type blocks. */
        public double suspiciousRatio = 4.0;
        public List<String> hiddenBlocks = new ArrayList<>(List.of(
                "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris",
                "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:gold_ore",
                "minecraft:deepslate_gold_ore", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
                "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:lapis_ore",
                "minecraft:deepslate_lapis_ore", "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore",
                "minecraft:spawner", "minecraft:chest"));
        public List<String> alertBlocks = new ArrayList<>(List.of(
                "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris"));
    }

    public static class IllegalItems {
        public boolean enabled = true;
        public boolean removeItems = true;
        public List<String> bannedItems = new ArrayList<>(List.of(
                "minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block",
                "minecraft:repeating_command_block", "minecraft:command_block_minecart", "minecraft:bedrock",
                "minecraft:spawner", "minecraft:trial_spawner", "minecraft:debug_stick", "minecraft:structure_block",
                "minecraft:structure_void", "minecraft:jigsaw", "minecraft:light", "minecraft:end_portal_frame",
                "minecraft:knowledge_book", "minecraft:test_block", "minecraft:test_instance_block"));
        /** How often (seconds) online inventories are scanned. */
        public int scanIntervalSeconds = 30;
    }

    public static class DupeWatch {
        public boolean enabled = true;
        public int windowSeconds = 60;
        /** Alert when a player's value of watched items grows by more than this in the window without a legit source. */
        public int valueJumpThreshold = 40;
        public Map<String, Integer> weights = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("minecraft:diamond", 1), Map.entry("minecraft:diamond_block", 9),
                Map.entry("minecraft:netherite_ingot", 10), Map.entry("minecraft:netherite_scrap", 3),
                Map.entry("minecraft:netherite_block", 90), Map.entry("minecraft:ancient_debris", 3),
                Map.entry("minecraft:elytra", 30), Map.entry("minecraft:totem_of_undying", 10),
                Map.entry("minecraft:shulker_box", 8), Map.entry("minecraft:enchanted_golden_apple", 20),
                Map.entry("minecraft:beacon", 40), Map.entry("minecraft:nether_star", 30),
                Map.entry("minecraft:emerald_block", 3)));
    }

    public static class Chat {
        public boolean enabled = true;
        public int maxMessagesPer10s = 6;
        public int minMillisBetween = 600;
        public int maxRepeats = 2;
        public int floodMaxLength = 256;
        public double maxCapsRatio = 0.7;
        public boolean blockLinks = true;
        public List<String> allowedDomains = new ArrayList<>(List.of("youtube.com", "youtu.be"));
        public boolean chatEnabled = true;
    }

    public static class Joins {
        public int maxNewAccountsPerMinute = 5;
        /** Joins from the same /24 range within a minute that count as a wave. */
        public int subnetWaveSize = 4;
        public boolean altDetection = true;
        public boolean maintenance = false;
    }

    public static class Staff {
        public boolean requirePin = true;
        public int maxPinAttempts = 5;
        public int pinLockMinutes = 30;
        public boolean freezeLogoutCreatesCase = false;
        public boolean teleportInvisibleByDefault = true;
        public Map<String, String> reasonTemplates = new LinkedHashMap<>(Map.of(
                "hacking", "Use of unfair modifications", "griefing", "Griefing", "spam", "Spamming",
                "toxicity", "Toxic behaviour", "xray", "X-ray"));
        /** Punishment ladder: after N warnings, apply this punishment. e.g. "3" -> "mute 1d". */
        public Map<String, String> ladder = new LinkedHashMap<>(Map.of("3", "mute 1d", "5", "tempban 3d"));
        public boolean ladderEnabled = false;
        public int blockLogRetentionDays = 30;
    }

    public static class Deaths {
        public int retentionDays = 30;
        public int maxPerPlayer = 100;
    }

    public static class Claims {
        public int minGap = 5;
        /** Archive (true) or delete (false) claims when they expire. */
        public boolean archiveOnExpiry = true;
        public int griefAlertAttempts = 5;
        public int griefAlertWindowSeconds = 30;
        public int maxClaimArea = 1_000_000;
    }

    public static class Redstone {
        public int maxPrimedTntPerChunk = 40;
        public double maxTntVelocity = 3.0;
        public boolean blockTntDupers = false;
        public int maxDispenserFiresPerChunkPerSecond = 20;
        public int clockMaxTogglesPer10s = 60;
        public int clockSustainSeconds = 30;
        public int maxPistonMovesPerChunkPerSecond = 80;
        public int maxEntitiesPerChunk = 200;
        public int maxMinecartsPerChunk = 40;
        public int maxItemsPerChunk = 400;
        public int maxArmorStandsPerChunk = 60;
    }

    public static class Lobby {
        public int lootChestRefillMinutes = 30;
        public boolean noHunger = true;
        public boolean noFallDamage = true;
        public boolean noPvp = true;
    }

    public static class Jail {
        public boolean onlineTimeOnly = true;
        public boolean allowChat = true;
        public boolean announce = true;
        public String releaseMessage = "You have been released from jail.";
    }

    public static class WaitingRoom {
        public boolean enabled = true;
        public long denyBanMillis = 24L * 60 * 60 * 1000;
        public boolean escalate = true;
        public List<String> escalationSteps = new ArrayList<>(List.of("24h", "3d", "7d"));
    }

    public static class Arenas {
        public int countdownSeconds = 5;
        public int queueTimeoutSeconds = 300;
        public int duelRequestSeconds = 30;
    }

    public static class Traders {
        public int minOffers = 4;
        public int maxOffers = 8;
        /** Base rotation interval (minutes) and random jitter (minutes). */
        public int rotationMinutes = 180;
        public int rotationJitterMinutes = 60;
        public double minMarkup = 1.2;
        public double maxMarkup = 1.5;
        /** 'Getting closer' when the offer is at least this fraction of the required value. */
        public double closeFraction = 0.65;
        /** Each extra unit of the same payment item is worth this fraction of the previous one. */
        public double diminishingFactor = 0.97;
        public double demandIncrease = 0.05;
        public double demandDecayPerHour = 0.01;
        public int weeklyRareCap = 5;
        public int weeklyLegendaryCap = 1;
        public int perPlayerLegendaryPerWeek = 1;
        public int perPlayerRarePerWeek = 3;
        public int tradesPerMinute = 20;
        public List<String> neverSell = new ArrayList<>(List.of(
                "minecraft:elytra", "minecraft:nether_star", "minecraft:beacon", "minecraft:enchanted_golden_apple",
                "minecraft:dragon_egg", "minecraft:heavy_core", "minecraft:mace"));
        /** Base values of raw materials. Crafted items are derived from recipes. */
        public Map<String, Double> baseValues = new LinkedHashMap<>();
        public int sellDailyCapPerPlayer = 256;
        public int sellDailyCapServer = 4096;
    }

    public static class PlayerTrade {
        public int requestSeconds = 30;
        public double maxDistance = 16.0;
        public int countdownSeconds = 3;
    }

    public static class Storage {
        /** "sqlite" or "mysql" (for mysql, put the MySQL driver jar in mods/ and set jdbcUrl). */
        public String type = "sqlite";
        public String jdbcUrl = "";
        public String user = "";
        public String password = "";
        public int logRetentionDays = 30;
        public boolean dailyBackups = true;
        public int backupsToKeep = 7;
    }

    public static class Discord {
        public String webhookUrl = "";
        public Map<String, Boolean> types = new LinkedHashMap<>(Map.of(
                "review", true, "claimExpiry", true, "grief", true, "lagMachine", true,
                "jail", true, "evidence", true, "ban", true));
    }

    public static class Fun {
        public boolean banEffects = true;
        public boolean caughtCounter = true;
    }

    /** The Watcher (section 33): atmosphere only, never touches the real world or the anti-cheat. */
    public static class Watcher {
        public boolean enabled = true;
        /** Each eligible player gets one event every this many minutes (random in between). */
        public int minMinutes = 30;
        public int maxMinutes = 75;
        /** Pool weights by effect id (appear, doppelganger, message, footsteps, ...). Missing ones use defaults. */
        public Map<String, Integer> weights = new LinkedHashMap<>();
        /** Effect ids turned off (e.g. "storm", "gift", "sleep_well"). */
        public List<String> disabledEffects = new ArrayList<>();
        /** The Watcher runs at the player and screams in their face just before reaching them. */
        public boolean rareRush = true;
        public double rareChance = 0.03;
        public double glitchChance = 0.1;
        public double bedsideChance = 0.05;
        public int messageCooldownMinMinutes = 60;
        public int messageCooldownMaxMinutes = 180;
        public boolean nightEnabled = true;
        public int nightMinDays = 7;
        public int nightMaxDays = 14;
        public String watchingText = "Something is watching...";
        public String glitchText = "ɪ ꜱᴇᴇ ʏᴏᴜ";
        public List<String> signLines = new ArrayList<>(List.of("I SEE YOU", "", "", ""));
        public String whisperFrom = "???";
        public String whisperText = "behind you";
        public String ownVoiceText = "look behind you";
        /** Shown (barely) before "behind you" jumpscares. */
        public String behindText = "turn around";
        /** How loud jumpscare screams are (0 to 1). */
        public double screamVolume = 1.0;
        public String sleepText = "Sleep well.";
        public String chestNoteName = "I was here";
        public String pocketGiftName = "You dropped this";
        /** Server list (multiplayer menu) messages shown now and then instead of the normal description. */
        public List<String> serverListMessages = new ArrayList<>(List.of("It's still here.", "Don't look behind you."));
        /** Chance per hour that the server list message changes. */
        public double serverListChancePerHour = 0.05;
        public int serverListMinutes = 10;
        /** Optional custom skin for the Watcher (signed textures from e.g. mineskin.org). Empty = dark outfit. */
        public String skinValue = "";
        public String skinSignature = "";
        /** Players are only eligible this long after their last hit given or taken. */
        public int combatSeconds = 15;
        /** The Watcher appears where a cheater stood just before the ban lightning (needs fun.banEffects). */
        public boolean banAppearance = true;
    }

    public static class Restarts {
        public boolean enabled = false;
        /** Times of day, 24h "HH:mm", server local time. */
        public List<String> times = new ArrayList<>(List.of("05:00"));
        public List<Integer> warnMinutes = new ArrayList<>(List.of(15, 5, 1));
        public boolean backupBeforeRestart = true;
    }

    /** Sensitivity multiplier for a check; defaults to 1. */
    public double sensitivity(String checkId) {
        if (detection.disabledChecks.contains(checkId)) {
            return 0;
        }
        return detection.sensitivity.getOrDefault(checkId, 1.0);
    }

    /** Fills in anything Gson left null because the file was partial. */
    public AcConfig normalize() {
        AcConfig d = new AcConfig();
        if (general == null) general = d.general;
        if (permissions == null) permissions = d.permissions;
        if (detection == null) detection = d.detection;
        if (detection.sensitivity == null) detection.sensitivity = new LinkedHashMap<>();
        if (detection.disabledChecks == null) detection.disabledChecks = new ArrayList<>();
        if (warnings == null) warnings = d.warnings;
        if (movement == null) movement = d.movement;
        if (combat == null) combat = d.combat;
        if (evidence == null) evidence = d.evidence;
        if (watchlist == null) watchlist = d.watchlist;
        if (exempt == null) exempt = d.exempt;
        if (xray == null) xray = d.xray;
        if (illegalItems == null) illegalItems = d.illegalItems;
        if (dupeWatch == null) dupeWatch = d.dupeWatch;
        if (chat == null) chat = d.chat;
        if (joins == null) joins = d.joins;
        if (staff == null) staff = d.staff;
        if (deaths == null) deaths = d.deaths;
        if (claims == null) claims = d.claims;
        if (redstone == null) redstone = d.redstone;
        if (lobby == null) lobby = d.lobby;
        if (jail == null) jail = d.jail;
        if (waitingRoom == null) waitingRoom = d.waitingRoom;
        if (arenas == null) arenas = d.arenas;
        if (traders == null) traders = d.traders;
        if (traders.baseValues == null) traders.baseValues = new LinkedHashMap<>();
        if (traders.neverSell == null) traders.neverSell = d.traders.neverSell;
        if (playerTrade == null) playerTrade = d.playerTrade;
        if (storage == null) storage = d.storage;
        if (discord == null) discord = d.discord;
        if (fun == null) fun = d.fun;
        if (restarts == null) restarts = d.restarts;
        if (watcher == null) watcher = d.watcher;
        if (watcher.weights == null) watcher.weights = new LinkedHashMap<>();
        if (watcher.disabledEffects == null) watcher.disabledEffects = new ArrayList<>();
        if (watcher.signLines == null) watcher.signLines = d.watcher.signLines;
        if (watcher.serverListMessages == null) watcher.serverListMessages = d.watcher.serverListMessages;
        return this;
    }

}
