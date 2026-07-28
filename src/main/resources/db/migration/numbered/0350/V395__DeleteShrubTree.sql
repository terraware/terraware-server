-- For all species that currently have the "Shrub/Tree" growth form, insert "Shrub" and "Tree"
-- growth forms if they aren't already there.
--
-- 1 = Tree, 2 = Shrub, 11 = Shrub/Tree
INSERT INTO species_growth_forms (species_id, growth_form_id)
SELECT species_id, series
FROM species_growth_forms,
     LATERAL generate_series(1, 2) AS series
WHERE growth_form_id = 11
ON CONFLICT DO NOTHING;

DELETE FROM species_growth_forms WHERE growth_form_id = 11;

DELETE FROM growth_forms WHERE id = 11;
