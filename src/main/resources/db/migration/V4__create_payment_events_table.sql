CREATE TABLE payment_events (
    event_id VARCHAR(128) PRIMARY KEY,
    claim_id UUID NOT NULL,
    type VARCHAR(64) NOT NULL,
    amount_paise INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_events_claim_id ON payment_events (claim_id);
