ALTER TABLE tracking.planting_sites ADD COLUMN planting_season_start_month INTEGER;
ALTER TABLE tracking.planting_sites ADD COLUMN planting_season_end_month INTEGER;

ALTER TABLE tracking.planting_sites ADD CONSTRAINT start_month_valid
    CHECK (planting_season_start_month BETWEEN 1 AND 12);
ALTER TABLE tracking.planting_sites ADD CONSTRAINT end_month_valid
    CHECK (planting_season_end_month BETWEEN 1 AND 12);
