-- Transactional Outbox table.
-- Events are written in the SAME database transaction as the transfer that produced them.
-- Atomicity guarantee: either both the transfer commit AND the event row exist, or neither does.
-- A separate scheduler (OutboxPoller) reads unpublished rows and publishes them to downstream
-- systems, then marks them delivered. At-least-once delivery: if the poller crashes after
-- publishing but before marking, the event will be re-published on next poll.
CREATE TABLE outbox_events (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type   VARCHAR(100)  NOT NULL,
    aggregate_id UUID          NOT NULL,    -- transferId for TRANSFER_COMPLETED events
    payload      JSONB         NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ               -- NULL = not yet published
);

-- Partial index: only unpublished rows are scanned by the poller. As events are published
-- the index shrinks automatically — it never accumulates all historical events.
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at)
    WHERE published_at IS NULL;