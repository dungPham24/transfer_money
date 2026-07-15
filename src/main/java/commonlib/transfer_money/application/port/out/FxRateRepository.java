package commonlib.transfer_money.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

public interface FxRateRepository {
    Optional<BigDecimal> findLatestRate(String fromCurrency, String toCurrency);
}