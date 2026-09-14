ALTER TABLE seedbank.accessions
    DROP CONSTRAINT accessions_collection_site_name_check,
    ALTER COLUMN collection_site_name TYPE TEXT COLLATE case_accent_insensitive,
    ADD CONSTRAINT collection_site_name_nonempty CHECK (length(collection_site_name) > 0);

CREATE TEMPORARY TABLE site_names (
    organization_id BIGINT NOT NULL,
    name TEXT COLLATE case_accent_insensitive NOT NULL,
    UNIQUE (organization_id, name)
);

INSERT INTO site_names (organization_id, name)
SELECT DISTINCT ON (f.organization_id, a.collection_site_name)
    f.organization_id, a.collection_site_name
FROM seedbank.accessions a
JOIN facilities f ON a.facility_id = f.id
WHERE a.collection_site_name IS NOT NULL
ORDER BY
    f.organization_id,
    a.collection_site_name,
    CASE
        WHEN a.collection_site_name = INITCAP(a.collection_site_name COLLATE "en-x-icu") THEN 1
        WHEN a.collection_site_name <> LOWER(a.collection_site_name COLLATE "en-x-icu") THEN 2
        ELSE 3
    END;

UPDATE seedbank.accessions a
SET collection_site_name = (
    SELECT sn.name
    FROM site_names sn
    WHERE sn.organization_id = (
        SELECT organization_id
        FROM facilities
        WHERE id = a.facility_id
    )
    AND sn.name = a.collection_site_name
)
WHERE a.collection_site_name IS NOT NULL;

DROP TABLE site_names;

CREATE INDEX ON seedbank.accessions (facility_id, collection_site_name);
