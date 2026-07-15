package commonlib.transfer_money.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "fx_rates")
@IdClass(FxRateJpaEntity.FxRateId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class FxRateJpaEntity {

    @Id
    @Column(name = "from_currency", length = 3)
    private String fromCurrency;

    @Id
    @Column(name = "to_currency", length = 3)
    private String toCurrency;

    @Id
    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    public static class FxRateId implements Serializable {
        private String fromCurrency;
        private String toCurrency;
        private Instant effectiveAt;

        public FxRateId() {}

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FxRateId other)) return false;
            return Objects.equals(fromCurrency, other.fromCurrency)
                    && Objects.equals(toCurrency, other.toCurrency)
                    && Objects.equals(effectiveAt, other.effectiveAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fromCurrency, toCurrency, effectiveAt);
        }
    }
}