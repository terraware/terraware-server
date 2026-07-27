UPDATE tracking.observed_plot_species_totals t
SET species_id = NULL, species_name = s.scientific_name, certainty_id = 2
FROM species s
WHERE t.species_id = s.id
AND s.deleted_time IS NOT NULL;

UPDATE tracking.observed_site_species_totals t
SET species_id = NULL, species_name = s.scientific_name, certainty_id = 2
FROM species s
WHERE t.species_id = s.id
AND s.deleted_time IS NOT NULL;

UPDATE tracking.observed_subzone_species_totals t
SET species_id = NULL, species_name = s.scientific_name, certainty_id = 2
FROM species s
WHERE t.species_id = s.id
AND s.deleted_time IS NOT NULL;

UPDATE tracking.observed_zone_species_totals t
SET species_id = NULL, species_name = s.scientific_name, certainty_id = 2
FROM species s
WHERE t.species_id = s.id
AND s.deleted_time IS NOT NULL;

UPDATE tracking.recorded_plants t
SET species_id = NULL, species_name = s.scientific_name, certainty_id = 2
FROM species s
WHERE t.species_id = s.id
AND s.deleted_time IS NOT NULL;

UPDATE tracking.observation_biomass_species t
SET species_id = NULL, scientific_name = s.scientific_name
FROM species s
WHERE t.species_id = s.id
AND s.deleted_time IS NOT NULL;
