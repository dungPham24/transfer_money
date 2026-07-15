package commonlib.transfer_money.application.service;

import commonlib.transfer_money.application.PageResult;
import commonlib.transfer_money.application.port.out.LedgerEntryRepository;
import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Wallet;
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
class WalletServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @InjectMocks WalletService walletService;

    // ── createWallet ────────────────────────────────────────────────────────

    @Test
    void createWallet_persistsAndReturnsWallet() {
        Wallet saved = Wallet.create("Alice", "USD");
        when(walletRepository.save(any(Wallet.class))).thenReturn(saved);

        Wallet result = walletService.createWallet("Alice", "USD");

        assertThat(result.getOwnerName()).isEqualTo("Alice");
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnerName()).isEqualTo("Alice");
    }

    @Test
    void createWallet_generatesUniqueIds() {
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Wallet w1 = walletService.createWallet("Alice", "USD");
        Wallet w2 = walletService.createWallet("Bob",   "USD");

        assertThat(w1.getId()).isNotEqualTo(w2.getId());
    }

    // ── getWallet ───────────────────────────────────────────────────────────

    @Test
    void getWallet_existingId_returnsWallet() {
        UUID id = UUID.randomUUID();
        Wallet wallet = new Wallet(id, "Alice", "USD", BigDecimal.TEN, Instant.now(), Instant.now());
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet));

        Wallet result = walletService.getWallet(id);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getBalance()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void getWallet_unknownId_throwsWalletNotFoundException() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getWallet(id))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // ── getTransactionHistory ───────────────────────────────────────────────

    @Test
    void getTransactionHistory_unknownWallet_throwsWalletNotFoundException() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getTransactionHistory(id, 0, 20))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void getTransactionHistory_delegatesToRepositoryWithCorrectParams() {
        UUID walletId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, "Alice", "USD", BigDecimal.ZERO, Instant.now(), Instant.now());

        LedgerEntry entry = LedgerEntry.credit(transferId, walletId, new BigDecimal("50.00"), "USD");
        PageResult<LedgerEntry> expected = new PageResult<>(List.of(entry), 1L, 1, 0, 20);

        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(walletId, 0, 20))
                .thenReturn(expected);

        PageResult<LedgerEntry> result = walletService.getTransactionHistory(walletId, 0, 20);

        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).getEntryType()).isEqualTo(LedgerEntry.EntryType.CREDIT);
        verify(ledgerEntryRepository).findByWalletIdOrderByCreatedAtDesc(walletId, 0, 20);
    }

    @Test
    void getTransactionHistory_emptyWallet_returnsEmptyPage() {
        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, "Bob", "EUR", BigDecimal.ZERO, Instant.now(), Instant.now());
        PageResult<LedgerEntry> empty = new PageResult<>(List.of(), 0L, 0, 0, 20);

        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(walletId, 0, 20))
                .thenReturn(empty);

        PageResult<LedgerEntry> result = walletService.getTransactionHistory(walletId, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }
}