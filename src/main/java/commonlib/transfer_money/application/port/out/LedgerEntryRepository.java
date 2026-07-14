package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.application.PageResult;
import commonlib.transfer_money.domain.model.LedgerEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository {
    void saveAll(List<LedgerEntry> entries);
    PageResult<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId, int page, int size);
    /** Returns SUM(CREDIT) - SUM(DEBIT) for the wallet — the ground-truth balance from the ledger. */
    BigDecimal calculateLedgerBalance(UUID walletId);
}