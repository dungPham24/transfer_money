package commonlib.transfer_money.application.port.in;

import commonlib.transfer_money.domain.model.Wallet;

import java.math.BigDecimal;
import java.util.UUID;

public interface DepositFundsUseCase {
    Wallet deposit(UUID walletId, BigDecimal amount, String currency);
}
