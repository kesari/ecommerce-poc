CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         text        NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE addresses (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id),
    full_name    text        NOT NULL,
    line1        text        NOT NULL,
    line2        text,
    city         text        NOT NULL,
    state        text,
    postal_code  text        NOT NULL,
    country      char(2)     NOT NULL,
    phone_number text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_addresses_user ON addresses (user_id);
