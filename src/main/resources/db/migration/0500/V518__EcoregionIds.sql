-- The Eco ID is already a unique numeric identifier for an ecoregion, so use it directly
-- rather than having a synthetic key. The synthetic ID was used as a foreign key, so we need
-- to make temporary copies of the dependent tables with the new IDs.
CREATE TEMPORARY TABLE temp_ecoregion_countries AS
    SELECT e.eco_id::BIGINT as ecoregion_id,
           ec.country_code
    FROM ecoregions e
    JOIN ecoregion_countries ec ON e.id = ec.ecoregion_id;

CREATE TEMPORARY TABLE temp_ecoregion_botanical_countries AS
    SELECT e.eco_id::BIGINT as ecoregion_id,
           ebc.botanical_country_id
    FROM ecoregions e
    JOIN ecoregion_botanical_countries ebc ON e.id = ebc.ecoregion_id;

TRUNCATE TABLE ecoregion_countries;
TRUNCATE TABLE ecoregion_botanical_countries;

-- Move the existing IDs out of the way since they might collide with the new ones.
-- Both the existing and the new IDs are positive integers.
UPDATE ecoregions SET id = -id;

UPDATE ecoregions SET id = eco_id::BIGINT;

ALTER TABLE ecoregions DROP COLUMN eco_id;
ALTER TABLE ecoregions DROP COLUMN object_id;

-- We no longer want to auto-allocate IDs since they come from an external data source.
ALTER TABLE ecoregions ALTER COLUMN id DROP IDENTITY;

INSERT INTO ecoregion_countries (ecoregion_id, country_code)
SELECT ecoregion_id, country_code
FROM temp_ecoregion_countries;

INSERT INTO ecoregion_botanical_countries (ecoregion_id, botanical_country_id)
SELECT ecoregion_id, botanical_country_id
FROM temp_ecoregion_botanical_countries;
