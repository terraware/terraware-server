INSERT INTO tracking.planting_subzone_populations (
    planting_subzone_id,
    species_id,
    total_plants,
    plants_since_last_observation)
SELECT plantings.planting_subzone_id,
       plantings.species_id,
       SUM(plantings.num_plants),
       SUM(plantings.num_plants)
FROM tracking.plantings
WHERE plantings.planting_subzone_id IS NOT NULL
GROUP BY plantings.planting_site_id, plantings.planting_subzone_id, plantings.species_id
ON CONFLICT (planting_subzone_id, species_id)
    DO UPDATE
    SET total_plants = excluded.total_plants,
        plants_since_last_observation = excluded.plants_since_last_observation;

INSERT INTO tracking.planting_zone_populations (
    planting_zone_id,
    species_id,
    total_plants,
    plants_since_last_observation)
SELECT sz.planting_zone_id,
       pop.species_id,
       SUM(pop.total_plants),
       SUM(pop.plants_since_last_observation)
FROM tracking.planting_subzone_populations pop
         JOIN tracking.planting_subzones sz ON pop.planting_subzone_id = sz.id
GROUP BY sz.planting_zone_id, pop.species_id
ON CONFLICT (planting_zone_id, species_id)
    DO UPDATE
    SET total_plants = excluded.total_plants,
        plants_since_last_observation = excluded.plants_since_last_observation;

INSERT INTO tracking.planting_site_populations (
    planting_site_id,
    species_id,
    total_plants,
    plants_since_last_observation)
SELECT pz.planting_site_id,
       pop.species_id,
       SUM(pop.total_plants),
       SUM(pop.plants_since_last_observation)
FROM tracking.planting_zone_populations pop
        JOIN tracking.planting_zones pz ON pop.planting_zone_id = pz.id
GROUP BY pz.planting_site_id, pop.species_id
ON CONFLICT (planting_site_id, species_id)
    DO UPDATE
    SET total_plants = excluded.total_plants,
        plants_since_last_observation = excluded.plants_since_last_observation;
