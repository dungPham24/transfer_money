package commonlib.transfer_money.domain.exception;

/**
 * Thrown when a concurrent request races to INSERT a transfer with the same idempotency key
 * and loses the UNIQUE constraint race. The caller should re-read the existing transfer.
 * Pure domain exception — no Spring or JPA imports.
 */
public class DuplicateTransferKeyException extends RuntimeException {

    private final String idempotencyKey;

    public DuplicateTransferKeyException(String idempotencyKey) {
        super("Concurrent transfer already committed for idempotency key: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
