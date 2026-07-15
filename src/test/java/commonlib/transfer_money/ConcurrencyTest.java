package commonlib.transfer_money;

import commonlib.transfer_money.api.dto.CreateWalletRequest;
import commonlib.transfer_money.api.dto.TransferRequest;
import commonlib.transfer_money.api.dto.WalletResponse;
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

// Plain java.net.http.HttpClient, not WebTestClient/TestRestTemplate: WebTestClient's
// reactor-netty client sharing a JVM with this Servlet-based app's embedded Tomcat reliably hung
// every concurrent request for the full response timeout in these multi-threaded tests
// (confirmed via diagnostic stack traces — every thread blocked in Mono.block() with zero
// requests ever completing), despite the exact same requests completing in well under a second
// against a real running instance via curl. TestRestTemplate would be the traditional fix but
// was removed in Spring Boot 4 (no longer resolvable anywhere in the dependency tree). The JDK's
// own HttpClient has no such entanglement and no dependency on Spring's test-client churn.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // application.properties' hikari.maximum-pool-size wasn't taking effect here (root cause
        // not yet fully understood — possibly src/test/resources/application.properties shadowing
        // src/main's on the classpath) — set explicitly so this test's 20-way contention on one
        // wallet doesn't exhaust a 10-connection default pool (each blocked SELECT FOR UPDATE
        // holds its connection for the whole wait, not just its own query).
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 20 threads each attempt to transfer $10 from a $100 wallet simultaneously.
     * Exactly 10 must succeed; the other 10 must fail with 422 (InsufficientFunds).
     * No overdraw, no uncontrolled exceptions, final balance exactly 0.
     */
    @Test
    void simultaneousWithdrawals_neverOverdraw_exactlyMaxSucceed() throws InterruptedException {
        BigDecimal initial = new BigDecimal("100.00");
        BigDecimal amount  = new BigDecimal("10.00");
        int threadCount = 20;
        int expectedSuccesses = initial.divide(amount).intValue(); // 10

        UUID sourceId = seedWallet("Source", "USD", initial);

        List<UUID> destIds = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            destIds.add(seedWallet("Dest-" + i, "USD", BigDecimal.ZERO));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes         = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
        AtomicInteger unexpectedErrors  = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

                int status = doTransferRaw(
                        UUID.randomUUID().toString(), sourceId, destIds.get(idx), amount, "USD");

                if (status == 201) {
                    successes.incrementAndGet();
                } else if (status == 422) {
                    insufficientFunds.incrementAndGet();
                } else {
                    unexpectedErrors.incrementAndGet();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(unexpectedErrors.get())
                .as("All failures must be controlled InsufficientFunds (422), not random exceptions")
                .isEqualTo(0);

        assertThat(successes.get())
                .as("Exactly %d transfers must succeed (balance / amount)", expectedSuccesses)
                .isEqualTo(expectedSuccesses);

        assertThat(insufficientFunds.get())
                .as("Remaining %d must fail with 422", threadCount - expectedSuccesses)
                .isEqualTo(threadCount - expectedSuccesses);

        BigDecimal finalBalance = getBalance(sourceId);
        assertThat(finalBalance)
                .as("Balance must never go negative")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        BigDecimal expectedFinalBalance = initial.subtract(amount.multiply(BigDecimal.valueOf(successes.get())));
        assertThat(finalBalance)
                .as("Final balance must equal initial minus total transferred")
                .isEqualByComparingTo(expectedFinalBalance);
    }

    /**
     * Deadlock prevention: A→B and B→A fired simultaneously.
     * Fixed lock order (lower UUID first) guarantees both complete without timeout or deadlock.
     */
    @Test
    void oppositeDirectionTransfers_completeWithoutDeadlock() throws InterruptedException {
        UUID walletA = seedWallet("WalletA", "USD", new BigDecimal("500.00"));
        UUID walletB = seedWallet("WalletB", "USD", new BigDecimal("500.00"));

        int pairs = 10; // 10 A→B and 10 B→A simultaneously
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch ready = new CountDownLatch(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        for (int i = 0; i < pairs; i++) {
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                int status = doTransferRaw(
                        UUID.randomUUID().toString(), walletA, walletB, new BigDecimal("10.00"), "USD");
                if (status == 201 || status == 422) {
                    completed.incrementAndGet();
                }
            });
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                int status = doTransferRaw(
                        UUID.randomUUID().toString(), walletB, walletA, new BigDecimal("10.00"), "USD");
                if (status == 201 || status == 422) {
                    completed.incrementAndGet();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(finished).as("All threads finished within timeout — no deadlock").isTrue();
        assertThat(completed.get())
                .as("Every request completed with a controlled HTTP status (no deadlock/timeout)")
                .isEqualTo(pairs * 2);

        // Total money across both wallets must be conserved
        BigDecimal totalBalance = getBalance(walletA).add(getBalance(walletB));
        assertThat(totalBalance)
                .as("Money is conserved: total across both wallets must still be $1000")
                .isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

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
}
