-- Drop some columns we aren't using and that are easy to add again if/when we need them.
ALTER TABLE species_options DROP COLUMN project_id;
ALTER TABLE species_options DROP COLUMN site_id;

-- Stop requiring primary names to be globally unique; they need to be per-organization.
CREATE INDEX ON species (name);
ALTER TABLE species DROP CONSTRAINT species_name_key;
ALTER TABLE species_names DROP CONSTRAINT species_names_unique;
ALTER TABLE species_names ADD CONSTRAINT species_names_unique UNIQUE (organization_id, name);

CREATE INDEX ON species_options (organization_id);

-- Make per-organization copies of species. Keep track of the ID mappings so we can update plants
-- and accessions to point to the new IDs.
ALTER TABLE species ADD COLUMN old_species_id BIGINT REFERENCES species (id);
ALTER TABLE species ADD COLUMN organization_id BIGINT REFERENCES organizations (id);

INSERT INTO species (old_species_id, organization_id, created_time, modified_time, name,
                     plant_form_id, conservation_status_id, rare_type_id, tsn, is_scientific,
                     native_range)
SELECT s.id,
       o.id,
       s.created_time,
       s.modified_time,
       s.name,
       s.plant_form_id,
       s.conservation_status_id,
       s.rare_type_id,
       s.tsn,
       s.is_scientific,
       s.native_range
FROM species s,
     organizations o
WHERE s.organization_id IS NULL;

INSERT INTO species_options (species_id, organization_id, created_time, modified_time)
SELECT id, organization_id, created_time, modified_time
FROM species
WHERE organization_id IS NOT NULL;

INSERT INTO species_names (species_id, organization_id, name, locale, created_time, modified_time)
SELECT s.id, s.organization_id, sn.name, sn.locale, sn.created_time, sn.modified_time
FROM species s
         JOIN species_names sn ON s.old_species_id = sn.species_id
WHERE s.organization_id IS NOT NULL;

DELETE
FROM species_names
WHERE organization_id IS NULL;

ALTER TABLE species_names ALTER COLUMN organization_id SET NOT NULL;

UPDATE accessions a
SET species_id = (
    SELECT s.id
    FROM species s
    WHERE s.old_species_id = a.species_id
      AND s.organization_id = (
        SELECT p.organization_id
        FROM projects p
                 JOIN sites s ON s.project_id = p.id
                 JOIN facilities f ON s.id = f.site_id
        WHERE a.facility_id = f.id
    )
)
WHERE species_id IS NOT NULL;

UPDATE plants p
SET species_id = (
    SELECT s.id
    FROM species s
    WHERE s.old_species_id = p.species_id
      AND s.organization_id = (
        SELECT projects.organization_id
        FROM projects
                 JOIN sites s ON projects.id = s.project_id
                 JOIN layers l ON s.id = l.site_id
                 JOIN features f ON l.id = f.layer_id
        WHERE p.feature_id = f.id
    )
)
WHERE species_id IS NOT NULL;

-- Old species should no longer be referenced by anything.
ALTER TABLE species DROP COLUMN old_species_id;
DELETE
FROM species
WHERE organization_id IS NULL;

ALTER TABLE species DROP COLUMN organization_id;
