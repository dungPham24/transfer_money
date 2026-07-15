-- Deposits: money entering the system from an external source (bank transfer, card top-up).
-- Modeled as its own aggregate rather than a transfer, because a deposit has no source wallet
-- inside this ledger — the offsetting side lives in a bank/payment processor this schema
-- doesn't model. It still produces exactly one ledger CREDIT entry, so the ledger stays the
-- single source of truth for every balance change, including how a wallet first got funded
-- (no direct UPDATE of wallets.balance ever happens outside a ledger-writing code path).
CREATE TABLE deposits (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id   UUID          NOT NULL REFERENCES wallets(id),
    amount      NUMERIC(19,4) NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_deposit_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_deposits_wallet ON deposits(wallet_id);

-- A ledger entry now references EITHER a transfer OR a deposit, never both and never neither —
-- enforced at the DB layer so an application bug can't silently write an orphaned entry.
ALTER TABLE ledger_entries
    ALTER COLUMN transfer_id DROP NOT NULL,
    ADD COLUMN deposit_id UUID REFERENCES deposits(id),
    ADD CONSTRAINT chk_ledger_reference_exclusive
        CHECK (num_nonnulls(transfer_id, deposit_id) = 1);

CREATE INDEX idx_ledger_deposit_id ON ledger_entries(deposit_id);
