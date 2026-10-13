ALTER TABLE customers_schema.idempotency_key
    ADD CONSTRAINT fk_idempotency_key_customer
    FOREIGN KEY (customer_id) REFERENCES customers_schema.customer (customer_id)
    ON DELETE RESTRICT;
