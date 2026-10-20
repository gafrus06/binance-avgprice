CREATE TABLE price_samples (
    id UUID PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    price NUMERIC NOT NULL CHECK (price > 0),
    fetched_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_price_samples_symbol_time ON price_samples (symbol, fetched_at, id);
