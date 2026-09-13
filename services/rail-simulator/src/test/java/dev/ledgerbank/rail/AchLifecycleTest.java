package dev.ledgerbank.rail;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AchLifecycleTest {
    @Test
    void acceptsCompleteSettlementAndReturnPaths() {
        assertDoesNotThrow(() -> AchLifecycle.requireLegal("PROCESSING", "INITIATED", "SETTLE"));
        assertDoesNotThrow(() -> AchLifecycle.requireLegal("INITIATED", "SUBMITTED", "SETTLE"));
        assertDoesNotThrow(() -> AchLifecycle.requireLegal("SUBMITTED", "SETTLED", "RETURN"));
        assertDoesNotThrow(() -> AchLifecycle.requireLegal("SETTLED", "RETURNED", "RETURN"));
        assertDoesNotThrow(() -> AchLifecycle.requireLegal("SUBMITTED", "REJECTED", "REJECT"));
    }

    @Test
    void rejectsSkippedAndScenarioIncompatibleTransitions() {
        assertThrows(IllegalStateException.class, () -> AchLifecycle.requireLegal("INITIATED", "SETTLED", "SETTLE"));
        assertThrows(IllegalStateException.class, () -> AchLifecycle.requireLegal("SETTLED", "RETURNED", "SETTLE"));
        assertThrows(IllegalStateException.class, () -> AchLifecycle.requireLegal("SUBMITTED", "SETTLED", "REJECT"));
    }
}
