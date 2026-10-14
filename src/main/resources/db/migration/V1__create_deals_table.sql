CREATE TABLE deals (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    title VARCHAR(100) NOT NULL,
    price_paise INTEGER NOT NULL CHECK (price_paise >= 1 AND price_paise <= 1000000),
    stock_total INTEGER NOT NULL CHECK (stock_total >= 1 AND stock_total <= 10000),
    stock_available INTEGER NOT NULL CHECK (stock_available >= 0 AND stock_available <= stock_total),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT check_starts_before_ends CHECK (starts_at < ends_at)
);

CREATE INDEX idx_deals_restaurant_id ON deals (restaurant_id);
