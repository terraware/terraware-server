CREATE TABLE "layer_types" (
    "id" INTEGER PRIMARY KEY,
    "name" TEXT UNIQUE NOT NULL
);

CREATE TABLE "layers" (
    "id" BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    "site_id" BIGINT NOT NULL REFERENCES sites,
    "layer_type_id" INTEGER NOT NULL REFERENCES layer_types,  -- changed from type_id in gis-server
    "tile_set_name" TEXT,
    "proposed" BOOLEAN NOT NULL,
    "hidden" BOOLEAN NOT NULL,
    "deleted" BOOLEAN NOT NULL,
    "created_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "modified_time" TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE "shape_types" (
    "id" INTEGER PRIMARY KEY,
    "name" TEXT UNIQUE NOT NULL
);

-- see V39__AddGeomToFeatures.sql for additional geom column
CREATE TABLE "features" (
    "id" BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    "layer_id" BIGINT NOT NULL REFERENCES layers,
    "shape_type_id" INTEGER NOT NULL REFERENCES shape_types,
    "altitude" FLOAT,
    "gps_horiz_accuracy" FLOAT,
    "gps_vert_accuracy" FLOAT,
    "attrib" TEXT,
    "notes" TEXT,
    "entered_time" TIMESTAMP WITH TIME ZONE,
    "created_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "modified_time" TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE "plants" (
    "feature_id" BIGINT PRIMARY KEY NOT NULL REFERENCES features,
    "label" TEXT,
    "species_id" BIGINT REFERENCES species,
    "natural_regen" BOOLEAN,
    "date_planted" DATE,
    "created_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "modified_time" TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE "health_states" (
    "id" INTEGER PRIMARY KEY,
    "name" TEXT UNIQUE NOT NULL
);

CREATE TABLE "plant_observations" (
    "id" BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    "feature_id" BIGINT REFERENCES plants,
    "timestamp" TIMESTAMP WITH TIME ZONE NOT NULL,
    "health_state_id" INTEGER REFERENCES health_states,
    "flowers" BOOLEAN,
    "seeds" BOOLEAN,
    "pests" TEXT,
    "height" FLOAT,
    "dbh" FLOAT,
    "created_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "modified_time" TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE "photos" (
    "id" BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    "feature_id" BIGINT NOT NULL REFERENCES features,
    "plant_observation_id" BIGINT REFERENCES plant_observations,
    "heading" FLOAT,
    "orientation" FLOAT,
    "captured_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "account_id" BIGINT references accounts,  -- TODO rename to user_id and reference users table
    "file_name" TEXT NOT NULL,
    "content_type" TEXT NOT NULL,
    "size" BIGINT NOT NULL,
    "created_time" TIMESTAMP WITH TIME ZONE NOT NULL,
    "modified_time" TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE "thumbnail" (
    "id" BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    "photo_id" BIGINT NOT NULL REFERENCES photos,
    "file_name" TEXT UNIQUE NOT NULL,
    "width" INTEGER NOT NULL,
    "height" INTEGER NOT NULL
);
