ALTER TABLE organizations
    ADD COLUMN botanical_country_code TEXT REFERENCES botanical_countries ON DELETE SET NULL;

CREATE INDEX ON organizations (botanical_country_code);

ALTER TABLE projects
    ADD COLUMN botanical_country_code TEXT REFERENCES botanical_countries ON DELETE SET NULL;

CREATE INDEX ON projects (botanical_country_code);
