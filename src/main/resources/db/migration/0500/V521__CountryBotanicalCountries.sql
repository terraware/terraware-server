CREATE TABLE country_botanical_countries (
    country_code TEXT NOT NULL REFERENCES countries ON DELETE CASCADE,
    botanical_country_id BIGINT NOT NULL REFERENCES botanical_countries ON DELETE CASCADE,

    PRIMARY KEY (country_code, botanical_country_id)
);

CREATE INDEX ON country_botanical_countries (botanical_country_id);
