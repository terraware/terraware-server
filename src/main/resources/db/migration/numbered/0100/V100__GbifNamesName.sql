-- The trigram index on gbif_names.name can be used for exact lookups, but only on PostgreSQL 14
-- and higher, and even there, it is significantly slower than a B-tree index. Create a B-tree index
-- to cover exact-name queries.
CREATE INDEX ON gbif_names (name, is_scientific);
