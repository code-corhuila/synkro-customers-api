-- HU-CLI-05: append-only record of who performed a controlled transition (ADMIN reactivation).
CREATE TABLE customers_schema.customer_audit (
    audit_id uuid NOT NULL DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL,
    action text NOT NULL,
    actor_id uuid NOT NULL,
    correlation_id text,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_customer_audit PRIMARY KEY (audit_id),
    CONSTRAINT chk_customer_audit_action CHECK (action IN ('REACTIVATED')),
    CONSTRAINT chk_customer_audit_correlation_length CHECK (correlation_id IS NULL OR char_length(correlation_id) BETWEEN 1 AND 128)
);
