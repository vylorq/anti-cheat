package com.vylorq.anticheat.core.trader;

import com.vylorq.anticheat.core.util.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** How this trader is paid: "default" (server setting), "items", "emeralds" or "cash". */
    public String payment = "default";
    /** Owner's own stock: item id -> how many, always in this trader's offers. */
    public Map<String, Integer> pinned = new LinkedHashMap<>();
    /** Items the owner took off this trader: never in its offers. */
    public Set<String> blocked = new LinkedHashSet<>();
}
