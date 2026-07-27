CREATE TABLE countries (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    CONSTRAINT countries_code_length CHECK ( LENGTH(code) = 2 ),
    CONSTRAINT countries_code_caps CHECK ( code = UPPER(code) )
);

CREATE TABLE country_subdivisions (
    code TEXT PRIMARY KEY,
    country_code TEXT NOT NULL REFERENCES countries (code),
    name TEXT NOT NULL,
    CONSTRAINT country_subdivisions_code_matches_country
        CHECK ( SUBSTR(code, 1, 2) = country_code ),
    CONSTRAINT country_subdivisions_code_length
        CHECK ( LENGTH(code) BETWEEN 4 AND 6 )
);

CREATE INDEX ON country_subdivisions (country_code);

ALTER TABLE organizations ADD COLUMN country_code TEXT REFERENCES countries (code);
ALTER TABLE organizations ADD COLUMN country_subdivision_code TEXT REFERENCES country_subdivisions (code);
ALTER TABLE organizations ADD CONSTRAINT country_code_matches_subdivision
    CHECK ( country_subdivision_code IS NULL OR
            SUBSTR(country_subdivision_code, 1, 2) = country_code );

ALTER TABLE organizations ADD COLUMN description TEXT;
ALTER TABLE organizations DROP COLUMN location;
