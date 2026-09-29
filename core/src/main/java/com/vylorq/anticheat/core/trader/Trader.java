package com.vylorq.anticheat.core.trader;

import com.vylorq.anticheat.core.util.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A frozen villager trader placed with the Trader Stick (section 23). */
public final class Trader {
    public enum Type { SELLS, BUYS, REQUESTS, MYSTERY }

    public UUID entity;
    public String name;
    public Specialty specialty = Specialty.LIBRARIAN;
    public Type type = Type.SELLS;
    /** Villager biome type, e.g. "minecraft:plains". */
    public String look = "minecraft:plains";
    public Location location;
    public List<TraderOffer> offers = new ArrayList<>();
    public long nextRotation;
    /** Markup this rotation (e.g. 1.2-1.5): the trader's "mood". */
    public double mood = 1.3;
    public long createdAt;
    public String createdBy;
    /** Request trader: current request. */
    public String requestItem;
    public int requestCount;
    public String requestDay;
}
