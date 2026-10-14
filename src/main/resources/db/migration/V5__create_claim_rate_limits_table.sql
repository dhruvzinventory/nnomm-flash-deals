CREATE TABLE claim_rate_limits (
    customer_id UUID NOT NULL,
    attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_claim_rate_limits_cust_time ON claim_rate_limits (customer_id, attempt_at);
