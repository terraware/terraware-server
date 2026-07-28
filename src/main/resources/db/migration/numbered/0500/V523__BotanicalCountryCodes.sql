-- The TDWG Level 3 code is already a unique identifier for a botanical country, so use it
-- directly rather than having a synthetic ID.
ALTER TABLE country_botanical_countries
    ADD COLUMN botanical_country_code TEXT;

UPDATE country_botanical_countries cbc
SET botanical_country_code = (
    SELECT bc.level3_code
    FROM botanical_countries bc
    WHERE bc.id = cbc.botanical_country_id
);

CREATE INDEX ON country_botanical_countries (botanical_country_code);

ALTER TABLE country_botanical_countries
    ALTER COLUMN botanical_country_code SET NOT NULL,
    DROP CONSTRAINT country_botanical_countries_pkey,
    DROP COLUMN botanical_country_id,
    ADD PRIMARY KEY (country_code, botanical_country_code);

ALTER TABLE wcvp_distributions
    ADD COLUMN botanical_country_code TEXT;

UPDATE wcvp_distributions wd
SET botanical_country_code = (
    SELECT bc.level3_code
    FROM botanical_countries bc
    WHERE bc.id = wd.botanical_country_id
);

CREATE INDEX ON wcvp_distributions (botanical_country_code);

ALTER TABLE wcvp_distributions
    DROP COLUMN botanical_country_id;

-- We have a unique constraint on level3_code, but we want a primary key instead since primary keys
-- are treated differently by jOOQ.
ALTER TABLE botanical_countries
    DROP COLUMN id,
    DROP CONSTRAINT botanical_countries_level3_code_key,
    ADD PRIMARY KEY (level3_code);

ALTER TABLE country_botanical_countries
    ADD FOREIGN KEY (botanical_country_code)
        REFERENCES botanical_countries (level3_code) ON DELETE CASCADE;

ALTER TABLE wcvp_distributions
    ADD FOREIGN KEY (botanical_country_code)
        REFERENCES botanical_countries (level3_code) ON DELETE SET NULL;
