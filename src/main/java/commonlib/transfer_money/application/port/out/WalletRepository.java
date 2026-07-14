package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.domain.model.Wallet;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {
    Wallet save(Wallet wallet);
    Optional<Wallet> findById(UUID id);
    /** Acquires a pessimistic write lock — use inside a transaction on the transfer path. */
    Optional<Wallet> findByIdForUpdate(UUID id);
}