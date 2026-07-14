package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.domain.exception.DuplicateTransferKeyException;
import commonlib.transfer_money.domain.model.Transfer;

import java.util.Optional;

public interface TransferRepository {
    Transfer save(Transfer transfer);
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /**
     * Inserts the PENDING transfer in an independent (REQUIRES_NEW) transaction.
     * Throws {@link DuplicateTransferKeyException} if a concurrent request already
     * committed the same idempotency key — the outer transaction remains alive.
     */
    void insertPendingOrThrowDuplicate(Transfer transfer);
}