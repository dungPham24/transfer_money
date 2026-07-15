package commonlib.transfer_money.infrastructure.fraud;

import commonlib.transfer_money.domain.model.FraudDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

/**
 * Simulates an external fraud-check service. In production this would be an HTTP call
 * to a dedicated fraud-detection service (e.g., via Feign or RestClient).
 *
 * Behaviour is configurable so integration tests can set all rates to 0 (always approve,
 * no delay) while local dev/prod simulation can use realistic failure rates.
 */
@Component
class StubFraudCheckAdapter {

    private static final Logger log = LoggerFactory.getLogger(StubFraudCheckAdapter.class);

    @Value("${app.fraud.rejection-rate:0.10}")
    private double rejectionRate;

    @Value("${app.fraud.error-rate:0.05}")
    private double errorRate;

    @Value("${app.fraud.max-delay-ms:300}")
    private int maxDelayMs;

    private final Random random = new Random();

    FraudDecision check(UUID transferId, UUID sourceWalletId, BigDecimal amount, String currency) {
        simulateLatency();

        if (random.nextDouble() < errorRate) {
            throw new RuntimeException("Fraud service unavailable (simulated network error)");
        }

        if (random.nextDouble() < rejectionRate) {
            log.atWarn()
                    .addKeyValue("transferId", transferId)
                    .addKeyValue("amount", amount)
                    .addKeyValue("currency", currency)
                    .log("fraud.check.rejected");
            return FraudDecision.REJECTED;
        }

        return FraudDecision.APPROVED;
    }

    private void simulateLatency() {
        if (maxDelayMs <= 0) return;
        try {
            Thread.sleep(random.nextInt(maxDelayMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}