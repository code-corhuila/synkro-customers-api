ALTER TABLE customers_schema.customer_audit
    ADD CONSTRAINT fk_customer_audit_customer
    FOREIGN KEY (customer_id) REFERENCES customers_schema.customer (customer_id)
    ON DELETE RESTRICT;
