package commonlib.transfer_money.domain.exception;

public class SameWalletTransferException extends RuntimeException {
    public SameWalletTransferException() {
        super("Source and destination wallets must be different");
    }
}