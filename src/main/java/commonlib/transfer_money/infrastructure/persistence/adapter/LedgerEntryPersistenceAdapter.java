package commonlib.transfer_money.infrastructure.persistence.adapter;

import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.infrastructure.persistence.entity.LedgerEntryJpaEntity;
import commonlib.transfer_money.infrastructure.persistence.repository.LedgerEntryJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class LedgerEntryPersistenceAdapter implements LedgerEntryRepository {

    private final LedgerEntryJpaRepository jpaRepository;

    public LedgerEntryPersistenceAdapter(LedgerEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void saveAll(List<LedgerEntry> entries) {
        jpaRepository.saveAll(entries.stream().map(this::toEntity).toList());
    }

    @Override
    public List<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId) {
        return jpaRepository.findByWalletIdOrderByCreatedAtDesc(walletId)
                .stream().map(this::toDomain).toList();
    }

    private LedgerEntryJpaEntity toEntity(LedgerEntry e) {
        return new LedgerEntryJpaEntity(e.getId(), e.getTransferId(), e.getWalletId(),
                e.getEntryType().name(), e.getAmount(), e.getCreatedAt());
    }

    private LedgerEntry toDomain(LedgerEntryJpaEntity e) {
        return new LedgerEntry(e.getId(), e.getTransferId(), e.getWalletId(),
                LedgerEntry.EntryType.valueOf(e.getEntryType()), e.getAmount(), e.getCreatedAt());
    }
}