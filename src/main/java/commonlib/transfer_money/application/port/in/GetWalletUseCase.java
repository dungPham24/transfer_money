package commonlib.transfer_money.application.port.in;

import commonlib.transfer_money.application.PageResult;
import commonlib.transfer_money.application.ReconcileResult;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Wallet;

import java.util.UUID;

public interface GetWalletUseCase {
    Wallet getWallet(UUID walletId);
    PageResult<LedgerEntry> getTransactionHistory(UUID walletId, int page, int size);
    /** Audit: verifies SUM(ledger_entries) == wallets.balance for the given wallet. */
    ReconcileResult reconcile(UUID walletId);
}