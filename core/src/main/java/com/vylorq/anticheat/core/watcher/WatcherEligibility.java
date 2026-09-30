package com.vylorq.anticheat.core.watcher;

/**
 * When the Watcher must never appear (section 33.1). Built only from what the player is doing right now; it never
 * looks at anti-cheat data (flags, watchlist, shadow mode, exempt list), so it can't reveal an investigation (33.8).
 */
public final class WatcherEligibility {
    public boolean enabled = true;
    public boolean excluded;
    public boolean creativeOrSpectator;
    public boolean dead;
    public boolean inCombat;
    public boolean inArena;
    public boolean trading;
    /** Any menu open (trader menus, trade windows, admin menus, chests during other effects). */
    public boolean menuOpen;
    public boolean jailed;
    public boolean frozen;
    public boolean waitingRoom;
    public boolean staffSpectating;

    /** @return why the Watcher can't act now, or null when it can. */
    public String blockedReason() {
        if (!enabled) return "disabled";
        if (excluded) return "excluded";
        if (creativeOrSpectator) return "gamemode";
        if (dead) return "dead";
        if (inCombat) return "combat";
        if (inArena) return "arena";
        if (trading) return "trading";
        if (menuOpen) return "menu";
        if (jailed) return "jail";
        if (frozen) return "frozen";
        if (waitingRoom) return "waiting room";
        if (staffSpectating) return "spectating";
        return null;
    }

    public boolean allowed() {
        return blockedReason() == null;
    }
}
