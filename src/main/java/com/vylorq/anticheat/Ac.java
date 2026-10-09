package com.vylorq.anticheat;

import com.vylorq.anticheat.core.arena.ArenaManager;
import com.vylorq.anticheat.core.arena.PlayerSnapshot;
import com.vylorq.anticheat.core.barrier.BarrierManager;
import com.vylorq.anticheat.core.chat.ChatFilter;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.deaths.DeathLog;
import com.vylorq.anticheat.core.detect.DetectionEngine;
import com.vylorq.anticheat.core.detect.ExemptList;
import com.vylorq.anticheat.core.detect.ShadowMode;
import com.vylorq.anticheat.core.detect.ViolationTracker;
import com.vylorq.anticheat.core.detect.WarningPolicy;
import com.vylorq.anticheat.core.detect.Watchlist;
import com.vylorq.anticheat.core.evidence.ClipStore;
import com.vylorq.anticheat.core.evidence.EvidenceRecorder;
import com.vylorq.anticheat.core.extras.DiscordWebhook;
import com.vylorq.anticheat.core.items.DupeWatch;
import com.vylorq.anticheat.core.jail.JailManager;
import com.vylorq.anticheat.core.joins.JoinGuard;
import com.vylorq.anticheat.core.lang.Lang;
import com.vylorq.anticheat.core.lobby.Lobby;
import com.vylorq.anticheat.core.movement.MovementPredictor;
import com.vylorq.anticheat.core.perm.AdminPins;
import com.vylorq.anticheat.core.redstone.LagMachineDetector;
import com.vylorq.anticheat.core.review.ReviewManager;
import com.vylorq.anticheat.core.staff.PunishmentManager;
import com.vylorq.anticheat.core.staff.Reports;
import com.vylorq.anticheat.core.staff.StaffState;
import com.vylorq.anticheat.core.stats.AcStats;
import com.vylorq.anticheat.core.storage.Database;
import com.vylorq.anticheat.core.storage.LogWriter;
import com.vylorq.anticheat.core.storage.StateStore;
import com.vylorq.anticheat.core.trader.ItemValues;
import com.vylorq.anticheat.core.trader.Trader;
import com.vylorq.anticheat.core.trader.TraderEconomy;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.waiting.WaitingRoom;
import com.vylorq.anticheat.core.watcher.WatcherScheduler;
import com.vylorq.anticheat.core.xray.OreAlerts;
import com.vylorq.anticheat.core.xray.XrayTrap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds every service for the running server. Created on server start, torn down on stop.
 * Static accessors keep feature code short.
 */
public final class Ac {
    public static final String MOD_ID = "vigil";
    /** The mod's id before it was renamed to Vigil; its data folder is moved on first start. */
    public static final String OLD_MOD_ID = "anticheat";
    public static final Logger LOG = LoggerFactory.getLogger("Vigil");

    /** Extra persisted things that don't belong to a core module. */
    public static final class Misc {
        public long xraySecret;
        public boolean waitingRoomSeeded;
        public Map<String, Boolean> featureFlags = new LinkedHashMap<>();
        /** Players who logged out while frozen, to handle on return. */
        public Set<UUID> frozenLogout = new java.util.HashSet<>();
        /** First join time per player. */
        public Map<UUID, Long> firstJoin = new LinkedHashMap<>();
        /** Total playtime per player (millis). */
        public Map<UUID, Long> playtime = new LinkedHashMap<>();
        /** Last logout location per player. */
        public Map<UUID, com.vylorq.anticheat.core.util.Location> lastLogout = new LinkedHashMap<>();
        /** Players who get told something on next login (e.g. release, trade returns). */
        public Map<UUID, List<String>> pendingMessages = new LinkedHashMap<>();
        /** Players who get a Staff alert when they join, because a review arrived while offline. */
        public boolean explosionsEnabled = true;
        /** Temporary admins and the state to put back when their visit ends. */
        public Map<UUID, com.vylorq.anticheat.feature.TempAdmins.Grant> tempAdmins = new LinkedHashMap<>();
        public Map<UUID, com.vylorq.anticheat.feature.BuilderMode.Builder> builders = new LinkedHashMap<>();
        /** Builders added while offline (lower-case name -> grant): it starts when they next join. */
        public Map<String, com.vylorq.anticheat.feature.BuilderMode.Pending> pendingBuilders = new LinkedHashMap<>();
        public Map<UUID, com.vylorq.anticheat.feature.BuilderDrafts.Draft> drafts = new LinkedHashMap<>();
        /** Everyone who has been a builder (so their logs can be found by name). */
        public Map<UUID, String> builderNames = new LinkedHashMap<>();
        /** Who accepted the jumpscare warning (and which version of it). */
        public Map<UUID, Integer> scareAccepted = new LinkedHashMap<>();
        /** Who chose to play without scares (when declining doesn't disconnect). */
        public java.util.Set<UUID> scareDeclined = new java.util.LinkedHashSet<>();
        public com.vylorq.anticheat.feature.WorldEvents.State events = new com.vylorq.anticheat.feature.WorldEvents.State();
        public String lastWorldBackupDay = "";
        /** Language each player chose with /language ("auto" when missing). */
        public Map<UUID, String> languages = new LinkedHashMap<>();
        /** Admins who turned menu and message sounds off for themselves. */
        public Set<UUID> quietUi = new java.util.HashSet<>();
        /** What the last staff inventory wipe removed per player (encoded slots), so it can be undone. */
        public Map<UUID, List<String>> inventoryBackups = new LinkedHashMap<>();
        /** Private staff notes per player. */
        public Map<UUID, List<com.vylorq.anticheat.gui.Notes.Note>> notes = new LinkedHashMap<>();
    }

    /** Items held by the mod on a player's behalf (trade windows, trader offers). Returned after a crash. */
    public static final class Escrow {
        /** "owner-uuid|context" -> encoded stacks currently held. */
        public Map<String, List<String>> held = new LinkedHashMap<>();
    }

    public static final class Traders {
        public Map<UUID, Trader> traders = new LinkedHashMap<>();
        /** Sell-to rules: item id -> [amount, reward item id, reward count] as strings. */
        public Map<String, String[]> sellRules = new LinkedHashMap<>(Map.of(
                "minecraft:wheat", new String[]{"32", "minecraft:emerald", "1"},
                "minecraft:rotten_flesh", new String[]{"32", "minecraft:emerald", "1"},
                "minecraft:string", new String[]{"16", "minecraft:emerald", "1"},
                "minecraft:paper", new String[]{"24", "minecraft:emerald", "1"}));
    }

    private static Ac instance;

    public final MinecraftServer server;
    public final Path dir;
    public final ConfigManager configManager;
    public final Lang lang = new Lang();
    public final Clock clock = Clock.SYSTEM;
    public Database db;
    public LogWriter logs;
    public StateStore state;

    public ViolationTracker violations;
    public WarningPolicy warnings;
    public Watchlist watchlist;
    public ExemptList exempt;
    public ShadowMode shadow;
    public ReviewManager reviews;
    public EvidenceRecorder evidence;
    public ClipStore clips;
    public AcStats stats;
    public DetectionEngine engine;
    public MovementPredictor predictor;
    public AdminPins pins;
    public PunishmentManager punishments;
    public Reports reports;
    public StaffState staff;
    public DeathLog deaths;
    public ClaimManager claims;
    public BarrierManager barriers;
    public com.vylorq.anticheat.core.team.TeamManager teams;
    public com.vylorq.anticheat.core.market.Market market;
    public LagMachineDetector redstone;
    public XrayTrap xrayTrap;
    public final OreAlerts oreAlerts = new OreAlerts();
    public final DupeWatch dupeWatch = new DupeWatch();
    public final ChatFilter chatFilter = new ChatFilter();
    public JoinGuard joins;
    public Lobby lobby;
    public JailManager jail;
    public WaitingRoom waitingRoom;
    public ArenaManager arenas;
    public TraderEconomy economy;
    public Traders traders;
    public ItemValues itemValues;
    public Escrow escrow;
    public Misc misc;
    public com.vylorq.anticheat.feature.EndLock.Data end;
    public WatcherScheduler watcher;
    public final DiscordWebhook discord = new DiscordWebhook();

    public final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    private Ac(MinecraftServer server) {
        this.server = server;
        this.dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        this.configManager = new ConfigManager(dir);
    }

    // ---- static access ----

    public static Ac get() {
        return instance;
    }

    public static boolean running() {
        return instance != null;
    }

    public static MinecraftServer server() {
        return instance.server;
    }

    public static AcConfig config() {
        return instance.configManager.get();
    }

    public static Lang lang() {
        return instance.lang;
    }

    public static PlayerSession session(ServerPlayerEntity p) {
        return instance.sessions.computeIfAbsent(p.getUuid(), k -> new PlayerSession(k, p.getGameProfile().name()));
    }

    public static PlayerSession sessionOrNull(UUID id) {
        return instance == null ? null : instance.sessions.get(id);
    }

    // ---- lifecycle ----

    public static void start(MinecraftServer server) {
        migrateFromAntiCheat(FabricLoader.getInstance().getConfigDir());
        Ac ac = new Ac(server);
        instance = ac;
        ac.init();
    }

    /**
     * The mod used to be called "anticheat". Moves config/anticheat to config/vigil (config, database, language
     * files, evidence, backups, snapshots) the first time Vigil starts, so nothing is lost. Safe to call every start.
     */
    public static void migrateFromAntiCheat(Path configDir) {
        Path oldDir = configDir.resolve(OLD_MOD_ID);
        Path newDir = configDir.resolve(MOD_ID);
        try {
            if (java.nio.file.Files.isDirectory(oldDir) && !java.nio.file.Files.exists(newDir)) {
                java.nio.file.Files.move(oldDir, newDir);
                LOG.info("Moved {} to {} (the mod is now called Vigil).", oldDir, newDir);
            }
            Path oldDb = newDir.resolve(OLD_MOD_ID + ".db");
            Path newDb = newDir.resolve(MOD_ID + ".db");
            if (java.nio.file.Files.exists(oldDb) && !java.nio.file.Files.exists(newDb)) {
                java.nio.file.Files.move(oldDb, newDb);
                LOG.info("Renamed the database to {}.", newDb.getFileName());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Vigil could not move its old data from " + oldDir + " to " + newDir
                    + ". Move that folder by hand, then start again.", e);
        }
    }

    private void init() {
        String err = configManager.load();
        if (err != null) {
            LOG.error(err);
        }
        AcConfig cfg = config();
        lang.load(cfg.general.language, dir.resolve("lang"));
        try {
            if ("mysql".equalsIgnoreCase(cfg.storage.type) && !cfg.storage.jdbcUrl.isBlank()) {
                db = Database.openMysql(cfg.storage.jdbcUrl, cfg.storage.user, cfg.storage.password);
            } else {
                db = Database.openSqlite(dir.resolve("vigil.db"));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Vigil could not open its database", e);
        }
        logs = new LogWriter(db, e -> LOG.error("Log write failed", e));
        state = new StateStore(db, e -> LOG.error("State save failed", e));

        watchlist = new Watchlist(state.load("watchlist", Watchlist.Data.class, null), clock);
        exempt = new ExemptList(state.load("exempt", ExemptList.Data.class, null));
        shadow = new ShadowMode(state.load("shadow", ShadowMode.Data.class, null));
        reviews = new ReviewManager(state.load("reviews", ReviewManager.Data.class, null), clock);
        stats = new AcStats(state.load("stats", AcStats.Data.class, null));
        pins = new AdminPins(state.load("pins", PinData.class, new PinData()).pins, clock,
                cfg.staff.maxPinAttempts, cfg.staff.pinLockMinutes * Durations.MINUTE);
        punishments = new PunishmentManager(state.load("punishments", PunishmentManager.Data.class, null), clock);
        reports = new Reports(state.load("reports", Reports.Data.class, null));
        staff = new StaffState(state.load("staff", StaffState.Data.class, null));
        deaths = new DeathLog(state.load("deaths", DeathLog.Data.class, null), clock);
        claims = new ClaimManager(state.load("claims", ClaimManager.Data.class, null), clock);
        barriers = new BarrierManager(state.load("barriers", BarrierManager.Data.class, null), clock);
        teams = new com.vylorq.anticheat.core.team.TeamManager(state.load("teams", com.vylorq.anticheat.core.team.TeamManager.Data.class, null), clock);
        market = new com.vylorq.anticheat.core.market.Market(state.load("market", com.vylorq.anticheat.core.market.Market.Data.class, null), clock);
        redstone = new LagMachineDetector(state.load("redstone", LagMachineDetector.Data.class, null));
        joins = new JoinGuard(state.load("joins", JoinGuard.Data.class, null));
        lobby = new Lobby(state.load("lobby", Lobby.Data.class, null));
        jail = new JailManager(state.load("jail", JailManager.Data.class, null), clock);
        waitingRoom = new WaitingRoom(state.load("waiting", WaitingRoom.Data.class, null), clock);
        arenas = new ArenaManager(state.load("arenas", ArenaManager.Data.class, null), clock);
        economy = new TraderEconomy(state.load("economy", TraderEconomy.Data.class, null), clock);
        traders = state.load("traders", Traders.class, new Traders());
        escrow = state.load("escrow", Escrow.class, new Escrow());
        misc = state.load("misc", Misc.class, new Misc());
        end = state.load("end", com.vylorq.anticheat.feature.EndLock.Data.class, new com.vylorq.anticheat.feature.EndLock.Data());
        watcher = new WatcherScheduler(state.load("watcher", WatcherScheduler.Data.class, null), clock, new java.util.Random());
        if (misc.xraySecret == 0) {
            misc.xraySecret = new SecureRandom().nextLong();
            markDirty("misc");
        }
        xrayTrap = new XrayTrap(misc.xraySecret);
        staff.setMaintenance(staff.maintenance() || cfg.joins.maintenance);

        violations = new ViolationTracker(clock, cfg.detection.decayPerMinute, cfg.detection.suspicionScale);
        warnings = new WarningPolicy(clock);
        evidence = new EvidenceRecorder(clock, cfg.evidence.clipSeconds, cfg.evidence.watchedClipSeconds, cfg.evidence.clipCooldownSeconds);
        clips = new ClipStore(dir.resolve("evidence"));
        engine = new DetectionEngine(Ac::config, clock, violations, warnings, watchlist, exempt, shadow, reviews, evidence, stats);
        predictor = new MovementPredictor(movementSettings(cfg));
        LOG.info("Vigil started (storage: {}).", db.dialect());
    }

    /** Applies config changes live (/ac reload). */
    public String reload() {
        String err = configManager.load();
        AcConfig cfg = config();
        lang.load(cfg.general.language, dir.resolve("lang"));
        violations.configure(cfg.detection.decayPerMinute, cfg.detection.suspicionScale);
        evidence.configure(cfg.evidence.clipSeconds, cfg.evidence.watchedClipSeconds, cfg.evidence.clipCooldownSeconds);
        MovementPredictor.Settings s = movementSettings(cfg);
        MovementPredictor.Settings live = predictor.settings();
        live.setbacks = s.setbacks;
        live.graceSeconds = s.graceSeconds;
        live.bufferLimit = s.bufferLimit;
        live.bufferDecay = s.bufferDecay;
        live.speedTolerance = s.speedTolerance;
        live.verticalTolerance = s.verticalTolerance;
        live.bedrockLeniency = s.bedrockLeniency;
        live.velocityGraceTicks = s.velocityGraceTicks;
        live.maxPingCompensationMs = s.maxPingCompensationMs;
        live.lagTpsThreshold = s.lagTpsThreshold;
        itemValues = null;
        return err;
    }

    static MovementPredictor.Settings movementSettings(AcConfig cfg) {
        MovementPredictor.Settings s = new MovementPredictor.Settings();
        s.setbacks = cfg.movement.setbacks;
        s.graceSeconds = cfg.movement.graceSeconds;
        s.bufferLimit = cfg.movement.bufferLimit;
        s.bufferDecay = cfg.movement.bufferDecay;
        s.speedTolerance = cfg.movement.speedTolerance;
        s.verticalTolerance = cfg.movement.verticalTolerance;
        s.bedrockLeniency = cfg.movement.bedrockLeniency;
        s.velocityGraceTicks = cfg.movement.velocityGraceTicks;
        s.maxPingCompensationMs = cfg.movement.maxPingCompensationMs;
        s.lagTpsThreshold = cfg.general.lagTpsThreshold;
        return s;
    }

    /** Stored admin PINs. */
    public static final class PinData {
        public Map<UUID, AdminPins.Entry> pins = new HashMap<>();
    }

    // ---- persistence ----

    public static void markDirty(String key) {
        if (instance != null) {
            instance.dirty.add(key);
        }
    }

    private Object stateFor(String key) {
        return switch (key) {
            case "watchlist" -> watchlist.data();
            case "exempt" -> exempt.data();
            case "shadow" -> shadow.data();
            case "reviews" -> reviews.data();
            case "stats" -> stats.data();
            case "pins" -> {
                PinData d = new PinData();
                d.pins = pins.data();
                yield d;
            }
            case "punishments" -> punishments.data();
            case "reports" -> reports.data();
            case "staff" -> staff.data();
            case "deaths" -> deaths.data();
            case "claims" -> claims.data();
            case "barriers" -> barriers.data();
            case "teams" -> teams.data();
            case "market" -> market.data();
            case "redstone" -> redstone.data();
            case "joins" -> joins.data();
            case "lobby" -> lobby.data();
            case "jail" -> jail.data();
            case "waiting" -> waitingRoom.data();
            case "arenas" -> arenas.data();
            case "economy" -> economy.data();
            case "traders" -> traders;
            case "escrow" -> escrow;
            case "misc" -> misc;
            case "end" -> end;
            case "watcher" -> watcher.data();
            default -> null;
        };
    }

    public static final List<String> ALL_KEYS = List.of("watchlist", "exempt", "shadow", "reviews", "stats", "pins",
            "punishments", "reports", "staff", "deaths", "claims", "barriers", "redstone", "joins", "lobby", "jail",
            "waiting", "arenas", "economy", "traders", "escrow", "misc", "watcher", "end", "teams", "market");

    /** Saves dirty modules (called every few seconds on the server thread). */
    public void saveDirty() {
        if (dirty.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(dirty);
        dirty.removeAll(keys);
        for (String k : keys) {
            Object o = stateFor(k);
            if (o != null) {
                state.save(k, o);
            }
        }
    }

    /** Saves one module right now (for things that must survive a crash, like escrow). */
    public static void saveNow(String key) {
        if (instance != null) {
            Object o = instance.stateFor(key);
            if (o != null) {
                instance.dirty.remove(key);
                instance.state.save(key, o);
            }
        }
    }

    public static void stop() {
        Ac ac = instance;
        if (ac == null) {
            return;
        }
        try {
            for (String k : ALL_KEYS) {
                Object o = ac.stateFor(k);
                if (o != null) {
                    ac.state.saveNow(k, o);
                }
            }
            ac.state.close();
            ac.logs.close();
            ac.db.close();
        } catch (Exception e) {
            LOG.error("Error while stopping Vigil", e);
        }
        instance = null;
    }

    // ---- small shared helpers ----

    /**
     * Records exactly which items the mod is holding for a player in a window (trade, trader offer). Saved at
     * once, so after a crash the items are returned on the player's next login. An empty list clears it.
     */
    public void setEscrow(UUID owner, String context, List<String> encoded) {
        String key = owner + "|" + context;
        if (encoded == null || encoded.isEmpty()) {
            if (escrow.held.remove(key) == null) {
                return;
            }
        } else {
            escrow.held.put(key, new ArrayList<>(encoded));
        }
        saveNow("escrow");
    }

    /** Removes and returns everything held for a player (used at login after a crash). */
    public List<String> takeAllEscrow(UUID owner) {
        List<String> out = new ArrayList<>();
        String prefix = owner + "|";
        escrow.held.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                out.addAll(e.getValue());
                return true;
            }
            return false;
        });
        if (!out.isEmpty()) {
            saveNow("escrow");
        }
        return out;
    }

    public void pendingMessage(UUID id, String message) {
        misc.pendingMessages.computeIfAbsent(id, k -> new ArrayList<>()).add(message);
        markDirty("misc");
    }

    public void savePendingRestore(PlayerSnapshot s) {
        arenas.savePending(s);
        saveNow("arenas");
    }
}
