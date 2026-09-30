package com.vylorq.anticheat.core.perm;

import java.util.Locale;

/**
 * Every protected action. The node is checked through a permissions API when installed,
 * otherwise the minimum role decides.
 */
public enum Perm {
    // Anti-cheat
    REVIEW(Role.ADMIN),
    INSPECT(Role.ADMIN),
    INSPECT_EDIT(Role.ADMIN),
    INSPECT_PRIVATE(Role.OWNER),
    SPECTATE(Role.ADMIN),
    TELEPORT(Role.ADMIN),
    WATCH(Role.ADMIN),
    EXEMPT(Role.OWNER),
    SHADOW(Role.ADMIN),
    ALERTS(Role.ADMIN),
    STATS(Role.ADMIN),
    RELOAD(Role.ADMIN),
    // Staff tools
    FREEZE(Role.ADMIN),
    WARN(Role.ADMIN),
    MUTE(Role.ADMIN),
    KICK(Role.ADMIN),
    BAN(Role.ADMIN),
    VANISH(Role.ADMIN),
    STAFF_CHAT(Role.ADMIN),
    MAINTENANCE(Role.ADMIN),
    ROLLBACK(Role.ADMIN),
    INSPECTOR_TOOL(Role.ADMIN),
    LAG(Role.ADMIN),
    SETTINGS(Role.ADMIN),
    DEATHS(Role.ADMIN),
    DEATH_RESTORE(Role.ADMIN),
    STAFF_LOG(Role.OWNER),
    MANAGE_ADMINS(Role.OWNER),
    EVENTS(Role.ADMIN),
    RESTART(Role.ADMIN),
    /** The Watcher (section 33): owner only. */
    WATCHER(Role.OWNER),
    // World
    CLAIM(Role.ADMIN),
    BARRIER(Role.ADMIN),
    LOBBY_ADMIN(Role.ADMIN),
    JAIL(Role.ADMIN),
    WAITING_ROOM(Role.ADMIN),
    ARENA_ADMIN(Role.ADMIN),
    TRADER_ADMIN(Role.ADMIN),
    REDSTONE_WHITELIST(Role.ADMIN),
    // Player commands
    REPORT(Role.PLAYER),
    TRADE(Role.PLAYER),
    ARENA_PLAY(Role.PLAYER),
    LOBBY(Role.PLAYER),
    REQUEST_JOIN(Role.PLAYER);

    private final Role minimum;
    private final String node;

    Perm(Role minimum) {
        this.minimum = minimum;
        this.node = "vigil." + name().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    public Role minimum() {
        return minimum;
    }

    public String node() {
        return node;
    }
}
