package dev.ledgerbank.rail;

import java.util.Set;

final class AchLifecycle {
    private static final Set<String> LEGAL = Set.of(
            "PROCESSING->INITIATED",
            "INITIATED->SUBMITTED",
            "SUBMITTED->SETTLED",
            "SUBMITTED->REJECTED",
            "SETTLED->RETURNED");

    private AchLifecycle() {}

    static void requireLegal(String from, String to, String scenario) {
        if (!LEGAL.contains(from + "->" + to)) throw new IllegalStateException("Illegal ACH transition: " + from + " -> " + to);
        if ("RETURNED".equals(to) && !"RETURN".equals(scenario)) throw new IllegalStateException("Only RETURN scenarios may enter RETURNED");
        if ("SETTLED".equals(to) && "REJECT".equals(scenario)) throw new IllegalStateException("REJECT scenarios cannot settle");
        if ("REJECTED".equals(to) && !"REJECT".equals(scenario)) throw new IllegalStateException("Only REJECT scenarios may reject");
    }
}
