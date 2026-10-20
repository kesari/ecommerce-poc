CREATE TABLE products (
    id          uuid PRIMARY KEY,
    name        text        NOT NULL,
    description text,
    image_url   text,
    price_minor bigint      NOT NULL CHECK (price_minor >= 0),
    currency    char(3)     NOT NULL DEFAULT 'INR',
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
