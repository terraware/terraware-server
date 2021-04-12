INSERT INTO accession_state (id, name, active)
VALUES (10, 'Pending', TRUE),
       (20, 'Processing', TRUE),
       (30, 'Processed', TRUE),
       (40, 'Drying', TRUE),
       (50, 'Dried', TRUE),
       (60, 'In Storage', TRUE),
       (70, 'Withdrawn', FALSE),
       (80, 'Nursery', FALSE)
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_seed_type (id, name)
VALUES (1, 'Fresh'),
       (2, 'Stored')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_substrate (id, name)
VALUES (1, 'Nursery Media'),
       (2, 'Agar Petri Dish'),
       (3, 'Paper Petri Dish'),
       (4, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_test_type (id, name)
VALUES (1, 'Lab'),
       (2, 'Nursery')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_treatment (id, name)
VALUES (1, 'Soak'),
       (2, 'Scarify'),
       (3, 'GA3'),
       (4, 'Stratification'),
       (5, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO notification_type (id, name)
VALUES (1, 'Alert'),
       (2, 'State'),
       (3, 'Date')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO processing_method (id, name)
VALUES (1, 'Count'),
       (2, 'Weight')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_seed_type (id, name)
VALUES (1, 'Fresh'),
       (2, 'Stored')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO site_module_type (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Desalination'),
       (3, 'Reverse Osmosis')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO source_plant_origin (id, name)
VALUES (1, 'Wild'),
       (2, 'Outplant')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO storage_condition (id, name)
VALUES (1, 'Refrigerator'),
       (2, 'Freezer')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO timeseries_type (id, name)
VALUES (1, 'Numeric'),
       (2, 'Text')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO withdrawal_purpose (id, name)
VALUES (1, 'Propagation'),
       (2, 'Outreach or Education'),
       (3, 'Research'),
       (4, 'Broadcast'),
       (5, 'Share with Another Site'),
       (6, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;
