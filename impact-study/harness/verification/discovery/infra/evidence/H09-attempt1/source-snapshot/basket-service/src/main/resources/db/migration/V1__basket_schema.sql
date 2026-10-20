CREATE TABLE coupons (
    code             text PRIMARY KEY,
    discount_percent int     NOT NULL CHECK (discount_percent BETWEEN 1 AND 100),
    active           boolean NOT NULL
);

CREATE TABLE baskets (
    id             uuid PRIMARY KEY,
    user_id        uuid        NOT NULL UNIQUE,
    coupon_code    text REFERENCES coupons (code),
    status         text        NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CHECKED_OUT')),
    basket_version bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE basket_items (
    basket_id       uuid    NOT NULL REFERENCES baskets (id),
    product_id      uuid    NOT NULL,
    name            text    NOT NULL,
    unit_price_minor bigint NOT NULL,
    currency        char(3) NOT NULL,
    quantity        int     NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (basket_id, product_id)
);
