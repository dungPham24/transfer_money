package commonlib.transfer_money.infrastructure.persistence.repository;

import commonlib.transfer_money.infrastructure.persistence.entity.LedgerEntryJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntryJpaEntity, UUID> {
    Page<LedgerEntryJpaEntity> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);
}