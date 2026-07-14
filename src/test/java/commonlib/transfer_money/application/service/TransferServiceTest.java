package commonlib.transfer_money.application.service;

import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.application.port.out.TransferRepository;
import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.exception.IdempotencyConflictException;
import commonlib.transfer_money.domain.exception.InsufficientFundsException;
import commonlib.transfer_money.domain.exception.SameWalletTransferException;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
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

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    // Fixed UUIDs so compareTo order is deterministic: SOURCE_ID < DEST_ID
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEST_ID   = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock WalletRepository walletRepository;
    @Mock TransferRepository transferRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @InjectMocks TransferService transferService;

    private Wallet sourceWallet;
    private Wallet destWallet;

    @BeforeEach
    void setUp() {
        sourceWallet = new Wallet(SOURCE_ID, "Alice", "USD", new BigDecimal("100.00"), Instant.now(), Instant.now());
        destWallet   = new Wallet(DEST_ID,   "Bob",   "USD", BigDecimal.ZERO,          Instant.now(), Instant.now());
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
    void transfer_savesTransferTwice_pendingThenCompleted() {
        stubLocksAndSaves();

        transferService.transfer("key-1", SOURCE_ID, DEST_ID, new BigDecimal("10.00"), "USD");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository, times(2)).save(captor.capture());

        List<Transfer> saves = captor.getAllValues();
        assertThat(saves.get(0).getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(saves.get(1).getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(saves.get(1).getCompletedAt()).isNotNull();
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
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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

        Wallet small = new Wallet(smallId, "X", "USD", new BigDecimal("100.00"), Instant.now(), Instant.now());
        Wallet large = new Wallet(largeId, "Y", "USD", BigDecimal.ZERO, Instant.now(), Instant.now());

        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(smallId)).thenReturn(Optional.of(small));
        when(walletRepository.findByIdForUpdate(largeId)).thenReturn(Optional.of(large));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Transfer FROM large TO small — but service must still lock small first
        transferService.transfer("key-1", largeId, smallId, new BigDecimal("10.00"), "USD");

        var inOrder = inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdForUpdate(smallId); // smaller UUID always first
        inOrder.verify(walletRepository).findByIdForUpdate(largeId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubLocksAndSaves() {
        when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(SOURCE_ID)).thenReturn(Optional.of(sourceWallet));
        when(walletRepository.findByIdForUpdate(DEST_ID)).thenReturn(Optional.of(destWallet));
        when(transferRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }
}