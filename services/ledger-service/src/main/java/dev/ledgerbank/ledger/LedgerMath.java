package dev.ledgerbank.ledger;

final class LedgerMath {
    private LedgerMath() {}

    static long balanceDelta(String accountType, String direction, long amountMinor) {
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
        return switch (accountType) {
            case "ASSET" -> switch (direction) {
                case "DEBIT" -> amountMinor;
                case "CREDIT" -> -amountMinor;
                default -> throw new IllegalArgumentException("Unsupported direction: " + direction);
            };
            case "LIABILITY" -> switch (direction) {
                case "DEBIT" -> -amountMinor;
                case "CREDIT" -> amountMinor;
                default -> throw new IllegalArgumentException("Unsupported direction: " + direction);
            };
            default -> throw new IllegalArgumentException("Unsupported account type: " + accountType);
        };
    }
}
