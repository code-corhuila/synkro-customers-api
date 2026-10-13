CREATE INDEX idx_idempotency_key_customer_id
    ON customers_schema.idempotency_key (customer_id);
