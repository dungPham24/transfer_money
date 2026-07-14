package commonlib.transfer_money.application.service;

import commonlib.transfer_money.application.port.in.CreateWalletUseCase;
import commonlib.transfer_money.application.port.in.GetWalletUseCase;
import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Wallet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    public List<LedgerEntry> getTransactionHistory(UUID walletId) {
        if (walletRepository.findById(walletId).isEmpty()) {
            throw new WalletNotFoundException(walletId);
        }
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(walletId);
    }
}