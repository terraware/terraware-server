CREATE TABLE seedbank (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL,
    longitude NUMERIC(10, 7) NOT NULL,
    language TEXT,
    timezone TEXT
);

CREATE TABLE seedbank_project (
    id SERIAL PRIMARY KEY,
    seedbank_id INTEGER NOT NULL REFERENCES seedbank,
    name TEXT NOT NULL,
    notes TEXT
);

CREATE TABLE app_device (
    id BIGSERIAL PRIMARY KEY,
    device_info JSONB,
    seed_collector_app_version TEXT
);

CREATE TABLE collection_site_location (
    id BIGSERIAL PRIMARY KEY,
    seedbank_id INTEGER NOT NULL REFERENCES seedbank,
    name TEXT NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL,
    longitude NUMERIC(10, 7) NOT NULL,
    landowner TEXT,
    notes TEXT
);

CREATE TABLE species (
    id BIGSERIAL PRIMARY KEY,
    formal_name TEXT NOT NULL
);

CREATE TABLE accession (
    id BIGSERIAL PRIMARY KEY,
    seedbank_id INTEGER NOT NULL REFERENCES seedbank,
    app_device_id BIGINT NOT NULL REFERENCES app_device,
    number INTEGER NOT NULL,
    project_id INTEGER REFERENCES seedbank_project,
    species_id BIGINT NOT NULL REFERENCES species,
    collection_site_location_id BIGINT NOT NULL REFERENCES collection_site_location,
    date_collected DATE NOT NULL,
    date_received DATE,
    active BOOLEAN NOT NULL
);
