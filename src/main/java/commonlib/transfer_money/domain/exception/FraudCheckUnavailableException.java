package commonlib.transfer_money.domain.exception;

public class FraudCheckUnavailableException extends RuntimeException {
    public FraudCheckUnavailableException(String reason) {
        super("Fraud check unavailable: " + reason);
    }
}