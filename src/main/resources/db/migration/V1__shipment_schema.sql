CREATE TABLE shipments (
    id             uuid PRIMARY KEY,
    order_id       uuid        NOT NULL UNIQUE,
    user_id        uuid,
    status         text        NOT NULL,
    postal_code    text        NOT NULL,
    city           text        NOT NULL,
    state          text        NOT NULL,
    country        text        NOT NULL,
    shipping_minor bigint      NOT NULL,
    currency       char(3)     NOT NULL,
    promised_from  date        NOT NULL,
    promised_to    date        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_shipments_user_id ON shipments (user_id);

CREATE TABLE outbox (
    id            uuid PRIMARY KEY,
    aggregate_id  uuid        NOT NULL,
    event_id      text        NOT NULL UNIQUE,
    event_type    text        NOT NULL,
    partition_key text        NOT NULL,
    payload       jsonb       NOT NULL,
    occurred_at   timestamptz NOT NULL,
    published_at  timestamptz NULL
);

CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;

CREATE TABLE inbox (
    event_id     text PRIMARY KEY,
    event_type   text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);
