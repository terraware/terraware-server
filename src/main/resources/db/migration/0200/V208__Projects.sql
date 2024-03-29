CREATE TABLE projects (
    id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    created_by BIGINT NOT NULL DEFAULT -1 REFERENCES users ON DELETE SET DEFAULT,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL DEFAULT -1 REFERENCES users ON DELETE SET DEFAULT,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    organization_id BIGINT NOT NULL REFERENCES organizations,
    name TEXT NOT NULL,
    description TEXT,

    UNIQUE (organization_id, name)
);

ALTER TABLE seedbank.accessions ADD COLUMN project_id BIGINT REFERENCES projects ON DELETE SET NULL;
ALTER TABLE nursery.batches ADD COLUMN project_id BIGINT REFERENCES projects ON DELETE SET NULL;
ALTER TABLE tracking.planting_sites ADD COLUMN project_id BIGINT REFERENCES projects ON DELETE SET NULL;

CREATE INDEX ON projects (created_by);
CREATE INDEX ON projects (modified_by);
CREATE INDEX ON seedbank.accessions (project_id);
CREATE INDEX ON nursery.batches (project_id);
CREATE INDEX ON tracking.planting_sites (project_id);

CREATE OR REPLACE VIEW tracking.planting_site_summaries AS
SELECT id,
       organization_id,
       name,
       description,
       boundary,
       created_by,
       created_time,
       modified_by,
       modified_time,
       (SELECT COUNT(*)
        FROM tracking.planting_zones pz
        WHERE ps.id = pz.planting_site_id) AS num_planting_zones,
       (SELECT COUNT(*)
        FROM tracking.planting_zones pz
                 JOIN tracking.planting_subzones sz ON pz.id = sz.planting_zone_id
        WHERE ps.id = pz.planting_site_id) AS num_planting_subzones,
       time_zone,
       project_id
FROM tracking.planting_sites ps;

CREATE OR REPLACE VIEW nursery.batch_summaries AS
SELECT id,
       organization_id,
       facility_id,
       species_id,
       batch_number,
       added_date,
       ready_quantity,
       not_ready_quantity,
       germinating_quantity,
       ready_quantity + not_ready_quantity AS total_quantity,
       ready_by_date,
       notes,
       accession_id,
       COALESCE(
               (SELECT SUM(bw.ready_quantity_withdrawn + bw.not_ready_quantity_withdrawn)
                FROM nursery.batch_withdrawals bw
                WHERE b.id = bw.batch_id),
               0) AS total_quantity_withdrawn,
       version,
       project_id
FROM nursery.batches b;
