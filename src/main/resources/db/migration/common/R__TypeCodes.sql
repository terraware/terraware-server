INSERT INTO accession_notification_types (id, name)
VALUES (1, 'Alert'),
       (2, 'State'),
       (3, 'Date')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO accession_states (id, name, active)
VALUES (10, 'Pending', TRUE),
       (20, 'Processing', TRUE),
       (30, 'Processed', TRUE),
       (40, 'Drying', TRUE),
       (50, 'Dried', TRUE),
       (60, 'In Storage', TRUE),
       (70, 'Withdrawn', FALSE),
       (80, 'Nursery', FALSE)
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO facility_types (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Desalination'),
       (3, 'Reverse Osmosis')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_seed_types (id, name)
VALUES (1, 'Fresh'),
       (2, 'Stored')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_substrates (id, name)
VALUES (1, 'Nursery Media'),
       (2, 'Agar Petri Dish'),
       (3, 'Paper Petri Dish'),
       (4, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_test_types (id, name)
VALUES (1, 'Lab'),
       (2, 'Nursery')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_treatments (id, name)
VALUES (1, 'Soak'),
       (2, 'Scarify'),
       (3, 'GA3'),
       (4, 'Stratification'),
       (5, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO growth_forms (id, name)
VALUES (1, 'Tree'),
       (2, 'Shrub'),
       (3, 'Forb'),
       (4, 'Graminoid'),
       (5, 'Fern')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO processing_methods (id, name)
VALUES (1, 'Count'),
       (2, 'Weight')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO project_statuses (id, name)
VALUES (1, 'Propagating'),
       (2, 'Planting'),
       (3, 'Completed/Monitoring')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO project_types (id, name)
VALUES (1, 'Native Forest Restoration'),
       (2, 'Agroforestry'),
       (3, 'Silvopasture'),
       (4, 'Sustainable Timber')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_seed_types (id, name)
VALUES (1, 'Fresh'),
       (2, 'Stored')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO roles (id, name)
VALUES (1, 'Contributor'),
       (3, 'Admin'),
       (4, 'Owner')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seed_quantity_units (id, name)
VALUES (1, 'Seeds'),
       (2, 'Grams'),
       (3, 'Milligrams'),
       (4, 'Kilograms'),
       (5, 'Ounces'),
       (6, 'Pounds')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seed_storage_behaviors (id, name)
VALUES (1, 'Orthodox'),
       (2, 'Recalcitrant'),
       (3, 'Intermediate'),
       (4, 'Unknown')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO source_plant_origins (id, name)
VALUES (1, 'Wild'),
       (2, 'Outplant')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO storage_conditions (id, name)
VALUES (1, 'Refrigerator'),
       (2, 'Freezer')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO timeseries_types (id, name)
VALUES (1, 'Numeric'),
       (2, 'Text')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO withdrawal_purposes (id, name)
VALUES (1, 'Propagation'),
       (2, 'Outreach or Education'),
       (3, 'Research'),
       (4, 'Broadcast'),
       (5, 'Share with Another Site'),
       (6, 'Other'),
       (7, 'Germination Testing')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO notification_criticalities (id, name)
VALUES (1, 'Info'),
       (2, 'Warning'),
       (3, 'Error'),
       (4, 'Success');

INSERT INTO notification_types (id, name, notification_criticality_id)
VALUES (1, 'User Added to Organization', 1),
       (2, 'Facility Idle', 2),
       (3, 'Facility Alert Requested', 3)
ON CONFLICT (id) DO UPDATE SET name = excluded.name, notification_criticality_id = excluded.notification_criticality_id;
