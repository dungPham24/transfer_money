-- =============================================================================
-- V1: Core schema — wallets, transfers, ledger_entries
-- =============================================================================

-- wallets ─────────────────────────────────────────────────────────────────────
CREATE TABLE wallets (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name  VARCHAR(255)  NOT NULL,
    currency    VARCHAR(3)    NOT NULL,           -- ISO 4217 (USD, EUR, VND …)
    balance     NUMERIC(19,4) NOT NULL DEFAULT 0, -- cached, updated atomically with ledger
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Hard stop at DB level: no negative balance regardless of application bugs
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

-- transfers ───────────────────────────────────────────────────────────────────
CREATE TABLE transfers (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key   VARCHAR(255)  NOT NULL,
    source_wallet_id  UUID          NOT NULL REFERENCES wallets(id),
    dest_wallet_id    UUID          NOT NULL REFERENCES wallets(id),
    amount            NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    status            VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    -- Idempotency guarantee enforced at DB layer — duplicate INSERT fails here
    CONSTRAINT uq_idempotency_key    UNIQUE (idempotency_key),
    CONSTRAINT chk_amount_positive   CHECK  (amount > 0),
    CONSTRAINT chk_different_wallets CHECK  (source_wallet_id <> dest_wallet_id),
    CONSTRAINT chk_status            CHECK  (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

-- ledger_entries ──────────────────────────────────────────────────────────────
-- Source of truth. Every transfer produces exactly 1 DEBIT + 1 CREDIT row.
-- Audit: SUM(CREDIT) - SUM(DEBIT) per wallet must equal wallets.balance.
CREATE TABLE ledger_entries (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id  UUID          NOT NULL REFERENCES transfers(id),
    wallet_id    UUID          NOT NULL REFERENCES wallets(id),
    entry_type   VARCHAR(6)    NOT NULL,    -- 'DEBIT' | 'CREDIT'
    amount       NUMERIC(19,4) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_entry_type             CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_entry_amount_positive  CHECK (amount > 0)
);

-- Indexes ─────────────────────────────────────────────────────────────────────

-- Primary query: transaction history for a wallet, newest first
CREATE INDEX idx_ledger_wallet_created  ON ledger_entries(wallet_id, created_at DESC);

-- Joins from transfer → its entries
CREATE INDEX idx_ledger_transfer_id     ON ledger_entries(transfer_id);

-- Outbound transfer queries (e.g., rate limiting, reporting)
CREATE INDEX idx_transfers_source       ON transfers(source_wallet_id);
CREATE INDEX idx_transfers_dest         ON transfers(dest_wallet_id);