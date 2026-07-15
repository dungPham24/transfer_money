package commonlib.transfer_money.infrastructure.persistence.adapter;

import commonlib.transfer_money.application.port.out.FxRateRepository;
import commonlib.transfer_money.infrastructure.persistence.repository.FxRateJpaRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

@Component
public class FxRateRepositoryAdapter implements FxRateRepository {

    private final FxRateJpaRepository jpaRepository;

    public FxRateRepositoryAdapter(FxRateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<BigDecimal> findLatestRate(String fromCurrency, String toCurrency) {
        return jpaRepository.findLatest(fromCurrency, toCurrency)
                .map(entity -> entity.getRate());
    }
}