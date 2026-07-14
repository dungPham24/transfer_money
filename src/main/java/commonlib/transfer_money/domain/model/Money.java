package commonlib.transfer_money.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Value object representing an amount in a specific currency.
 * Immutable and self-validating — cannot be constructed in an invalid state.
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive, got: " + amount);
        }
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-char ISO 4217 code, got: " + currency);
        }
    }

    public boolean isSameCurrency(String other) {
        return currency.equalsIgnoreCase(other);
    }
}