package commonlib.transfer_money.application.port.in;

import commonlib.transfer_money.domain.model.Wallet;

public interface CreateWalletUseCase {
    Wallet createWallet(String ownerName, String currency);
}