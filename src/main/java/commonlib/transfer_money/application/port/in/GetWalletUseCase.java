package commonlib.transfer_money.application.port.in;

import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Wallet;

import java.util.List;
import java.util.UUID;

public interface GetWalletUseCase {
    Wallet getWallet(UUID walletId);
    List<LedgerEntry> getTransactionHistory(UUID walletId);
}