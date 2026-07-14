package commonlib.transfer_money;

import commonlib.transfer_money.api.dto.CreateWalletRequest;
import commonlib.transfer_money.api.dto.TransferRequest;
import commonlib.transfer_money.api.dto.TransferResponse;
import commonlib.transfer_money.api.dto.WalletResponse;
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
class ConcurrencyTest {

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

                ResponseEntity<String> resp = doTransferRaw(
                        UUID.randomUUID().toString(), sourceId, destIds.get(idx), amount, "USD");

                if (resp.getStatusCode() == HttpStatus.CREATED) {
                    successes.incrementAndGet();
                } else if (resp.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
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
                ResponseEntity<String> resp = doTransferRaw(
                        UUID.randomUUID().toString(), walletA, walletB, new BigDecimal("10.00"), "USD");
                if (resp.getStatusCode() == HttpStatus.CREATED ||
                        resp.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                    completed.incrementAndGet();
                }
            });
            pool.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                ResponseEntity<String> resp = doTransferRaw(
                        UUID.randomUUID().toString(), walletB, walletA, new BigDecimal("10.00"), "USD");
                if (resp.getStatusCode() == HttpStatus.CREATED ||
                        resp.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                    completed.incrementAndGet();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(15, TimeUnit.SECONDS);

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

    private ResponseEntity<String> doTransferRaw(String idempotencyKey,
                                                  UUID sourceId, UUID destId,
                                                  BigDecimal amount, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(sourceId, destId, amount, currency), headers),
                String.class);
    }

    private BigDecimal getBalance(UUID walletId) {
        WalletResponse wallet = http.getForEntity(
                "/api/v1/wallets/" + walletId, WalletResponse.class).getBody();
        assertThat(wallet).isNotNull();
        return wallet.balance();
    }
}