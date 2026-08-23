CREATE TABLE stock (
    product_id uuid PRIMARY KEY,
    available  int NOT NULL CHECK (available >= 0)
);

CREATE TABLE reservations (
    reservation_id uuid PRIMARY KEY,
    order_id       uuid        NOT NULL UNIQUE,
    status         text        NOT NULL,
    expires_at     timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reservation_items (
    reservation_id uuid NOT NULL REFERENCES reservations (reservation_id),
    product_id     uuid NOT NULL,
    quantity       int  NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, product_id)
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
