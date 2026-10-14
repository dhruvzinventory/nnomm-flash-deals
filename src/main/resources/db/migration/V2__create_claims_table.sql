CREATE TABLE claims (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL REFERENCES deals(id),
    customer_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity >= 1 AND quantity <= 3),
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('RESERVED', 'PAID', 'EXPIRED', 'NEEDS_REVIEW', 'REFUND_PENDING')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Invariant 2: A customer can hold at most one RESERVED, PAID or NEEDS_REVIEW claim on a deal.
CREATE UNIQUE INDEX idx_claims_customer_deal_active ON claims (customer_id, deal_id)
    WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Index for fast expiry scanning by the background worker:
CREATE INDEX idx_claims_status_expires_at ON claims (status, expires_at)
    WHERE status = 'RESERVED';

CREATE INDEX idx_claims_customer_created ON claims (customer_id, created_at DESC, id DESC);
CREATE INDEX idx_claims_deal_id ON claims (deal_id);
