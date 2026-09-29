package com.vylorq.anticheat.core.movement;

import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Outcome of checking one movement packet. */
public final class MoveResult {
    public record Violation(CheckType check, double points, String detail) {
    }

    public static final MoveResult OK = new MoveResult();

    public final List<Violation> violations = new ArrayList<>();
    /** Where to pull the player back to, or null. Must be validated with {@link SafeSpot}. */
    public Vec3 setbackTo;
    /** Checks that failed this packet (even while still inside the buffer). */
    public final List<CheckType> failed = new ArrayList<>();

    public boolean isClean() {
        return violations.isEmpty() && setbackTo == null && failed.isEmpty();
    }
}
