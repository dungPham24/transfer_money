package commonlib.transfer_money.domain.exception;

import java.util.UUID;

public class FraudCheckRejectedException extends RuntimeException {
    public FraudCheckRejectedException(UUID transferId) {
        super("Transfer rejected by fraud check: " + transferId);
    }
}