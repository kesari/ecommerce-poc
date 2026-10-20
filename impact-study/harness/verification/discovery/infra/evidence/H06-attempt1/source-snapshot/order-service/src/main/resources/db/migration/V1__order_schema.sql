CREATE TABLE quotes (
    quote_id         uuid PRIMARY KEY,
    user_id          uuid        NOT NULL,
    basket_version   bigint      NOT NULL,
    address_snapshot jsonb       NOT NULL,
    subtotal_minor   bigint      NOT NULL,
    discount_minor   bigint      NOT NULL,
    shipping_minor   bigint      NOT NULL,
    tax_minor        bigint      NOT NULL,
    total_minor      bigint      NOT NULL,
    currency         char(3)     NOT NULL,
    promised_from    date        NOT NULL,
    promised_to      date        NOT NULL,
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE quote_lines (
    quote_id         uuid   NOT NULL REFERENCES quotes (quote_id),
    product_id       uuid   NOT NULL,
    name             text   NOT NULL,
    unit_price_minor bigint NOT NULL,
    quantity         int    NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (quote_id, product_id)
);

CREATE TABLE orders (
    order_id         uuid PRIMARY KEY,
    user_id          uuid        NOT NULL,
    quote_id         uuid        NOT NULL,
    status           text        NOT NULL,
    basket_version   bigint      NOT NULL,
    address_snapshot jsonb       NOT NULL,
    payment_method   text        NOT NULL,
    payment_token    text        NOT NULL,
    total_minor      bigint      NOT NULL,
    currency         char(3)     NOT NULL,
    payment_id       uuid        NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_user_id ON orders (user_id);

CREATE TABLE order_lines (
    order_id         uuid   NOT NULL REFERENCES orders (order_id),
    product_id       uuid   NOT NULL,
    name             text   NOT NULL,
    unit_price_minor bigint NOT NULL,
    quantity         int    NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, product_id)
);

CREATE TABLE order_status_history (
    id          bigserial PRIMARY KEY,
    order_id    uuid        NOT NULL REFERENCES orders (order_id),
    status      text        NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_history_order ON order_status_history (order_id, id);

CREATE TABLE idempotency_keys (
    idempotency_key text PRIMARY KEY,
    user_id         uuid        NOT NULL,
    request_hash    text        NOT NULL,
    order_id        uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
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

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published = false;
