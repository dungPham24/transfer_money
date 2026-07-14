package commonlib.transfer_money.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Audit result: compares the wallet's cached balance against the sum derived from ledger_entries.
 * balanced == true means the double-entry ledger is consistent with the cached balance.
 */
public record ReconcileResult(
        UUID walletId,
        BigDecimal ledgerBalance,   // SUM(CREDIT) - SUM(DEBIT) from ledger_entries
        BigDecimal walletBalance,   // wallets.balance (cached column)
        boolean balanced            // ledgerBalance.compareTo(walletBalance) == 0
) {}