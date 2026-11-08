-- HU-CLI-05: exact and prefix lookup of the folded identity document (equality and range under the "C" collation).
-- No index on name_search: the token search over the stored text scans in about 35 ms at 100 000 customers
-- (docs/hu-cli-05-search-benchmark.md), and a substring match cannot use a btree without an extension.
CREATE INDEX idx_customer_document_search
    ON customers_schema.customer (document_search);
