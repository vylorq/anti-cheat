package com.vylorq.anticheat.core.trader;

import com.vylorq.anticheat.core.item.ItemInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Judges a player's offer (section 23.3/23.4). The payment must be worth more than the item (markup and mood),
 * each extra unit of the same payment item is worth less, damaged items less, and shulker boxes / bundles are
 * never accepted.
 */
public final class OfferEvaluator {
    public enum Verdict { WAY_TOO_LOW, GETTING_CLOSER, DEAL, REJECTED }

    public record Result(Verdict verdict, double offered, double required, String reason) {
    }

    private OfferEvaluator() {
    }

    public static boolean forbiddenPayment(ItemInfo i) {
        return i.id.endsWith("shulker_box") || i.id.equals("minecraft:bundle") || i.id.endsWith("_bundle");
    }

    /** One kind of item in an offer: how many and what they're worth together. */
    public record Line(String id, int count, double value) {
    }

    /**
     * Total payment value with diminishing returns: the k-th unit of the same item type is worth
     * {@code unit * factor^k}, so huge amounts of cheap items never add up to a valuable one.
     */
    public static double paymentValue(List<ItemInfo> payment, ItemValues values, double factor) {
        double total = 0;
        for (Line l : breakdown(payment, values, factor)) {
            total += l.value();
        }
        return total;
    }

    /** What each kind of item in the payment is worth (same rules as {@link #paymentValue}). */
    public static List<Line> breakdown(List<ItemInfo> payment, ItemValues values, double factor) {
        Map<String, double[]> byType = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>();
        for (ItemInfo i : payment) {
            if (i == null || i.isEmpty()) {
                continue;
            }
            double unit = values.unitValue(i);
            String key = i.id + i.enchantments + i.storedEnchantments;
            ids.put(key, i.id);
            double[] acc = byType.computeIfAbsent(key, k -> new double[2]);
            // Average unit value weighted by count (damage can differ between stacks).
            acc[0] = (acc[0] * acc[1] + unit * i.count) / (acc[1] + i.count);
            acc[1] += i.count;
        }
        List<Line> out = new java.util.ArrayList<>();
        for (Map.Entry<String, double[]> e : byType.entrySet()) {
            double n = e.getValue()[1];
            double unit = e.getValue()[0];
            out.add(new Line(ids.get(e.getKey()), (int) n, factor >= 1.0 ? unit * n : unit * (1 - Math.pow(factor, n)) / (1 - factor)));
        }
        return out;
    }

    /** What the trader's item is worth before markup. */
    public static double itemValue(TraderOffer offer, ItemValues values) {
        ItemInfo sold = new ItemInfo(offer.id, offer.unit);
        sold.enchantments.putAll(offer.enchantments);
        sold.storedEnchantments.putAll(offer.storedEnchantments);
        double v = values.unitValue(sold) * offer.unit;
        if (offer.potion != null) {
            v += 6 * offer.unit;
        }
        return v;
    }

    /** The price to pay: value x demand x the trader's mood x the market price. */
    public static double price(TraderOffer offer, ItemValues values, double demandMultiplier, double mood, double market) {
        return itemValue(offer, values) * demandMultiplier * mood * market;
    }

    /** @param illegal reason from the illegal-item check for any payment item, or null */
    public static Result evaluate(TraderOffer offer, List<ItemInfo> payment, ItemValues values, double demandMultiplier,
                                  double mood, double factor, double closeFraction, String illegal) {
        if (illegal != null) {
            return new Result(Verdict.REJECTED, 0, 0, illegal);
        }
        for (ItemInfo i : payment) {
            if (i != null && !i.isEmpty() && forbiddenPayment(i)) {
                return new Result(Verdict.REJECTED, 0, 0, "shulker boxes and bundles can't be used as payment");
            }
        }
        double required = itemValue(offer, values) * demandMultiplier * mood;
        double offered = paymentValue(payment, values, factor);
        Verdict v;
        if (offered >= required) {
            v = Verdict.DEAL;
        } else if (offered >= required * closeFraction) {
            v = Verdict.GETTING_CLOSER;
        } else {
            v = Verdict.WAY_TOO_LOW;
        }
        return new Result(v, offered, required, null);
    }
}
