CREATE VIEW tracking.planting_site_populations AS
SELECT planting_site_id, species_id, SUM(num_plants) AS total_plants
FROM tracking.plantings
GROUP BY planting_site_id, species_id;

CREATE VIEW tracking.plot_populations AS
SELECT planting_site_id, plot_id, species_id, SUM(num_plants) AS total_plants
FROM tracking.plantings
WHERE plot_id IS NOT NULL
GROUP BY planting_site_id, plot_id, species_id;
