package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.domain.model.Transfer;

import java.util.Optional;

public interface TransferRepository {
    Transfer save(Transfer transfer);
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}