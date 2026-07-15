package commonlib.transfer_money.application.service;

import commonlib.transfer_money.application.port.in.TransferFundsUseCase;
import commonlib.transfer_money.application.port.out.FraudCheckPort;
import commonlib.transfer_money.application.port.out.FxRateRepository;
import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.application.port.out.OutboxEventRepository;
import commonlib.transfer_money.application.port.out.TransferRepository;
import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.exception.DuplicateTransferKeyException;
import commonlib.transfer_money.domain.exception.FraudCheckRejectedException;
import commonlib.transfer_money.domain.exception.FxRateNotFoundException;
import commonlib.transfer_money.domain.exception.IdempotencyConflictException;
import commonlib.transfer_money.domain.exception.SameWalletTransferException;
import commonlib.transfer_money.domain.exception.ValidationException;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import commonlib.transfer_money.domain.model.FraudDecision;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.OutboxEvent;
import commonlib.transfer_money.domain.model.Transfer;
import commonlib.transfer_money.domain.model.TransferStatus;
import commonlib.transfer_money.domain.model.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService implements TransferFundsUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String MDC_TRANSFER_ID = "transferId";
    private static final int DEST_AMOUNT_SCALE = 4;

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final FraudCheckPort fraudCheckPort;
    private final FxRateRepository fxRateRepository;
    private final OutboxEventRepository outboxEventRepository;

    public TransferService(WalletRepository walletRepository,
                           TransferRepository transferRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           FraudCheckPort fraudCheckPort,
                           FxRateRepository fxRateRepository,
                           OutboxEventRepository outboxEventRepository) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.fraudCheckPort = fraudCheckPort;
        this.fxRateRepository = fxRateRepository;
        this.outboxEventRepository = outboxEventRepository;
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
     *
     * Fraud check runs BEFORE the wallet locks are acquired. The resilience4j-wrapped call can
     * take several seconds under retry/timeout; holding SELECT FOR UPDATE on both wallets for
     * that long would serialize unrelated transfers touching the same wallets. A REJECTED or
     * unavailable fraud decision therefore fails fast with zero lock contention.
     *
     * Cross-currency transfers: destAmount is computed from the latest FX rate (source → dest)
     * once the wallets — and therefore their currencies — are known.
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

        // ── Fraud check (before locks — see class-level javadoc) ─────────────────
        UUID transferId = UUID.randomUUID();
        MDC.put(MDC_TRANSFER_ID, transferId.toString());

        FraudDecision decision = fraudCheckPort.check(transferId, sourceWalletId, amount, currency);
        if (decision == FraudDecision.REJECTED) {
            log.atWarn()
                    .addKeyValue("idempotencyKey", idempotencyKey)
                    .addKeyValue("transferId", transferId)
                    .addKeyValue("outcome", "FRAUD_REJECTED")
                    .log("transfer.completed");
            throw new FraudCheckRejectedException(transferId);
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

        if (!currency.equalsIgnoreCase(source.getCurrency())) {
            throw new ValidationException("currency",
                    "must match source wallet currency (" + source.getCurrency() + ")");
        }

        // ── FX conversion (only when source and dest wallets hold different currencies) ──
        BigDecimal destAmount;
        BigDecimal exchangeRate;
        if (source.getCurrency().equalsIgnoreCase(dest.getCurrency())) {
            destAmount = amount;
            exchangeRate = null;
        } else {
            exchangeRate = fxRateRepository.findLatestRate(source.getCurrency(), dest.getCurrency())
                    .orElseThrow(() -> new FxRateNotFoundException(source.getCurrency(), dest.getCurrency()));
            destAmount = amount.multiply(exchangeRate).setScale(DEST_AMOUNT_SCALE, RoundingMode.HALF_UP);
        }

        // ── Layer 2: DB-enforced idempotency (concurrent requests) ───────────────
        Transfer transfer = new Transfer(transferId, idempotencyKey, sourceWalletId, destWalletId,
                amount, currency, destAmount, exchangeRate, TransferStatus.PENDING, Instant.now(), null);

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
        source.debit(amount);        // throws InsufficientFundsException if balance < amount
        dest.credit(destAmount);

        walletRepository.save(source);
        walletRepository.save(dest);

        ledgerEntryRepository.saveAll(List.of(
                LedgerEntry.debit(transfer.getId(), sourceWalletId, amount, source.getCurrency()),
                LedgerEntry.credit(transfer.getId(), destWalletId, destAmount, dest.getCurrency())
        ));

        transfer.complete();
        Transfer saved = transferRepository.save(transfer);

        // ── Outbox (same DB transaction as the transfer commit — see OutboxPoller) ──
        outboxEventRepository.save(OutboxEvent.transferCompleted(saved));

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