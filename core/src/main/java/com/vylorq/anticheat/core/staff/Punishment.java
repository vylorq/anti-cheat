package com.vylorq.anticheat.core.staff;

import com.vylorq.anticheat.core.util.Durations;

import java.util.UUID;

/** A punishment record. Only admins create these; the system never does on its own. */
public final class Punishment {
    public enum Type { WARN, MUTE, KICK, BAN, JAIL, DENY }

    public long id;
    public Type type;
    public UUID player;
    public String playerName;
    public String reason;
    public String by;
    public long at;
    /** {@link Durations#PERMANENT} for permanent; 0 for instant ones (kick/warn). */
    public long expiresAt;
    public boolean revoked;
    public String revokedBy;
    public long revokedAt;
    /** Ban for cheating (drives the ban effect and caught counter). */
    public boolean cheating;

    public boolean isActive(long now) {
        if (revoked) {
            return false;
        }
        return switch (type) {
            case BAN, MUTE, JAIL, DENY -> expiresAt == Durations.PERMANENT || expiresAt > now;
            default -> false;
        };
    }

    public long remaining(long now) {
        return expiresAt == Durations.PERMANENT ? Durations.PERMANENT : Math.max(0, expiresAt - now);
    }
}
