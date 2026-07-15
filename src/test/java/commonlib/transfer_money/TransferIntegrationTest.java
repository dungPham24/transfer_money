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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Plain java.net.http.HttpClient, not WebTestClient/TestRestTemplate — see ConcurrencyTest for
// why: WebTestClient's reactor-netty client sharing a JVM with this Servlet-based app's embedded
// Tomcat reliably hung every concurrent request in the multi-threaded test below, and
// TestRestTemplate no longer exists in Spring Boot 4.
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
        // See ConcurrencyTest — application.properties' hikari.maximum-pool-size wasn't taking
        // effect in this Testcontainers context; set explicitly so 10-way contention on one
        // wallet doesn't exhaust a 10-connection default pool.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired GetWalletUseCase getWalletUseCase;
    @Autowired ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // ── POST /api/v1/transfers — happy path ─────────────────────────────────

    @Test
    void transfer_happyPath_movesMoneyAndWritesBalancedLedger() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("200.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);

        TransferResponse body = doTransfer(
                UUID.randomUUID().toString(), sourceId, destId, new BigDecimal("75.00"), "USD");

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(body.amount()).isEqualByComparingTo("75.00");
        assertThat(body.completedAt()).isNotNull();

        assertThat(getBalance(sourceId)).isEqualByComparingTo("125.00");
        assertThat(getBalance(destId)).isEqualByComparingTo("75.00");

        // Only dest reconciles: it started at $0 and received all funds via ledger.
        // Source was seeded via direct DB UPDATE (no ledger entries), so reconcile would fail by design.
        assertReconciled(destId);
    }

    // ── Idempotency: same key twice moves money exactly once ─────────────────

    @Test
    void transfer_sameIdempotencyKeyTwice_movesMoneyOnce() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("100.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);
        String idemKey = UUID.randomUUID().toString();

        // Send the same request twice — both must return 201 with the same transfer
        TransferResponse r1 = doTransfer(idemKey, sourceId, destId, new BigDecimal("40.00"), "USD");
        TransferResponse r2 = doTransfer(idemKey, sourceId, destId, new BigDecimal("40.00"), "USD");

        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();

        // Second response is identical to first (same transfer object)
        assertThat(r2.id()).isEqualTo(r1.id());
        assertThat(r2.idempotencyKey()).isEqualTo(r1.idempotencyKey());
        assertThat(r2.sourceWalletId()).isEqualTo(r1.sourceWalletId());
        assertThat(r2.destWalletId()).isEqualTo(r1.destWalletId());
        assertThat(r2.amount()).isEqualByComparingTo(r1.amount());
        assertThat(r2.currency()).isEqualTo(r1.currency());
        assertThat(r2.status()).isEqualTo(r1.status());
        assertThat(r2.completedAt()).isEqualTo(r1.completedAt());

        // Exactly 1 transfer record in DB for this idempotency key
        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?",
                Integer.class, idemKey);
        assertThat(transferCount).isEqualTo(1);

        // Exactly 2 ledger entries (1 DEBIT + 1 CREDIT) for this transfer
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?::uuid",
                Integer.class, r1.id().toString());
        assertThat(ledgerCount).isEqualTo(2);

        Integer debitCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?::uuid AND entry_type = 'DEBIT'",
                Integer.class, r1.id().toString());
        assertThat(debitCount).isEqualTo(1);

        Integer creditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = ?::uuid AND entry_type = 'CREDIT'",
                Integer.class, r1.id().toString());
        assertThat(creditCount).isEqualTo(1);

        // Money moved ONCE: source $60, dest $40 — not $20/$80
        assertThat(getBalance(sourceId)).isEqualByComparingTo("60.00");
        assertThat(getBalance(destId)).isEqualByComparingTo("40.00");

        // Only dest reconciles (started at $0, all funds via ledger).
        assertReconciled(destId);
    }

    @Test
    void transfer_sameKeyDifferentAmount_returns409Conflict() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("200.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);
        String idemKey = UUID.randomUUID().toString();

        doTransfer(idemKey, sourceId, destId, new BigDecimal("50.00"), "USD");

        int status = doTransferRaw(idemKey, sourceId, destId, new BigDecimal("99.00"), "USD");
        assertThat(status).isEqualTo(409);
    }

    // ── Error cases ─────────────────────────────────────────────────────────

    @Test
    void transfer_insufficientFunds_returns422AndLeavesBalancesUnchanged() {
        UUID sourceId = seedWallet("Alice", "USD", new BigDecimal("50.00"));
        UUID destId   = seedWallet("Bob",   "USD", BigDecimal.ZERO);

        int status = doTransferRaw(
                UUID.randomUUID().toString(), sourceId, destId, new BigDecimal("100.00"), "USD");

        assertThat(status).isEqualTo(422);
        assertThat(getBalance(sourceId)).isEqualByComparingTo("50.00");
        assertThat(getBalance(destId)).isEqualByComparingTo("0.00");
    }

    @Test
    void transfer_unknownSourceWallet_returns404() {
        UUID destId = seedWallet("Bob", "USD", BigDecimal.ZERO);

        int status = doTransferRaw(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                destId,
                new BigDecimal("10.00"), "USD");

        assertThat(status).isEqualTo(404);
    }

    @Test
    void transfer_sameSourceAndDest_returns400() {
        UUID walletId = seedWallet("Alice", "USD", new BigDecimal("100.00"));

        int status = doTransferRaw(
                UUID.randomUUID().toString(), walletId, walletId, new BigDecimal("10.00"), "USD");

        assertThat(status).isEqualTo(400);
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
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                int status = doTransferRaw(
                        UUID.randomUUID().toString(), sourceId, destIds.get(idx), transferAmount, "USD");
                if (status == 201) {
                    successes.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(5);
        assertThat(failures.get()).isEqualTo(5);

        BigDecimal finalBalance = getBalance(sourceId);
        assertThat(finalBalance).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Creates a wallet via API then seeds its balance directly in the DB.
     * This bypasses the app's own deposit endpoint for test-setup convenience/speed.
     */
    private UUID seedWallet(String ownerName, String currency, BigDecimal balance) {
        try {
            String body = objectMapper.writeValueAsString(new CreateWalletRequest(ownerName, currency));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/wallets"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            WalletResponse wallet = objectMapper.readValue(response.body(), WalletResponse.class);
            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                jdbcTemplate.update("UPDATE wallets SET balance = ? WHERE id = ?::uuid",
                        balance, wallet.id().toString());
            }
            return wallet.id();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Sends a transfer and asserts 201 — use for success-path tests. */
    private TransferResponse doTransfer(String idempotencyKey,
                                        UUID sourceId, UUID destId,
                                        BigDecimal amount, String currency) {
        try {
            String body = objectMapper.writeValueAsString(new TransferRequest(sourceId, destId, amount, currency));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            return objectMapper.readValue(response.body(), TransferResponse.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Sends a transfer and returns the HTTP status without asserting — use for error-path tests. */
    private int doTransferRaw(String idempotencyKey, UUID sourceId, UUID destId,
                              BigDecimal amount, String currency) {
        try {
            String body = objectMapper.writeValueAsString(new TransferRequest(sourceId, destId, amount, currency));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BigDecimal getBalance(UUID walletId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/wallets/" + walletId))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            WalletResponse wallet = objectMapper.readValue(response.body(), WalletResponse.class);
            return wallet.balance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertReconciled(UUID walletId) {
        ReconcileResult result = getWalletUseCase.reconcile(walletId);
        assertThat(result.balanced())
                .as("Wallet %s: ledger=%s wallet=%s", walletId,
                        result.ledgerBalance(), result.walletBalance())
                .isTrue();
    }
}
