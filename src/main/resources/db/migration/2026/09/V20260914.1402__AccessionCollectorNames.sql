CREATE COLLATION case_accent_insensitive (
    provider = icu,
    locale = 'und-u-ks-level1',
    deterministic = false
);

ALTER TABLE seedbank.accession_collectors
    ADD COLUMN organization_id BIGINT REFERENCES organizations (id) ON DELETE CASCADE;

UPDATE seedbank.accession_collectors ac
SET organization_id = (
    SELECT f.organization_id
    FROM facilities f
    JOIN seedbank.accessions a ON f.id = a.facility_id
    WHERE a.id = ac.accession_id
);

ALTER TABLE seedbank.accession_collectors
    ALTER COLUMN organization_id SET NOT NULL,
    DROP CONSTRAINT accession_collectors_name_check,
    ALTER COLUMN name TYPE TEXT COLLATE case_accent_insensitive,
    ADD CONSTRAINT name_not_empty CHECK (length(name) > 0);

CREATE INDEX ON seedbank.accession_collectors (organization_id, name);

CREATE TEMPORARY TABLE collector_names (
    organization_id BIGINT NOT NULL,
    name TEXT COLLATE case_accent_insensitive NOT NULL,
    UNIQUE (organization_id, name)
);

INSERT INTO collector_names (organization_id, name)
SELECT DISTINCT ON (f.organization_id, ac.name)
    f.organization_id, ac.name
FROM seedbank.accession_collectors ac
         JOIN seedbank.accessions a ON ac.accession_id = a.id
         JOIN facilities f ON a.facility_id = f.id
ORDER BY
    f.organization_id,
    ac.name,
    CASE
        WHEN ac.name = INITCAP(ac.name COLLATE "en-x-icu") THEN 1
        WHEN ac.name <> LOWER(ac.name COLLATE "en-x-icu") THEN 2
        ELSE 3
    END;

UPDATE seedbank.accession_collectors ac
SET name = (
    SELECT cn.name
    FROM collector_names cn
    WHERE cn.organization_id = ac.organization_id
    AND cn.name = ac.name
);

DROP TABLE collector_names;
