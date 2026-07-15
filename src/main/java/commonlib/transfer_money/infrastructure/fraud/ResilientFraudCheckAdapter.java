package commonlib.transfer_money.infrastructure.fraud;

import commonlib.transfer_money.application.port.out.FraudCheckPort;
import commonlib.transfer_money.domain.exception.FraudCheckUnavailableException;
import commonlib.transfer_money.domain.model.FraudDecision;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Wraps StubFraudCheckAdapter with Resilience4j:
 *   TimeLimiter  — 2-second timeout per attempt (via CompletableFuture.orTimeout)
 *   Retry        — up to 3 attempts with 200ms backoff for transient errors
 *   CircuitBreaker — opens after 50% failure rate over 10 calls; waits 30s before half-open
 *
 * Circuit-breaker-open decision: when the circuit is open, transfers are REJECTED with 503.
 * The alternative — fallback to "always approve" — is unacceptable in fintech: a fraud check
 * that silently approves everything when the service is down defeats its entire purpose.
 * Operators should monitor circuit state and restore the fraud service before the 30s wait.
 */
@Component
public class ResilientFraudCheckAdapter implements FraudCheckPort {

    private final StubFraudCheckAdapter stub;
    private CircuitBreaker circuitBreaker;
    private Retry retry;

    // Dedicated pool, not ForkJoinPool.commonPool(): the common pool is shared JVM-wide with
    // everything else running in this process (including, in tests, unrelated test classes) —
    // a blocking bulkhead here keeps fraud-check latency/starvation isolated to this adapter.
    private final ExecutorService fraudCheckExecutor = Executors.newFixedThreadPool(20);

    public ResilientFraudCheckAdapter(StubFraudCheckAdapter stub) {
        this.stub = stub;
    }

    @PreDestroy
    void shutdown() {
        fraudCheckExecutor.shutdown();
    }

    @PostConstruct
    void configure() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.circuitBreaker = CircuitBreaker.of("fraudCheck", cbConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                // Don't retry when circuit breaker is open or when fraud service explicitly rejects
                .ignoreExceptions(CallNotPermittedException.class, FraudCheckUnavailableException.class)
                .build();
        this.retry = Retry.of("fraudCheck", retryConfig);
    }

    @Override
    public FraudDecision check(UUID transferId, UUID sourceWalletId, BigDecimal amount, String currency) {
        Supplier<FraudDecision> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        () -> callWithTimeout(transferId, sourceWalletId, amount, currency)));
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new FraudCheckUnavailableException("Circuit breaker open — fraud check unavailable");
        }
    }

    private FraudDecision callWithTimeout(UUID transferId, UUID sourceWalletId,
                                           BigDecimal amount, String currency) {
        try {
            return CompletableFuture.supplyAsync(
                            () -> stub.check(transferId, sourceWalletId, amount, currency), fraudCheckExecutor)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FraudCheckUnavailableException("Fraud check interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw new FraudCheckUnavailableException("Fraud check timed out after 2s");
            }
            if (cause instanceof RuntimeException re) throw re;
            throw new FraudCheckUnavailableException(cause.getMessage());
        }
    }
}