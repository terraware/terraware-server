CREATE TABLE tracking.simplified_planting_sites (
    planting_site_id BIGINT PRIMARY KEY REFERENCES tracking.planting_sites ON DELETE CASCADE,
    boundary GEOMETRY(MultiPolygon) NOT NULL,
    exclusion GEOMETRY(MultiPolygon)
);

CREATE TABLE tracking.simplified_strata (
    stratum_id BIGINT PRIMARY KEY REFERENCES tracking.strata ON DELETE CASCADE,
    boundary GEOMETRY(MultiPolygon) NOT NULL
);

CREATE TABLE tracking.simplified_substrata (
    substratum_id BIGINT PRIMARY KEY REFERENCES tracking.substrata ON DELETE CASCADE,
    boundary GEOMETRY(MultiPolygon) NOT NULL
);

CREATE TABLE tracking.simplified_planting_site_histories (
    planting_site_history_id BIGINT PRIMARY KEY REFERENCES tracking.planting_site_histories ON DELETE CASCADE,
    boundary GEOMETRY(MultiPolygon) NOT NULL,
    exclusion GEOMETRY(MultiPolygon)
);

CREATE TABLE tracking.simplified_stratum_histories (
    stratum_history_id BIGINT PRIMARY KEY REFERENCES tracking.stratum_histories ON DELETE CASCADE,
    boundary GEOMETRY(MultiPolygon) NOT NULL
);

CREATE TABLE tracking.simplified_substratum_histories (
   substratum_history_id BIGINT PRIMARY KEY REFERENCES tracking.substratum_histories ON DELETE CASCADE,
   boundary GEOMETRY(MultiPolygon) NOT NULL
);
