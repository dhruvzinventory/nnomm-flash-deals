CREATE TABLE idempotency_records (
    customer_id UUID NOT NULL,
    key VARCHAR(64) NOT NULL,
    deal_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status_code INTEGER,
    response_body TEXT,
    locked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (customer_id, key)
);

CREATE INDEX idx_idempotency_created_at ON idempotency_records (created_at);
