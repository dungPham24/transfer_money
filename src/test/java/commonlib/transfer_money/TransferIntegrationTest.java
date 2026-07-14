package commonlib.transfer_money;

import commonlib.transfer_money.api.dto.CreateWalletRequest;
import commonlib.transfer_money.api.dto.TransferRequest;
import commonlib.transfer_money.api.dto.TransferResponse;
import commonlib.transfer_money.api.dto.WalletResponse;
import commonlib.transfer_money.application.ReconcileResult;
import commonlib.transfer_money.application.port.in.GetWalletUseCase;
import commonlib.transfer_money.domain.model.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired GetWalletUseCase getWalletUseCase; // used for reconcile assertions

    // ── POST /api/v1/transfers — happy path ─────────────────────────────────

    @Test
    void transfer_happyPath_movesMoneyAndWritesBalancedLedger() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("200.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);

        ResponseEntity<TransferResponse> resp = doTransfer(
                UUID.randomUUID().toString(), sourceId, destId, new BigDecimal("75.00"), "USD");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransferResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(body.amount()).isEqualByComparingTo("75.00");
        assertThat(body.completedAt()).isNotNull();

        // Balances updated correctly
        assertThat(getBalance(sourceId)).isEqualByComparingTo("125.00");
        assertThat(getBalance(destId)).isEqualByComparingTo("75.00");

        // Ledger is the source of truth — both wallets must reconcile
        assertReconciled(sourceId);
        assertReconciled(destId);
    }

    // ── Idempotency: same key twice moves money exactly once ─────────────────

    @Test
    void transfer_sameIdempotencyKeyTwice_movesMoneyOnce() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("100.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);
        String idemKey = UUID.randomUUID().toString();

        ResponseEntity<TransferResponse> first  = doTransfer(idemKey, sourceId, destId, new BigDecimal("40.00"), "USD");
        ResponseEntity<TransferResponse> second = doTransfer(idemKey, sourceId, destId, new BigDecimal("40.00"), "USD");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same transfer ID returned both times
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());

        // Money moved ONCE: source $60, dest $40 — not $20/$80
        assertThat(getBalance(sourceId)).isEqualByComparingTo("60.00");
        assertThat(getBalance(destId)).isEqualByComparingTo("40.00");

        // Ledger balanced after single movement
        assertReconciled(sourceId);
        assertReconciled(destId);
    }

    @Test
    void transfer_sameKeyDifferentAmount_returns409Conflict() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("200.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);
        String idemKey = UUID.randomUUID().toString();

        doTransfer(idemKey, sourceId, destId, new BigDecimal("50.00"), "USD");

        ResponseEntity<String> conflict =
                doTransferRaw(idemKey, sourceId, destId, new BigDecimal("99.00"), "USD");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── Error cases ─────────────────────────────────────────────────────────

    @Test
    void transfer_insufficientFunds_returns422AndLeavesBalancesUnchanged() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("50.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);

        ResponseEntity<String> resp =
                doTransferRaw(UUID.randomUUID().toString(), sourceId, destId, new BigDecimal("100.00"), "USD");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(getBalance(sourceId)).isEqualByComparingTo("50.00"); // unchanged
        assertThat(getBalance(destId)).isEqualByComparingTo("0.00");

        // Ledger still reconciles (no partial entries written)
        assertReconciled(sourceId);
    }

    @Test
    void transfer_unknownSourceWallet_returns404() {
        UUID destId = seedWallet("Bob", "USD", BigDecimal.ZERO);

        ResponseEntity<String> resp = doTransferRaw(
                UUID.randomUUID().toString(),
                UUID.randomUUID(), // non-existent
                destId,
                new BigDecimal("10.00"), "USD");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void transfer_sameSourceAndDest_returns400() {
        UUID walletId = seedWallet("Alice", "USD", new BigDecimal("100.00"));

        ResponseEntity<String> resp = doTransferRaw(
                UUID.randomUUID().toString(), walletId, walletId, new BigDecimal("10.00"), "USD");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Concurrency: simultaneous transfers cannot overdraw ──────────────────

    @Test
    void concurrentTransfers_neverOverdrawSourceWallet() throws InterruptedException {
        // Source has $100; 10 threads each try to transfer $20 → only 5 should succeed
        UUID sourceId = seedWallet("Source", "USD", new BigDecimal("100.00"));
        int threadCount = 10;
        BigDecimal transferAmount = new BigDecimal("20.00");

        List<UUID> destIds = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            destIds.add(seedWallet("Dest-" + i, "USD", BigDecimal.ZERO));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures  = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // all threads start simultaneously
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ResponseEntity<TransferResponse> resp = doTransfer(
                        UUID.randomUUID().toString(), sourceId, destIds.get(idx), transferAmount, "USD");
                if (resp.getStatusCode() == HttpStatus.CREATED) {
                    successes.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
            });
        }

        ready.await();           // wait until all threads are queued
        start.countDown();       // fire all at once
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // Exactly 5 should succeed (100 / 20 = 5)
        assertThat(successes.get()).isEqualTo(5);
        assertThat(failures.get()).isEqualTo(5);

        // Source balance must be exactly 0 — never negative
        BigDecimal finalBalance = getBalance(sourceId);
        assertThat(finalBalance).isEqualByComparingTo(BigDecimal.ZERO);

        // Ledger must reconcile for source wallet after all concurrent writes
        assertReconciled(sourceId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Creates a wallet via API then seeds its balance directly in the DB.
     * This bypasses the "no deposit endpoint" limitation in tests.
     */
    private UUID seedWallet(String ownerName, String currency, BigDecimal balance) {
        ResponseEntity<WalletResponse> resp = http.postForEntity(
                "/api/v1/wallets",
                new CreateWalletRequest(ownerName, currency),
                WalletResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = resp.getBody().id();
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            jdbcTemplate.update("UPDATE wallets SET balance = ? WHERE id = ?::uuid",
                    balance, id.toString());
        }
        return id;
    }

    private ResponseEntity<TransferResponse> doTransfer(String idempotencyKey,
                                                         UUID sourceId, UUID destId,
                                                         BigDecimal amount, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new TransferRequest(sourceId, destId, amount, currency);
        return http.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(body, headers), TransferResponse.class);
    }

    private ResponseEntity<String> doTransferRaw(String idempotencyKey,
                                                  UUID sourceId, UUID destId,
                                                  BigDecimal amount, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new TransferRequest(sourceId, destId, amount, currency);
        return http.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private BigDecimal getBalance(UUID walletId) {
        WalletResponse wallet = http.getForEntity(
                "/api/v1/wallets/" + walletId, WalletResponse.class).getBody();
        assertThat(wallet).isNotNull();
        return wallet.balance();
    }

    private void assertReconciled(UUID walletId) {
        ReconcileResult result = getWalletUseCase.reconcile(walletId);
        assertThat(result.balanced())
                .as("Wallet %s: ledger=%s wallet=%s", walletId,
                        result.ledgerBalance(), result.walletBalance())
                .isTrue();
    }
}