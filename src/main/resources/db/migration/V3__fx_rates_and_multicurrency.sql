-- FX rates table. Rate semantics: 1 unit of from_currency = rate units of to_currency.
-- The application always fetches the most recent rate for a pair (ORDER BY effective_at DESC LIMIT 1).
-- To update a rate, INSERT a new row — old rates are preserved for audit.
CREATE TABLE fx_rates (
    from_currency CHAR(3)        NOT NULL,
    to_currency   CHAR(3)        NOT NULL,
    rate          NUMERIC(19, 8) NOT NULL,
    effective_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (from_currency, to_currency, effective_at),
    CONSTRAINT chk_fx_rate_positive         CHECK (rate > 0),
    CONSTRAINT chk_fx_different_currencies  CHECK (from_currency <> to_currency)
);

-- Seed initial rates for testing
INSERT INTO fx_rates (from_currency, to_currency, rate) VALUES
    ('USD', 'EUR', 0.92000000),
    ('EUR', 'USD', 1.08695652),
    ('USD', 'VND', 25000.00000000),
    ('VND', 'USD', 0.00004000),
    ('EUR', 'VND', 27173.91304000),
    ('VND', 'EUR', 0.00003679);

-- Add optional FX columns to transfers (NULL for same-currency transfers — fully backward compatible).
-- dest_amount: the amount actually credited to the destination wallet (in dest currency).
-- exchange_rate: the rate applied (dest_amount = amount * exchange_rate).
ALTER TABLE transfers
    ADD COLUMN dest_amount   NUMERIC(19, 4),
    ADD COLUMN exchange_rate NUMERIC(19, 8);

-- Add currency to ledger entries for cross-currency clarity.
-- Same-currency transfers: currency = transfer.currency for both debit and credit.
-- Cross-currency: debit entry has source currency, credit entry has dest currency.
ALTER TABLE ledger_entries
    ADD COLUMN currency CHAR(3);