-- Fix weight-based accessions where people added cut test results before we added the validation
-- rule to require subset count/weight. Treat the seeds as having negligible weight (but not
-- zero, since zero weights aren't allowed).
UPDATE seedbank.accessions
SET
    subset_count = 1,
    subset_weight_quantity = 0.000001,
    subset_weight_units_id = (SELECT id FROM seedbank.seed_quantity_units WHERE name = 'Milligrams')
WHERE total_grams IS NOT NULL
AND subset_count IS NULL
AND (
    cut_test_seeds_compromised IS NOT NULL
    OR cut_test_seeds_empty IS NOT NULL
    OR cut_test_seeds_filled IS NOT NULL
);

-- Fix accessions with withdrawals created by buggy logic that could calculate negative remaining
-- seed counts. Normalize the remaining quantities to zero so the data can be loaded into model
-- objects; the correct quantities will be recalculated by the model code.
UPDATE seedbank.accessions
SET remaining_quantity = 0
WHERE remaining_quantity < 0;

UPDATE seedbank.viability_tests
SET remaining_quantity = 0
WHERE remaining_quantity < 0;

UPDATE seedbank.withdrawals
SET remaining_quantity = 0
WHERE remaining_quantity < 0;
