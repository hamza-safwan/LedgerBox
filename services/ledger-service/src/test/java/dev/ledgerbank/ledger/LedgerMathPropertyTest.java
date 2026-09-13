package dev.ledgerbank.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LedgerMathPropertyTest {
    @Test
    void everyDebitIsExactlyReversedByACreditForEachAccountType() {
        Random amounts = new Random(90210);
        for (int sample = 0; sample < 10_000; sample++) {
            long amount = 1 + amounts.nextLong(1_000_000_00L);
            for (String type : new String[]{"ASSET", "LIABILITY"}) {
                long debit = LedgerMath.balanceDelta(type, "DEBIT", amount);
                long credit = LedgerMath.balanceDelta(type, "CREDIT", amount);
                assertEquals(0, Math.addExact(debit, credit));
                assertEquals(amount, Math.abs(debit));
                assertEquals(amount, Math.abs(credit));
            }
        }
    }

    @Test
    void rejectsInvalidMoneyAndAccountingDimensions() {
        assertThrows(IllegalArgumentException.class, () -> LedgerMath.balanceDelta("ASSET", "DEBIT", 0));
        assertThrows(IllegalArgumentException.class, () -> LedgerMath.balanceDelta("ASSET", "SIDEWAYS", 1));
        assertThrows(IllegalArgumentException.class, () -> LedgerMath.balanceDelta("REVENUE", "CREDIT", 1));
    }
}
