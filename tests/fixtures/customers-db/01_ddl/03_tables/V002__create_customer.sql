CREATE TABLE customers_schema.customer (
    customer_id uuid NOT NULL DEFAULT gen_random_uuid(),
    name text NOT NULL,
    identity_document text NOT NULL,
    email text,
    phone text,
    address text,
    registration_date timestamptz NOT NULL DEFAULT now(),
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT pk_customer PRIMARY KEY (customer_id),
    CONSTRAINT uq_customer_identity_document UNIQUE (identity_document),
    CONSTRAINT chk_customer_name_length CHECK (char_length(name) BETWEEN 1 AND 150),
    CONSTRAINT chk_customer_identity_document_length CHECK (char_length(identity_document) BETWEEN 1 AND 30),
    CONSTRAINT chk_customer_email_length CHECK (email IS NULL OR char_length(email) <= 255),
    CONSTRAINT chk_customer_phone_length CHECK (phone IS NULL OR char_length(phone) <= 20),
    CONSTRAINT chk_customer_address_length CHECK (address IS NULL OR char_length(address) <= 255)
);
