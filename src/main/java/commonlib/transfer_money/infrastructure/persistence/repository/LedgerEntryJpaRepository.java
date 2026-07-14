package commonlib.transfer_money.infrastructure.persistence.repository;

import commonlib.transfer_money.infrastructure.persistence.entity.LedgerEntryJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryJpaEntity, UUID> {

    Page<LedgerEntryJpaEntity> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    /**
     * Computes the ground-truth balance from the ledger.
     * COALESCE handles wallets with no entries (new wallets) → returns 0.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amount
                                     ELSE -e.amount END), 0)
            FROM LedgerEntryJpaEntity e
            WHERE e.walletId = :walletId
            """)
    BigDecimal calculateLedgerBalance(@Param("walletId") UUID walletId);
}