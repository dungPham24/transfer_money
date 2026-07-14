package commonlib.transfer_money.application.service;

import commonlib.transfer_money.application.PageResult;
import commonlib.transfer_money.application.ReconcileResult;
import commonlib.transfer_money.application.port.in.CreateWalletUseCase;
import commonlib.transfer_money.application.port.in.GetWalletUseCase;
import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Wallet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WalletService implements CreateWalletUseCase, GetWalletUseCase {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletService(WalletRepository walletRepository,
                         LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Override
    @Transactional
    public Wallet createWallet(String ownerName, String currency) {
        return walletRepository.save(Wallet.create(ownerName, currency));
    }

    @Override
    public Wallet getWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    @Override
    public PageResult<LedgerEntry> getTransactionHistory(UUID walletId, int page, int size) {
        if (walletRepository.findById(walletId).isEmpty()) {
            throw new WalletNotFoundException(walletId);
        }
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(walletId, page, size);
    }

    /**
     * Audit method: reads wallet.balance (cached) and SUM(ledger_entries) in the same
     * read-only transaction to get a consistent snapshot. balanced == true means the
     * double-entry ledger is perfectly in sync with the cached balance column.
     * Used in tests to verify data integrity after transfers.
     */
    @Override
    public ReconcileResult reconcile(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        BigDecimal ledgerBalance = ledgerEntryRepository.calculateLedgerBalance(walletId);
        boolean balanced = wallet.getBalance().compareTo(ledgerBalance) == 0;
        return new ReconcileResult(walletId, ledgerBalance, wallet.getBalance(), balanced);
    }
}