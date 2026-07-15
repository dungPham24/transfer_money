package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.domain.model.FraudDecision;

import java.math.BigDecimal;
import java.util.UUID;

public interface FraudCheckPort {
    FraudDecision check(UUID transferId, UUID sourceWalletId, BigDecimal amount, String currency);
}