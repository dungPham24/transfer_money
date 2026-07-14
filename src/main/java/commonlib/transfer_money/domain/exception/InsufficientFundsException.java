package commonlib.transfer_money.domain.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID walletId, BigDecimal balance, BigDecimal requested) {
        super(String.format("Wallet %s has insufficient funds: balance=%s, requested=%s",
                walletId, balance.toPlainString(), requested.toPlainString()));
    }
}