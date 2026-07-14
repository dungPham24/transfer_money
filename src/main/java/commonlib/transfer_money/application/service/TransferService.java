package commonlib.transfer_money.application.service;

import commonlib.transfer_money.application.port.in.TransferFundsUseCase;
import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.application.port.out.TransferRepository;
import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.exception.DuplicateTransferKeyException;
import commonlib.transfer_money.domain.exception.IdempotencyConflictException;
import commonlib.transfer_money.domain.exception.SameWalletTransferException;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Transfer;
import commonlib.transfer_money.domain.model.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService implements TransferFundsUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String MDC_TRANSFER_ID = "transferId";

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
     * Idempotency is guaranteed at two layers:
     *
     *  Layer 1 — application check (handles sequential retries, the common case):
     *    findByIdempotencyKey() before doing any work. If found, validate payload and return.
     *
     *  Layer 2 — DB constraint (handles concurrent requests racing to INSERT):
     *    insertPendingOrThrowDuplicate() runs in a REQUIRES_NEW sub-transaction.
     *    If a concurrent request already committed the same key, the sub-tx rolls back
     *    and throws DuplicateTransferKeyException. The outer @Transactional is still alive
     *    (only the sub-tx was poisoned), so we can re-read and return the existing transfer.
     *
     * Concurrency / overdraw prevention:
     *    Both wallet rows are locked with SELECT FOR UPDATE in fixed UUID order → no deadlock.
     *    Wallet.debit() + CHECK (balance >= 0) are the dual safety nets.
     */
    @Override
    @Transactional
    public Transfer transfer(String idempotencyKey, UUID sourceWalletId, UUID destWalletId,
                             BigDecimal amount, String currency) {

        // Structured log — SLF4J 2.x key-value pairs become separate JSON fields in prod.
        // idempotencyKey and wallet IDs are safe to log; do NOT log balances or owner names.
        log.atInfo()
                .addKeyValue("idempotencyKey", idempotencyKey)
                .addKeyValue("sourceWalletId", sourceWalletId)
                .addKeyValue("destWalletId", destWalletId)
                .addKeyValue("amount", amount.toPlainString())
                .addKeyValue("currency", currency)
                .log("transfer.received");

        if (sourceWalletId.equals(destWalletId)) {
            throw new SameWalletTransferException();
        }

        // ── Layer 1: application-level idempotency check (sequential retries) ──
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transfer t = existing.get();
            assertSamePayload(t, sourceWalletId, destWalletId, amount, currency);
            log.atInfo()
                    .addKeyValue("idempotencyKey", idempotencyKey)
                    .addKeyValue("transferId", t.getId())
                    .addKeyValue("outcome", "IDEMPOTENT_REPLAY")
                    .log("transfer.completed");
            return t;
        }

        // ── Acquire pessimistic locks in deterministic UUID order (prevents deadlock) ──
        UUID firstId  = sourceWalletId.compareTo(destWalletId) < 0 ? sourceWalletId : destWalletId;
        UUID secondId = sourceWalletId.compareTo(destWalletId) < 0 ? destWalletId   : sourceWalletId;

        Wallet first  = walletRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new WalletNotFoundException(firstId));
        Wallet second = walletRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new WalletNotFoundException(secondId));

        Wallet source = first.getId().equals(sourceWalletId) ? first : second;
        Wallet dest   = first.getId().equals(destWalletId)   ? first : second;

        // ── Layer 2: DB-enforced idempotency (concurrent requests) ───────────────
        Transfer transfer = Transfer.create(idempotencyKey, sourceWalletId, destWalletId, amount, currency);

        // Put transferId in MDC now — all subsequent log lines in this request will carry it.
        MDC.put(MDC_TRANSFER_ID, transfer.getId().toString());

        try {
            transferRepository.insertPendingOrThrowDuplicate(transfer);
        } catch (DuplicateTransferKeyException e) {
            Transfer committed = transferRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Transfer missing after duplicate key signal", e));
            assertSamePayload(committed, sourceWalletId, destWalletId, amount, currency);
            log.atInfo()
                    .addKeyValue("idempotencyKey", idempotencyKey)
                    .addKeyValue("transferId", committed.getId())
                    .addKeyValue("outcome", "CONCURRENT_DUPLICATE")
                    .log("transfer.completed");
            return committed;
        }

        // ── Money movement (within the outer @Transactional) ──────────────────────
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

        log.atInfo()
                .addKeyValue("transferId", saved.getId())
                .addKeyValue("idempotencyKey", idempotencyKey)
                .addKeyValue("outcome", "COMPLETED")
                .log("transfer.completed");
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