package com.flowpass.approval.domain.decision;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain tests for the structured {@link RuleCondition} evaluation semantics. */
class RuleConditionTest {

    @Test
    void defaultConditionMatchesEverything() {
        RuleCondition any = RuleCondition.any();
        assertThat(any.isDefault()).isTrue();
        assertThat(any.matches(Map.of())).isTrue();
        assertThat(any.matches(Map.of("amount", 999))).isTrue();
    }

    @Test
    void amountBandIsLowerInclusiveUpperExclusive() {
        RuleCondition band = RuleCondition.amountBand(new BigDecimal("1000"), new BigDecimal("5000"));
        assertThat(band.matches(Map.of("amount", new BigDecimal("1000")))).isTrue();
        assertThat(band.matches(Map.of("amount", new BigDecimal("4999.99")))).isTrue();
        assertThat(band.matches(Map.of("amount", new BigDecimal("5000")))).isFalse();
        assertThat(band.matches(Map.of("amount", new BigDecimal("999.99")))).isFalse();
    }

    @Test
    void bandedConditionDoesNotMatchWhenAmountAbsent() {
        RuleCondition band = RuleCondition.amountBand(new BigDecimal("1000"), null);
        assertThat(band.matches(Map.of())).isFalse();
    }

    @Test
    void openEndedBandMatchesAboveMin() {
        RuleCondition band = RuleCondition.amountBand(new BigDecimal("10000"), null);
        assertThat(band.matches(Map.of("amount", 10000))).isTrue();
        assertThat(band.matches(Map.of("amount", 9999))).isFalse();
    }

    @Test
    void fieldMatchesRequireStringEquality() {
        RuleCondition condition = new RuleCondition(null, null, Map.of("department", "IT"));
        assertThat(condition.isDefault()).isFalse();
        assertThat(condition.matches(Map.of("department", "IT"))).isTrue();
        assertThat(condition.matches(Map.of("department", "HR"))).isFalse();
        assertThat(condition.matches(Map.of())).isFalse();
    }

    @Test
    void rejectsInvertedBand() {
        assertThatThrownBy(() -> RuleCondition.amountBand(new BigDecimal("5000"), new BigDecimal("1000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
