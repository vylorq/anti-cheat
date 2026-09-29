package com.vylorq.anticheat.core.trade;

import java.util.UUID;

/**
 * State machine for a player-to-player trade (section 24): both click Ready, then both Confirm, then a
 * countdown. Any change on either side resets everything. The actual item swap is done by the Minecraft layer
 * in one step when {@link #tick} returns {@link Step#COMPLETE}.
 */
public final class SecureTrade {
    public enum Step { OPEN, BOTH_READY, CONFIRMED_COUNTDOWN, COMPLETE, CANCELLED }

    public enum CancelReason { CLOSED, DISCONNECTED, DIED, JAILED, FROZEN, TELEPORTED, TOO_FAR, INVENTORY_FULL,
        ILLEGAL_ITEM, BAD_CLICK, ADMIN, AREA }

    private final UUID a;
    private final UUID b;
    private final long countdownMillis;
    private boolean readyA;
    private boolean readyB;
    private boolean confirmA;
    private boolean confirmB;
    private long countdownStart = -1;
    private Step step = Step.OPEN;
    private CancelReason cancelReason;
    /** Increases on every change so a stale confirm (from before a change) can be rejected. */
    private int revision;

    public SecureTrade(UUID a, UUID b, long countdownMillis) {
        this.a = a;
        this.b = b;
        this.countdownMillis = countdownMillis;
    }

    public UUID a() {
        return a;
    }

    public UUID b() {
        return b;
    }

    public UUID other(UUID p) {
        return p.equals(a) ? b : a;
    }

    public boolean involves(UUID p) {
        return a.equals(p) || b.equals(p);
    }

    public Step step() {
        return step;
    }

    public int revision() {
        return revision;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public boolean isReady(UUID p) {
        return p.equals(a) ? readyA : readyB;
    }

    public boolean isConfirmed(UUID p) {
        return p.equals(a) ? confirmA : confirmB;
    }

    public boolean isFinished() {
        return step == Step.COMPLETE || step == Step.CANCELLED;
    }

    /** Items changed on either side: everyone back to not-ready and the countdown stops. */
    public synchronized void changed() {
        if (isFinished()) {
            return;
        }
        readyA = readyB = confirmA = confirmB = false;
        countdownStart = -1;
        step = Step.OPEN;
        revision++;
    }

    public synchronized boolean toggleReady(UUID p) {
        if (isFinished() || step == Step.CONFIRMED_COUNTDOWN) {
            return false;
        }
        if (p.equals(a)) {
            readyA = !readyA;
        } else if (p.equals(b)) {
            readyB = !readyB;
        } else {
            return false;
        }
        confirmA = confirmB = false;
        step = readyA && readyB ? Step.BOTH_READY : Step.OPEN;
        return true;
    }

    /** Confirm only works after both are ready, and only for the revision the player saw. */
    public synchronized boolean confirm(UUID p, int seenRevision, long now) {
        if (step != Step.BOTH_READY || seenRevision != revision) {
            return false;
        }
        if (p.equals(a)) {
            confirmA = true;
        } else if (p.equals(b)) {
            confirmB = true;
        } else {
            return false;
        }
        if (confirmA && confirmB) {
            step = Step.CONFIRMED_COUNTDOWN;
            countdownStart = now;
        }
        return true;
    }

    public synchronized void cancel(CancelReason reason) {
        if (isFinished()) {
            return;
        }
        step = Step.CANCELLED;
        cancelReason = reason;
    }

    /** Seconds left in the countdown, or -1. */
    public synchronized int secondsLeft(long now) {
        if (step != Step.CONFIRMED_COUNTDOWN) {
            return -1;
        }
        return (int) Math.ceil((countdownMillis - (now - countdownStart)) / 1000.0);
    }

    /** Advances the countdown. Returns true exactly once: when the swap must happen now. */
    public synchronized boolean tick(long now) {
        if (step == Step.CONFIRMED_COUNTDOWN && now - countdownStart >= countdownMillis) {
            step = Step.COMPLETE;
            return true;
        }
        return false;
    }

    /**
     * Inventory space check before swapping: can {@code incoming} stacks fit into free slots and partial stacks?
     *
     * @param freeSlots empty slots in the receiver's main inventory (after their own offered items leave)
     * @param mergeable number of incoming stacks that would merge fully into existing partial stacks
     */
    public static boolean fits(int incomingStacks, int freeSlots, int mergeable) {
        return incomingStacks - mergeable <= freeSlots;
    }
}
