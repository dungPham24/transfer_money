package commonlib.transfer_money.application.service;

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
import commonlib.transfer_money.domain.exception.InsufficientFundsException;
import commonlib.transfer_money.domain.exception.SameWalletTransferException;
import commonlib.transfer_money.domain.exception.ValidationException;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import commonlib.transfer_money.domain.model.FraudDecision;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Transfer;
import commonlib.transfer_money.domain.model.TransferStatus;
import commonlib.transfer_money.domain.model.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    // Fixed UUIDs so compareTo order is deterministic: SOURCE_ID < DEST_ID
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEST_ID   = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock WalletRepository walletRepository;
    @Mock TransferRepository transferRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock FraudCheckPort fraudCheckPort;
    @Mock FxRateRepository fxRateRepository;
    @Mock OutboxEventRepository outboxEventRepository;
    @InjectMocks TransferService transferService;

    private Wallet sourceWallet;
    private Wallet destWallet;

    @BeforeEach
    void setUp() {
        sourceWallet = new Wallet(SOURCE_ID, "Alice", "USD", new BigDecimal("100.00"), Instant.now(), Instant.now());
        destWallet   = new Wallet(DEST_ID,   "Bob",   "USD", BigDecimal.ZERO,          Instant.now(), Instant.now());
        // Not every test reaches the fraud-check call site (e.g. same-wallet, idempotent-replay
        // paths short-circuit earlier) — lenient() avoids UnnecessaryStubbingException there.
        lenient().when(fraudCheckPort.check(any(), any(), any(), any())).thenReturn(FraudDecision.APPROVED);
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    void transfer_happyPath_debitsSourceCreditsDest() {
        stubLocksAndSaves();

        Transfer result = transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("30.00"), "USD");

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(sourceWallet.getBalance()).isEqualByComparingTo("70.00");
        assertThat(destWallet.getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void transfer_writesExactlyTwoBalancedLedgerEntries() {
        stubLocksAndSaves();

        transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("40.00"), "USD");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());

        List<LedgerEntry> entries = captor.getValue();
        assertThat(entries).hasSize(2);

        LedgerEntry debit  = entries.stream().filter(e -> e.getEntryType() == LedgerEntry.EntryType.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> e.getEntryType() == LedgerEntry.EntryType.CREDIT).findFirst().orElseThrow();

        // same transfer_id on both entries
        assertThat(debit.getTransferId()).isEqualTo(credit.getTransferId());
        // amounts match — the invariant that makes the ledger balanced
        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
        // correct wallet assignment
        assertThat(debit.getWalletId()).isEqualTo(SOURCE_ID);
        assertThat(credit.getWalletId()).isEqualTo(DEST_ID);
    }

    @Test
    void transfer_insertsPendingThenSavesCompleted() {
        // Transfer is a mutable domain object: the same instance passed to
        // insertPendingOrThrowDuplicate() is later mutated by transfer.complete(). Snapshot its
        // status at invocation time (doAnswer) rather than reading a captured reference after
        // the whole transfer() call returns, which would only ever observe the final COMPLETED state.
        java.util.concurrent.atomic.AtomicReference<TransferStatus> statusAtInsertTime = new java.util.concurrent.atomic.AtomicReference<>();
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(destWallet));
        doAnswer(inv -> {
            statusAtInsertTime.set(((Transfer) inv.getArgument(0)).getStatus());
            return null;
        }).when(transferRepository).insertPendingOrThrowDuplicate(any());
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD");

        // Step 1: insertPendingOrThrowDuplicate called with PENDING status
        assertThat(statusAtInsertTime.get()).isEqualTo(TransferStatus.PENDING);

        // Step 2: save() called once, with COMPLETED status
        ArgumentCaptor<Transfer> completedCaptor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository, times(1)).save(completedCaptor.capture());
        assertThat(completedCaptor.getValue().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(completedCaptor.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    void transfer_concurrentDuplicate_returnsExistingTransferWithoutMovingMoney() {
        // Simulate the race: insertPendingOrThrowDuplicate signals a concurrent commit
        Transfer alreadyCommitted = Transfer.create("key-race", SOURCE_ID, DEST_ID, new BigDecimal("30.00"), "USD");
        alreadyCommitted.complete();

        // 1st call (layer-1 check) sees nothing yet; 2nd call (after the duplicate-key signal)
        // re-reads the row a concurrent request just committed. A second when(...) on the same
        // argument would simply overwrite the first — thenReturn(a, b) is required to sequence them.
        when(transferRepository.findByIdempotencyKey("key-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(alreadyCommitted));
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(destWallet));
        doThrow(new DuplicateTransferKeyException("key-race"))
                .when(transferRepository).insertPendingOrThrowDuplicate(any());

        Transfer result = transferService.transfer("key-race", SOURCE_ID, DEST_ID, new BigDecimal("30.00"), "USD");

        assertThat(result.getId()).isEqualTo(alreadyCommitted.getId());
        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        // No money moved — no wallet or ledger writes
        verify(walletRepository, never()).save(any());
        verifyNoInteractions(ledgerEntryRepository);
    }

    // ── Idempotency ─────────────────────────────────────────────────────────

    @Test
    void transfer_sameKeyTwice_returnsExistingTransferWithoutMovingMoney() {
        Transfer existing = Transfer.create("key-dup", SOURCE_ID, DEST_ID, new BigDecimal("50.00"), "USD");
        existing.complete();
        when(transferRepository.findByIdempotencyKey("key-dup")).thenReturn(Optional.of(existing));

        Transfer result = transferService.transfer("key-dup", SOURCE_ID, DEST_ID, new BigDecimal("50.00"), "USD");

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        // No money movement on replay
        verifyNoInteractions(walletRepository);
        verifyNoInteractions(ledgerEntryRepository);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void transfer_sameKeyDifferentAmount_throwsIdempotencyConflict() {
        Transfer existing = Transfer.create("key-conflict", SOURCE_ID, DEST_ID, new BigDecimal("50.00"), "USD");
        when(transferRepository.findByIdempotencyKey("key-conflict")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                transferService.transfer("key-conflict", SOURCE_ID, DEST_ID, new BigDecimal("99.00"), "USD"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("key-conflict");
    }

    // ── Domain validation ───────────────────────────────────────────────────

    @Test
    void transfer_sameWallet_throwsSameWalletTransferException() {
        assertThatThrownBy(() ->
                transferService.transfer("key-1", SOURCE_ID, SOURCE_ID, new BigDecimal("10.00"), "USD"))
                .isInstanceOf(SameWalletTransferException.class);

        verifyNoInteractions(walletRepository, transferRepository, ledgerEntryRepository);
    }

    @Test
    void transfer_insufficientFunds_throwsAndRollsBackWithoutLedgerWrite() {
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(destWallet));
        doNothing().when(transferRepository).insertPendingOrThrowDuplicate(any());
        // transferRepository.save() is never reached — debit() throws first — so it's not stubbed here.

        // Source has $100, requesting $200
        assertThatThrownBy(() ->
                transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("200.00"), "USD"))
                .isInstanceOf(InsufficientFundsException.class);

        // Ledger must NOT have been written — in production this is the transaction rollback
        verifyNoInteractions(ledgerEntryRepository);
        // Wallet save must NOT have been called
        verify(walletRepository, never()).save(any());
    }

    @Test
    void transfer_sourceWalletNotFound_throwsWalletNotFoundException() {
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD"))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessageContaining(SOURCE_ID.toString());
    }

    @Test
    void transfer_destWalletNotFound_throwsWalletNotFoundException() {
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD"))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessageContaining(DEST_ID.toString());
    }

    // ── Lock ordering ────────────────────────────────────────────────────────

    @Test
    void transfer_locksWalletsInUuidOrder_toPreventDeadlock() {
        stubLocksAndSaves();

        // SOURCE_ID < DEST_ID → source must be locked first
        transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD");

        var inOrder = inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdForUpdate(SOURCE_ID); // smaller UUID first
        inOrder.verify(walletRepository).findByIdForUpdate(DEST_ID);
    }

    @Test
    void transfer_whenDestIdSmaller_locksDestFirst() {
        // DEST_ID > SOURCE_ID, so swap: use dest as "source arg" (larger UUID)
        UUID smallId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID largeId = UUID.fromString("00000000-0000-0000-0000-000000000009");

        // "large"/"small" name the wallet IDs (for lock-order clarity), not the balances:
        // large is the transfer source here, so it needs the funds.
        Wallet small = new Wallet(smallId, "X", "USD", BigDecimal.ZERO,          Instant.now(), Instant.now());
        Wallet large = new Wallet(largeId, "Y", "USD", new BigDecimal("100.00"), Instant.now(), Instant.now());

        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(smallId)).thenReturn(Optional.of(small));
        when(walletRepository.findByIdForUpdate(largeId)).thenReturn(Optional.of(large));
        doNothing().when(transferRepository).insertPendingOrThrowDuplicate(any());
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Transfer FROM large TO small — but service must still lock small first
        transferService.transfer("key-1", largeId, smallId, new BigDecimal("10.00"), "USD");

        var inOrder = inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdForUpdate(smallId); // smaller UUID always first
        inOrder.verify(walletRepository).findByIdForUpdate(largeId);
    }

    // ── Fraud check ─────────────────────────────────────────────────────────

    @Test
    void transfer_fraudRejected_throwsAndNeverAcquiresLocks() {
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(fraudCheckPort.check(any(), any(), any(), any())).thenReturn(FraudDecision.REJECTED);

        assertThatThrownBy(() ->
                transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD"))
                .isInstanceOf(FraudCheckRejectedException.class);

        // Rejected before any lock/write — no wallet or ledger interaction at all
        verifyNoInteractions(walletRepository, ledgerEntryRepository, outboxEventRepository);
        verify(transferRepository, never()).insertPendingOrThrowDuplicate(any());
    }

    @Test
    void transfer_fraudCheckRunsBeforeWalletLocks() {
        stubLocksAndSaves();

        transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD");

        var inOrder = inOrder(fraudCheckPort, walletRepository);
        inOrder.verify(fraudCheckPort).check(any(), eq(SOURCE_ID), any(), any());
        inOrder.verify(walletRepository).findByIdForUpdate(SOURCE_ID);
    }

    // ── Cross-currency (FX) ─────────────────────────────────────────────────

    @Test
    void transfer_crossCurrency_convertsDestAmountUsingFxRate() {
        Wallet eurDest = new Wallet(DEST_ID, "Bob", "EUR", BigDecimal.ZERO, Instant.now(), Instant.now());

        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(eurDest));
        when(fxRateRepository.findLatestRate("USD", "EUR")).thenReturn(Optional.of(new BigDecimal("0.92")));
        doNothing().when(transferRepository).insertPendingOrThrowDuplicate(any());
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transfer result = transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD");

        assertThat(result.getExchangeRate()).isEqualByComparingTo("0.92");
        assertThat(result.getDestAmount()).isEqualByComparingTo("9.2000");
        assertThat(sourceWallet.getBalance()).isEqualByComparingTo("90.00"); // debited in USD
        assertThat(eurDest.getBalance()).isEqualByComparingTo("9.2000");     // credited in EUR
    }

    @Test
    void transfer_crossCurrency_noFxRate_throwsFxRateNotFoundException() {
        Wallet eurDest = new Wallet(DEST_ID, "Bob", "EUR", BigDecimal.ZERO, Instant.now(), Instant.now());

        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(eurDest));
        when(fxRateRepository.findLatestRate("USD", "EUR")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD"))
                .isInstanceOf(FxRateNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
        verify(transferRepository, never()).insertPendingOrThrowDuplicate(any());
    }

    @Test
    void transfer_currencyDoesNotMatchSourceWallet_throwsValidationException() {
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet)); // USD wallet
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(destWallet));

        assertThatThrownBy(() ->
                transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "EUR"))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    // ── Outbox ──────────────────────────────────────────────────────────────

    @Test
    void transfer_onSuccess_writesTransferCompletedOutboxEvent() {
        stubLocksAndSaves();

        Transfer result = transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD");

        ArgumentCaptor<commonlib.transfer_money.domain.model.OutboxEvent> captor =
                ArgumentCaptor.forClass(commonlib.transfer_money.domain.model.OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(captor.getValue().getAggregateId()).isEqualTo(result.getId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubLocksAndSaves() {
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(destWallet));
        doNothing().when(transferRepository).insertPendingOrThrowDuplicate(any());
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }
}