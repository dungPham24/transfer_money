package commonlib.transfer_money.domain.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key conflict — a different transfer already used key: " + idempotencyKey);
    }
}