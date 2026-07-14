package commonlib.transfer_money.infrastructure.persistence.adapter;

import commonlib.transfer_money.application.port.out.TransferRepository;
import commonlib.transfer_money.domain.model.Transfer;
import commonlib.transfer_money.domain.model.TransferStatus;
import commonlib.transfer_money.infrastructure.persistence.entity.TransferJpaEntity;
import commonlib.transfer_money.infrastructure.persistence.repository.TransferJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TransferPersistenceAdapter implements TransferRepository {

    private final TransferJpaRepository jpaRepository;
    private final IdempotentTransferInserter idempotentInserter;

    public TransferPersistenceAdapter(TransferJpaRepository jpaRepository,
                                      IdempotentTransferInserter idempotentInserter) {
        this.jpaRepository = jpaRepository;
        this.idempotentInserter = idempotentInserter;
    }

    @Override
    public Transfer save(Transfer transfer) {
        return toDomain(jpaRepository.save(toEntity(transfer)));
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(this::toDomain);
    }

    /**
     * Delegates to IdempotentTransferInserter which runs in REQUIRES_NEW.
     * DuplicateTransferKeyException propagates to TransferService on constraint violation.
     */
    @Override
    public void insertPendingOrThrowDuplicate(Transfer transfer) {
        idempotentInserter.insertPending(toEntity(transfer));
    }

    private TransferJpaEntity toEntity(Transfer t) {
        return new TransferJpaEntity(t.getId(), t.getIdempotencyKey(), t.getSourceWalletId(),
                t.getDestWalletId(), t.getAmount(), t.getCurrency(),
                t.getStatus().name(), t.getCreatedAt(), t.getCompletedAt());
    }

    private Transfer toDomain(TransferJpaEntity e) {
        return new Transfer(e.getId(), e.getIdempotencyKey(), e.getSourceWalletId(),
                e.getDestWalletId(), e.getAmount(), e.getCurrency(),
                TransferStatus.valueOf(e.getStatus()), e.getCreatedAt(), e.getCompletedAt());
    }
}