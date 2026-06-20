CREATE TABLE wcvp_taxa (
    taxon_id BIGINT NOT NULL PRIMARY KEY,
    accepted_name_usage_id BIGINT,
    family TEXT,
    genus TEXT,
    infraspecific_epithet TEXT,
    nomenclatural_status TEXT,
    original_name_usage_id BIGINT,
    parent_name_usage_id BIGINT,
    scientific_name TEXT NOT NULL,
    specific_epithet TEXT,
    taxon_rank TEXT NOT NULL,
    taxonomic_status TEXT NOT NULL
);

CREATE TABLE wcvp_distributions (
    taxon_id BIGINT NOT NULL REFERENCES wcvp_taxa ON DELETE CASCADE,
    level3_code TEXT NOT NULL,
    establishment_means TEXT,
    occurrence_status TEXT,
    threat_status TEXT,
    botanical_country_id BIGINT REFERENCES botanical_countries ON DELETE CASCADE,

    PRIMARY KEY (taxon_id, level3_code)
);

CREATE INDEX ON wcvp_distributions (botanical_country_id);
