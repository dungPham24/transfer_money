package commonlib.transfer_money.infrastructure.persistence.adapter;

import commonlib.transfer_money.domain.exception.DuplicateTransferKeyException;
import commonlib.transfer_money.infrastructure.persistence.entity.TransferJpaEntity;
import commonlib.transfer_money.infrastructure.persistence.repository.TransferJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a PENDING transfer row in its own REQUIRES_NEW transaction.
 *
 * Why a separate bean?
 *   Spring @Transactional uses proxies — calling a method on `this` bypasses the proxy,
 *   so REQUIRES_NEW must live in a different Spring-managed component.
 *
 * Why REQUIRES_NEW?
 *   If the INSERT fails (concurrent request already committed the same idempotency_key),
 *   only this inner transaction rolls back. The outer @Transactional (REQUIRED) is merely
 *   suspended, not poisoned — it resumes and can still execute the re-read SELECT.
 *
 * Why saveAndFlush instead of save?
 *   save() defers the INSERT via Hibernate's write-behind. The constraint violation would
 *   only surface at commit time, after our try-catch exits. saveAndFlush() forces the SQL
 *   immediately so the DataIntegrityViolationException is caught inside the try block.
 */
@Component
public class IdempotentTransferInserter {

    private final TransferJpaRepository jpaRepository;

    public IdempotentTransferInserter(TransferJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertPending(TransferJpaEntity entity) {
        try {
            jpaRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE constraint on idempotency_key violated by a concurrent request.
            // Translate to a domain exception so the service layer stays Spring-free.
            throw new DuplicateTransferKeyException(entity.getIdempotencyKey());
        }
    }
}
