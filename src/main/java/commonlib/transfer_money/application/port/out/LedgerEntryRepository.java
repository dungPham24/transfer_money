package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.domain.model.LedgerEntry;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository {
    void saveAll(List<LedgerEntry> entries);
    List<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId);
}