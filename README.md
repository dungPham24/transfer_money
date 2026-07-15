# Wallet & Transfer Service

A production-style fintech service demonstrating safe money movement between wallets: atomic double-entry bookkeeping, two-layer idempotency, pessimistic locking with deadlock prevention, and structured logging with correlation IDs.

**Stack:** Java 17 · Spring Boot 4.1.0 · PostgreSQL 16 · Flyway · JUnit 5 · Testcontainers · Gradle

---

## Running the project

Prerequisites: Docker and Docker Compose installed. Nothing else required — the build happens inside Docker.

```bash
# 1. Clone
git clone https://github.com/dungPham24/transfer_money.git
cd transfer_money

# 2. (Optional) Override credentials/ports — defaults work fine for local testing
cp .env.example .env
```

Postgres is exposed on **host port 5433**, not 5432 — the single most common port to already be
taken by a locally-installed Postgres, which would otherwise make `docker compose up` fail with a
"port already in use" error before the app ever gets a chance to start. This only affects
optional external access (e.g. connecting with `psql`/DBeaver from your host); the app itself
always talks to `db:5432` over the internal Docker network regardless. Override via `.env` if
you need a different port.

```bash
# 3. Start everything
docker compose up --build
```

First run takes 2–3 minutes: downloads the JDK image, resolves Gradle dependencies, builds the JAR, starts Postgres, runs Flyway migrations, then starts the app. Subsequent builds with unchanged dependencies take ~30 seconds.

When you see `Started TransferMoneyApplication in X seconds`, the API is ready.

```bash
# Create two wallets
ALICE=$(curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -d '{"ownerName": "Alice", "currency": "USD"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

BOB=$(curl -s -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -d '{"ownerName": "Bob", "currency": "USD"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# Fund Alice — wallets start at $0; deposits are real ledger-writing transactions
# (POST /wallets/{id}/deposits), not a raw balance UPDATE, so the ledger stays the
# single source of truth even for how a wallet first got its money.
curl -X POST "http://localhost:8080/api/v1/wallets/$ALICE/deposits" \
  -H "Content-Type: application/json" \
  -d '{"amount": 200.00, "currency": "USD"}'

# Transfer funds Alice -> Bob
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{
    \"sourceWalletId\": \"$ALICE\",
    \"destWalletId\":   \"$BOB\",
    \"amount\": 50.00,
    \"currency\": \"USD\"
  }"

# Check balances
curl "http://localhost:8080/api/v1/wallets/$ALICE"   # → 150.00
curl "http://localhost:8080/api/v1/wallets/$BOB"     # → 50.00

# Interactive API docs
open http://localhost:8080/swagger-ui.html

# Stream structured logs
docker compose logs -f app | jq .

# Shut down and wipe the database
docker compose down -v
```

---

## Testing

Two tiers, and they run very differently — know which one you're running.

### Unit tests — no Docker, no database, run in milliseconds

`TransferServiceTest` and `WalletServiceTest` are plain Mockito tests against the service layer
(no Spring context at all). `GlobalExceptionHandlerTest` is a `@WebMvcTest` slice — it boots only
the web layer (controllers + `GlobalExceptionHandler`), no database.

```bash
./gradlew test --tests "commonlib.transfer_money.application.service.*" \
                --tests "commonlib.transfer_money.api.exception.*"
```

### Integration tests — need Docker (Testcontainers boots a real, throwaway Postgres)

`WalletIntegrationTest`, `TransferIntegrationTest`, and `ConcurrencyTest` each spin up a real
Postgres container, run the actual Flyway migrations against it, and drive the app end-to-end
over real HTTP. These are slower (each test class pays container-startup cost) but they're what
actually proves correctness — everything from `ddl-auto=validate` schema checks to real
row-level locking behaves differently against a real database than any mock ever could.

```bash
./gradlew test
```

These two tests specifically prove the assessment's two hardest requirements:

- **Idempotency** — `TransferIntegrationTest.transfer_sameIdempotencyKeyTwice_movesMoneyOnce()`
  sends the *same* transfer request twice with the *same* `Idempotency-Key` header and asserts
  money moved exactly once: exactly one row in `transfers`, exactly two rows in `ledger_entries`
  (one DEBIT, one CREDIT), both HTTP responses identical, and the second call does no wallet or
  ledger writes at all.

- **Concurrency / no overdraw** — `ConcurrencyTest.simultaneousWithdrawals_neverOverdraw_exactlyMaxSucceed()`
  fires 20 real concurrent HTTP requests (separate threads, separate connections) transferring
  $10 each out of a single $100 wallet, and asserts exactly 10 succeed, exactly 10 fail with 422,
  and the final balance is exactly $0 — never negative, no matter how the requests interleave.
  `ConcurrencyTest.oppositeDirectionTransfers_completeWithoutDeadlock()` fires transfers in both
  directions between two wallets simultaneously (A→B and B→A at once) and asserts neither
  deadlocks, proving the fixed-lock-order strategy in `TransferService`.

Run just those two:

```bash
./gradlew test --tests "commonlib.transfer_money.ConcurrencyTest" \
                --tests "commonlib.transfer_money.TransferIntegrationTest"
```

If you already have a local Postgres running (e.g. on 5432), that's unrelated and won't interfere
— Testcontainers always picks its own random free host port per container, independent of
`application.properties`' hardcoded `spring.datasource.url` (which the tests override via
`@DynamicPropertySource`).

---

## Architecture

The project uses **hexagonal architecture** (ports & adapters), split into four packages under `src/main/java/commonlib/transfer_money/`:

```
api/                    ← HTTP controllers, DTOs, validation, exception handler
application/
  port/in/              ← Use-case interfaces (what the outside world can ask)
  port/out/             ← Repository interfaces (what the service needs from storage)
  service/              ← Business logic: WalletService, TransferService
domain/
  model/                ← Wallet, Transfer, LedgerEntry, TransferStatus
  exception/            ← Domain exceptions (no framework imports)
infrastructure/
  persistence/          ← JPA entities, Spring Data repositories, adapters
  config/               ← OpenAPI config
```

**Why structure it this way?**

The `domain/` and `application/` packages import nothing from Spring or JPA. This is not academic — it has two practical consequences:

1. Unit tests for `TransferService` and `WalletService` run with plain Mockito in milliseconds, with no Spring context to boot. `TransferServiceTest.java` has 10 tests that verify all the tricky paths (idempotency, lock ordering, concurrent race) without a database.

2. If PostgreSQL were replaced with a different store tomorrow, only `infrastructure/persistence/` changes. The business rules in `domain/` and `application/` are untouched.

The `application/port/out/` interfaces (e.g., `WalletRepository`, `TransferRepository`) are what the service layer declares it needs. The `infrastructure/persistence/adapter/` classes implement those interfaces using Spring Data JPA. Spring wires them together at runtime — the service never imports a JPA class.

---

## How idempotency is guaranteed

Every `POST /transfers` request must include an `Idempotency-Key` header. The service guarantees that re-sending the same key with the same payload is safe: money moves exactly once, and the response is identical to the original.

Two layers handle two different scenarios.

### Layer 1 — application check (sequential retries)

At the top of `TransferService.transfer()` (line 79):

```java
Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
if (existing.isPresent()) {
    assertSamePayload(existing.get(), sourceWalletId, destWalletId, amount, currency);
    return existing.get();   // return immediately, no money movement
}
```

This handles the common case: a client retries because it didn't receive a response. The first request committed, the key is in the database, we return the same result. If the key matches but the payload differs (different amount, different wallets), `assertSamePayload()` (line 147) throws `IdempotencyConflictException` → 409.

### Layer 2 — DB constraint + REQUIRES_NEW sub-transaction (concurrent race)

Layer 1 has a gap: two requests with the same key can both pass the `findByIdempotencyKey` check simultaneously if neither has committed yet. Layer 2 closes that gap.

The `transfers` table has `CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)` (see `V1__init.sql`, line 31). When two concurrent requests race to INSERT, exactly one wins. The loser gets a constraint violation.

The catch is *when* the violation surfaces. Hibernate's `save()` defers the INSERT via write-behind; the exception would only appear at commit time, after any try-catch is gone. `IdempotentTransferInserter.insertPending()` uses `saveAndFlush()` instead (line 40 of `IdempotentTransferInserter.java`), which forces the SQL immediately.

The inserter runs in `@Transactional(propagation = REQUIRES_NEW)` (line 37). This creates a separate nested transaction that can roll back on its own without poisoning the outer transaction in `TransferService`. When the INSERT fails, `DataIntegrityViolationException` is caught and translated to `DuplicateTransferKeyException` (a pure domain exception, no Spring imports). Back in `TransferService` (line 111–120), that exception is caught, and the service does a final re-read to return the transfer that the winning concurrent request committed.

`IdempotentTransferInserter` exists as a separate `@Component` because Spring's `@Transactional` works via proxies — calling a `REQUIRES_NEW` method on `this` within the same bean bypasses the proxy and gets no new transaction. Putting it in a separate bean ensures the proxy is invoked.

---

## How overdraw is prevented

Two independent safety nets work in layers.

### Application layer — `Wallet.debit()` (line 38–43)

```java
public void debit(BigDecimal amount) {
    if (balance.compareTo(amount) < 0) {
        throw new InsufficientFundsException(id, balance, amount);
    }
    balance = balance.subtract(amount);
    updatedAt = Instant.now();
}
```

This runs **after** both wallet rows are locked with `SELECT FOR UPDATE`. The lock is acquired in `TransferService.transfer()` (lines 92–98) via `WalletRepository.findByIdForUpdate()`, which maps to `@Lock(LockModeType.PESSIMISTIC_WRITE)` in `WalletJpaRepository.java` (line 16). Locking before checking the balance is the critical detail: without the lock, two concurrent transfers could both read the same balance, both pass the check, and both debit — resulting in a negative balance.

**Deadlock prevention:** if two transfers run concurrently in opposite directions (A→B and B→A), each acquires its first lock and then waits for the other. The fix is to always lock wallets in the same order. `TransferService.transfer()` (lines 92–93) sorts by UUID before locking:

```java
UUID firstId  = sourceWalletId.compareTo(destWalletId) < 0 ? sourceWalletId : destWalletId;
UUID secondId = sourceWalletId.compareTo(destWalletId) < 0 ? destWalletId   : sourceWalletId;
```

A→B and B→A both lock the wallet with the lower UUID first, so they can never form a cycle.

### Database layer — `CHECK (balance >= 0)` (V1__init.sql, line 15)

```sql
CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
```

This is the hard stop. Even if there were a bug in the application that skipped the `debit()` check, the database would reject the `UPDATE`. A constraint at the DB layer is the appropriate place for a rule as critical as "a balance can never go negative" — it enforces the invariant regardless of which application, migration script, or ad-hoc query touches the table.

### Atomicity

The entire transfer — INSERT transfer (PENDING), UPDATE source balance, UPDATE dest balance, INSERT two ledger entries, UPDATE transfer (COMPLETED) — runs inside a single `@Transactional` method. Any exception at any step rolls back everything. The ledger and the cached balance are always consistent with each other.

`WalletService.reconcile()` (used in integration tests) verifies this by comparing `SUM(CREDIT) - SUM(DEBIT)` from `ledger_entries` against `wallets.balance` in a single read-only snapshot.

---

## What I would add with more time

**Transactional Outbox pattern for `TransferCompleted` events.**

Right now, when a transfer completes, no external systems know about it. Adding a direct call to a message broker (Kafka, SQS) inside the `@Transactional` method would be wrong: if the broker call succeeds but the DB commit fails (or vice versa), the event and the database are out of sync. This is a real data integrity problem in production.

The Outbox pattern solves it: instead of calling the broker directly, the service writes an `outbox_events` row to the same database in the same transaction. A separate process (a scheduled job, or a CDC connector like Debezium reading the Postgres WAL) reads committed outbox rows and publishes them to the broker, then marks them delivered. The event is only published if the DB transaction committed, and it's published at least once.

This is the standard approach in fintech for reliably triggering downstream actions (fraud checks, account statements, notifications, ledger reconciliation reports) without introducing distributed transaction risk. It's the single most valuable addition because without it the service has no reliable way to integrate with anything.

---

## Adding a NOT NULL column to `transfers` with zero downtime

The goal is to deploy the schema change and the code that uses it without any request failing and without locking the table for the duration of a backfill.

**Step 1 — Add the column as nullable.**

```sql
ALTER TABLE transfers ADD COLUMN description VARCHAR(500) NULL;
```

`ADD COLUMN NULL` in PostgreSQL is metadata-only: it does not rewrite the table and acquires only a brief `ACCESS EXCLUSIVE` lock (milliseconds). Safe to run on a live table.

**Step 2 — Pre-validate a NOT NULL check without a table lock.**

Instead of adding the `NOT NULL` constraint immediately (which requires a full table scan under a lock), add a `CHECK` constraint with `NOT VALID`:

```sql
ALTER TABLE transfers
  ADD CONSTRAINT chk_description_not_null
  CHECK (description IS NOT NULL) NOT VALID;
```

`NOT VALID` means the constraint is enforced for new writes immediately but existing rows are not scanned yet. New inserts and updates must satisfy it, so the application can start writing the column now.

**Step 3 — Deploy application code that writes to the new column.**

At this point new rows have `description` populated; old rows are NULL.

**Step 4 — Backfill existing rows in batches.**

```sql
UPDATE transfers SET description = 'legacy' WHERE description IS NULL AND id BETWEEN ... AND ...;
```

Do this in small batches (e.g., 1000 rows) with a short sleep between each, so you don't hold row-level locks for long and don't spike I/O.

**Step 5 — Validate the constraint.**

Once backfill is complete:

```sql
ALTER TABLE transfers VALIDATE CONSTRAINT chk_description_not_null;
```

`VALIDATE CONSTRAINT` scans the table but only holds a `SHARE UPDATE EXCLUSIVE` lock, which allows concurrent reads and writes. It takes time proportional to table size, but the application keeps running.

**Step 6 — Promote to NOT NULL.**

PostgreSQL 12+ recognises that a validated `CHECK (col IS NOT NULL)` proves no NULLs exist and makes `SET NOT NULL` instant (no scan needed):

```sql
ALTER TABLE transfers ALTER COLUMN description SET NOT NULL;
```

Then drop the now-redundant check constraint:

```sql
ALTER TABLE transfers DROP CONSTRAINT chk_description_not_null;
```

This final `ALTER` acquires `ACCESS EXCLUSIVE` momentarily but does no work, so it completes in microseconds.

---

## Project structure at a glance

```
src/main/java/commonlib/transfer_money/
├── api/
│   ├── TransferController.java
│   ├── WalletController.java
│   ├── dto/                         # Request/response records with @Schema
│   ├── exception/
│   │   ├── ApiError.java            # Uniform error shape: code, message, timestamp, details[]
│   │   └── GlobalExceptionHandler.java
│   ├── filter/
│   │   └── CorrelationFilter.java   # Assigns X-Request-ID → MDC for every request
│   └── validation/
│       └── ValidCurrency.java       # ISO 4217 check via java.util.Currency
├── application/
│   ├── port/in/                     # Use-case interfaces
│   ├── port/out/                    # Repository interfaces
│   └── service/
│       ├── TransferService.java     # Core business logic
│       └── WalletService.java
├── domain/
│   ├── exception/                   # 5 domain exceptions, no Spring imports
│   └── model/                       # Wallet, Transfer, LedgerEntry
└── infrastructure/
    ├── config/OpenApiConfig.java
    └── persistence/
        ├── adapter/                 # Implement port/out interfaces
        │   └── IdempotentTransferInserter.java   # REQUIRES_NEW sub-transaction
        ├── entity/                  # JPA entities (@Entity lives here only)
        └── repository/              # Spring Data JPA repositories

src/main/resources/
├── application.properties
├── logback-spring.xml               # Text (default) / JSON (prod profile)
└── db/migration/V1__init.sql        # Flyway baseline schema

src/test/java/commonlib/transfer_money/
├── application/service/
│   ├── WalletServiceTest.java       # 7 Mockito unit tests
│   └── TransferServiceTest.java     # 10 Mockito unit tests
├── api/exception/
│   └── GlobalExceptionHandlerTest.java   # 9 @WebMvcTest slice tests
├── WalletIntegrationTest.java       # 7 Testcontainers tests
├── TransferIntegrationTest.java     # 7 Testcontainers tests
└── ConcurrencyTest.java             # 2 tests: 20-thread overdraw + deadlock
```