UPDATE species
SET conservation_category_id = 'EN'
WHERE conservation_category_id IS NULL
AND endangered;
