CREATE TABLE payments (
    payment_id         uuid PRIMARY KEY,
    order_id           uuid        NOT NULL UNIQUE,
    amount_minor       bigint      NOT NULL CHECK (amount_minor >= 0),
    currency           char(3)     NOT NULL,
    status             text        NOT NULL CHECK (status IN ('CHARGED', 'DECLINED', 'REFUND_PENDING', 'REFUNDED')),
    provider_reference text,
    token_used         text        NOT NULL CHECK (token_used IN ('tok_success', 'tok_declined', 'tok_error')),
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refunds (
    refund_id    uuid PRIMARY KEY,
    payment_id   uuid        NOT NULL UNIQUE REFERENCES payments (payment_id),
    amount_minor bigint      NOT NULL CHECK (amount_minor >= 0),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inbox (
    event_id     uuid PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    event_id     uuid PRIMARY KEY,
    aggregate_id uuid        NOT NULL,
    topic        text        NOT NULL,
    payload      jsonb       NOT NULL,
    published    boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL
);

CREATE INDEX idx_payment_outbox_unpublished
    ON outbox (created_at)
    WHERE published = false;
