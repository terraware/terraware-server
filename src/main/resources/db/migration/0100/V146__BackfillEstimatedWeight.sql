UPDATE seedbank.accessions
SET est_weight_grams = remaining_grams,
    est_weight_quantity = remaining_quantity,
    est_weight_units_id = remaining_units_id
WHERE est_weight_grams IS NULL
  AND remaining_grams IS NOT NULL;
