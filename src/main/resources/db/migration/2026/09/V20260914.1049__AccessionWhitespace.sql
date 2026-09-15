-- The COLLATEs are to match all Unicode whitespace characters rather than just ASCII ones.
DELETE FROM seedbank.accession_collectors
WHERE name ~ '^[[:space:]]*$' COLLATE "en-x-icu";

UPDATE seedbank.accession_collectors
SET name = trim(regexp_replace(name COLLATE "en-x-icu", '[[:space:]]+', ' ', 'g'))
WHERE name != trim(regexp_replace(name COLLATE "en-x-icu", '[[:space:]]+', ' ', 'g'));

UPDATE seedbank.accessions
SET collection_site_name =
        nullif(trim(regexp_replace(collection_site_name COLLATE "en-x-icu", '[[:space:]]+', ' ', 'g')), '')
WHERE collection_site_name !=
      trim(regexp_replace(collection_site_name COLLATE "en-x-icu", '[[:space:]]+', ' ', 'g'));

ALTER TABLE seedbank.accession_collectors
    ADD CONSTRAINT name_whitespace CHECK (
        name !~ '\s\s' COLLATE "en-x-icu"
            AND name !~ '^\s' COLLATE "en-x-icu"
            AND name !~ '\s$' COLLATE "en-x-icu"
        );
ALTER TABLE seedbank.accessions
    ADD CONSTRAINT site_name_whitespace CHECK (
        collection_site_name !~ '\s\s' COLLATE "en-x-icu"
            AND collection_site_name !~ '^\s' COLLATE "en-x-icu"
            AND collection_site_name !~ '\s$' COLLATE "en-x-icu"
        );
