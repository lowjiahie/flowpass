package com.flowpass.approval.domain.common;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Money value object — amount plus ISO-4217 currency, per the charter (money as decimal + currency).
 *
 * <p>Immutable and validated; never mutated in place. Amounts are stored in UTC-free, currency
 * plain-scale decimal representation (no float).</p>
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places");
        }
    }
}
