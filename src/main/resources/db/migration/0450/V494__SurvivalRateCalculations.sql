CREATE TABLE tracking.planting_site_survival_rate_calculations
(
    planting_site_id BIGINT PRIMARY KEY REFERENCES tracking.planting_sites ON DELETE CASCADE,
    additional_calculation_requested BOOLEAN NOT NULL DEFAULT FALSE
);
