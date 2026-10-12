CREATE TABLE customers_schema.idempotency_key (
    key text NOT NULL,
    customer_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_key PRIMARY KEY (key),
    CONSTRAINT chk_idempotency_key_length CHECK (char_length(key) BETWEEN 8 AND 128)
);
