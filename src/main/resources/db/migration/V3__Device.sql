ALTER TABLE seedbank
    RENAME TO site;

CREATE TABLE seedbank_system (
    id        BIGSERIAL PRIMARY KEY,
    site_id   INTEGER NOT NULL REFERENCES site,
    name      TEXT NOT NULL,
    type      TEXT NOT NULL, -- Should this be an enumerated value?
    latitude  NUMERIC(10, 7),
    longitude NUMERIC(10, 7)
);

CREATE TABLE device_type (
    id   SERIAL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE device (
    id                 BIGSERIAL PRIMARY KEY,
    seedbank_system_id BIGINT NOT NULL REFERENCES seedbank_system,
    device_type_id     INTEGER NOT NULL REFERENCES device_type,
    watchdog_seconds   INTEGER
);

COMMENT ON COLUMN device.watchdog_seconds
    IS 'If not null, send out notification if no recent update for this device';

CREATE TABLE "sequence" (
    id             BIGSERIAL PRIMARY KEY,
    device_id      BIGINT NOT NULL REFERENCES device,
    name           TEXT NOT NULL,
    units          TEXT NOT NULL, -- Should this be an enumerated value?
    decimal_places INTEGER NOT NULL,
    type           TEXT NOT NULL  -- Should this be an enum type?
);

CREATE TABLE sequence_value (
    sequence_id  BIGINT NOT NULL REFERENCES sequence,
    updated_time TIMESTAMP WITH TIME ZONE NOT NULL,
    value        TEXT NOT NULL
);

CREATE UNIQUE INDEX ON sequence_value (sequence_id, updated_time DESC);

CREATE VIEW latest_sequence_value AS
    SELECT sequence_id, updated_time, value
    FROM (
             SELECT sequence_id,
                    updated_time,
                    value,
                    row_number()
                    OVER (PARTITION BY sequence_id ORDER BY updated_time DESC) AS row_num
             FROM sequence_value) AS ordered
    WHERE row_num = 1;
