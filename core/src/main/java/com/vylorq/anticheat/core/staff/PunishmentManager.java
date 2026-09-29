package com.vylorq.anticheat.core.staff;

import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Warns, mutes, kicks, bans (section 15), plus the optional punishment ladder. */
public final class PunishmentManager {
    public static final class Data {
        public long nextId = 1;
        public Map<Long, Punishment> all = new TreeMap<>();
    }

    /** What the ladder says to do after a warning. */
    public record LadderStep(Punishment.Type type, long duration) {
    }

    private final Data data;
    private final Clock clock;

    public PunishmentManager(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public synchronized Punishment add(Punishment.Type type, UUID player, String name, String reason, String by,
                                       long duration) {
        Punishment p = new Punishment();
        p.id = data.nextId++;
        p.type = type;
        p.player = player;
        p.playerName = name;
        p.reason = reason == null || reason.isBlank() ? "No reason given" : reason;
        p.by = by;
        p.at = clock.nowMillis();
        p.expiresAt = switch (type) {
            case WARN, KICK -> 0;
            default -> Durations.expiryFrom(p.at, duration);
        };
        data.all.put(p.id, p);
        return p;
    }

    public synchronized Punishment active(UUID player, Punishment.Type type) {
        long now = clock.nowMillis();
        Punishment best = null;
        for (Punishment p : data.all.values()) {
            if (p.player.equals(player) && p.type == type && p.isActive(now)) {
                if (best == null || p.expiresAt == Durations.PERMANENT
                        || (best.expiresAt != Durations.PERMANENT && p.expiresAt > best.expiresAt)) {
                    best = p;
                }
            }
        }
        return best;
    }

    public boolean isBanned(UUID player) {
        return active(player, Punishment.Type.BAN) != null || active(player, Punishment.Type.DENY) != null;
    }

    public boolean isMuted(UUID player) {
        return active(player, Punishment.Type.MUTE) != null;
    }

    /** Revokes all active punishments of a type. @return how many were revoked. */
    public synchronized int revoke(UUID player, Punishment.Type type, String by) {
        long now = clock.nowMillis();
        int n = 0;
        for (Punishment p : data.all.values()) {
            if (p.player.equals(player) && p.type == type && p.isActive(now)) {
                p.revoked = true;
                p.revokedBy = by;
                p.revokedAt = now;
                n++;
            }
        }
        return n;
    }

    public synchronized List<Punishment> history(UUID player) {
        List<Punishment> out = new ArrayList<>();
        for (Punishment p : data.all.values()) {
            if (p.player.equals(player)) {
                out.add(p);
            }
        }
        return out;
    }

    public synchronized int count(UUID player, Punishment.Type type) {
        int n = 0;
        for (Punishment p : data.all.values()) {
            if (p.player.equals(player) && p.type == type && !p.revoked) {
                n++;
            }
        }
        return n;
    }

    public synchronized int countByIpDenials(List<UUID> accounts) {
        int n = 0;
        for (UUID id : accounts) {
            n += count(id, Punishment.Type.DENY);
        }
        return n;
    }

    /**
     * Punishment ladder: e.g. {"3": "mute 1d", "5": "tempban 3d"}. Returns the step for the player's warn count,
     * or null.
     */
    public LadderStep ladderStep(UUID player, Map<String, String> ladder) {
        int warns = count(player, Punishment.Type.WARN);
        String rule = ladder.get(String.valueOf(warns));
        if (rule == null) {
            return null;
        }
        return parseStep(rule);
    }

    public static LadderStep parseStep(String rule) {
        String[] parts = rule.trim().split("\\s+");
        Punishment.Type type = switch (parts[0].toLowerCase()) {
            case "mute" -> Punishment.Type.MUTE;
            case "kick" -> Punishment.Type.KICK;
            case "ban", "tempban" -> Punishment.Type.BAN;
            case "jail" -> Punishment.Type.JAIL;
            default -> null;
        };
        if (type == null) {
            return null;
        }
        long d = parts.length > 1 ? Durations.parse(parts[1]).orElse(Durations.PERMANENT) : Durations.PERMANENT;
        return new LadderStep(type, d);
    }

    /** "You are temporarily banned. Time remaining: 24h 0m." style screen (never reveals the reason for denials). */
    public String banScreen(Punishment p) {
        long now = clock.nowMillis();
        if (p.type == Punishment.Type.DENY) {
            return "You are temporarily banned. Time remaining: " + Durations.format(p.remaining(now)) + ".";
        }
        if (p.expiresAt == Durations.PERMANENT) {
            return "You are banned from this server.\nReason: " + p.reason;
        }
        return "You are temporarily banned. Time remaining: " + Durations.format(p.remaining(now)) + ".\nReason: " + p.reason;
    }
}
