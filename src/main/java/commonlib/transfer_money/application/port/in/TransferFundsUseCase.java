package commonlib.transfer_money.application.port.in;

import commonlib.transfer_money.domain.model.Transfer;

import java.math.BigDecimal;
import java.util.UUID;

public interface TransferFundsUseCase {
    Transfer transfer(String idempotencyKey, UUID sourceWalletId, UUID destWalletId,
                      BigDecimal amount, String currency);
}