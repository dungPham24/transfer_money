package commonlib.transfer_money.application.service;

import commonlib.transfer_money.application.port.in.TransferFundsUseCase;
import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.application.port.out.TransferRepository;
import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.exception.IdempotencyConflictException;
import commonlib.transfer_money.domain.exception.SameWalletTransferException;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Transfer;
import commonlib.transfer_money.domain.model.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService implements TransferFundsUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Moves money atomically. Concurrency safety:
     *   1. Both wallet rows are locked with SELECT FOR UPDATE in a fixed UUID order to prevent deadlocks.
     *   2. Wallet.debit() enforces the no-overdraw rule at the domain level.
     *   3. wallets.balance CHECK (balance >= 0) is the DB-level hard stop.
     *
     * Idempotency:
     *   - UNIQUE (idempotency_key) means the first INSERT wins; duplicate → load & return existing.
     *   - Payload is re-validated against the stored transfer to catch accidental key reuse.
     */
    @Override
    @Transactional
    public Transfer transfer(String idempotencyKey, UUID sourceWalletId, UUID destWalletId,
                             BigDecimal amount, String currency) {

        log.info("transfer.start idempotencyKey={} src={} dst={} amount={} currency={}",
                idempotencyKey, sourceWalletId, destWalletId, amount, currency);

        if (sourceWalletId.equals(destWalletId)) {
            throw new SameWalletTransferException();
        }

        // ── Idempotency check ────────────────────────────────────────────────
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transfer t = existing.get();
            assertSamePayload(t, sourceWalletId, destWalletId, amount, currency);
            log.info("transfer.idempotent_replay idempotencyKey={} transferId={}", idempotencyKey, t.getId());
            return t;
        }

        // ── Acquire pessimistic locks in deterministic order (avoids deadlock) ─
        UUID firstId  = sourceWalletId.compareTo(destWalletId) < 0 ? sourceWalletId : destWalletId;
        UUID secondId = sourceWalletId.compareTo(destWalletId) < 0 ? destWalletId   : sourceWalletId;

        Wallet first  = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletNotFoundException(firstId));
        Wallet second = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletNotFoundException(secondId));

        Wallet source = first.getId().equals(sourceWalletId) ? first : second;
        Wallet dest   = first.getId().equals(destWalletId)   ? first : second;

        // ── Record transfer as PENDING, then apply domain logic ──────────────
        Transfer transfer = Transfer.create(idempotencyKey, sourceWalletId, destWalletId, amount, currency);
        transferRepository.save(transfer);

        source.debit(amount);   // throws InsufficientFundsException if balance < amount
        dest.credit(amount);

        walletRepository.save(source);
        walletRepository.save(dest);

        ledgerEntryRepository.saveAll(List.of(
                LedgerEntry.debit(transfer.getId(), sourceWalletId, amount),
                LedgerEntry.credit(transfer.getId(), destWalletId, amount)
        ));

        transfer.complete();
        Transfer saved = transferRepository.save(transfer);

        log.info("transfer.complete transferId={} idempotencyKey={}", saved.getId(), idempotencyKey);
        return saved;
    }

    private void assertSamePayload(Transfer existing, UUID sourceWalletId, UUID destWalletId,
                                   BigDecimal amount, String currency) {
        boolean mismatch = !existing.getSourceWalletId().equals(sourceWalletId)
                || !existing.getDestWalletId().equals(destWalletId)
                || existing.getAmount().compareTo(amount) != 0
                || !existing.getCurrency().equalsIgnoreCase(currency);
        if (mismatch) {
            throw new IdempotencyConflictException(existing.getIdempotencyKey());
        }
    }
}