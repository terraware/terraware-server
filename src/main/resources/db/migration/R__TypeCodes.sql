INSERT INTO seedbank.accession_quantity_history_types (id, name)
VALUES (1, 'Observed'),
       (2, 'Computed')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.accession_states (id, name, active)
VALUES (5, 'Awaiting Check-In', TRUE),
       (10, 'Pending', TRUE),
       (15, 'Awaiting Processing', TRUE),
       (20, 'Processing', TRUE),
       (30, 'Processed', TRUE),
       (40, 'Drying', TRUE),
       (50, 'Dried', TRUE),
       (60, 'In Storage', TRUE),
       (70, 'Withdrawn', FALSE),
       (75, 'Used Up', FALSE),
       (80, 'Nursery', FALSE)
ON CONFLICT (id) DO UPDATE SET name   = excluded.name,
                               active = excluded.active;

INSERT INTO nursery.batch_quantity_history_types (id, name)
VALUES (1, 'Observed'),
       (2, 'Computed')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.collection_sources (id, name)
VALUES (1, 'Wild'),
       (2, 'Reintroduced'),
       (3, 'Cultivated'),
       (4, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.data_sources (id, name)
VALUES (1, 'Web'),
       (2, 'Seed Collector App'),
       (3, 'File Import')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO device_template_categories (id, name)
VALUES (1, 'PV'),
       (2, 'Seed Bank Default')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO facility_connection_states (id, name)
VALUES (1, 'Not Connected'),
       (2, 'Connected'),
       (3, 'Configured')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO facility_types (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Desalination'),
       (3, 'Reverse Osmosis'),
       (4, 'Nursery')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO growth_forms (id, name)
VALUES (1, 'Tree'),
       (2, 'Shrub'),
       (3, 'Forb'),
       (4, 'Graminoid'),
       (5, 'Fern')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO notification_criticalities (id, name)
VALUES (1, 'Info'),
       (2, 'Warning'),
       (3, 'Error'),
       (4, 'Success')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO notification_types (id, name, notification_criticality_id)
VALUES (1, 'User Added to Organization', 1),
       (2, 'Facility Idle', 2),
       (3, 'Facility Alert Requested', 3),
       (6, 'Accession Scheduled to End Drying', 1),
       (12, 'Sensor Out Of Bounds', 3),
       (13, 'Unknown Automation Triggered', 3),
       (14, 'Device Unresponsive', 3)
ON CONFLICT (id) DO UPDATE SET name                        = excluded.name,
                               notification_criticality_id = excluded.notification_criticality_id;

INSERT INTO seedbank.processing_methods (id, name)
VALUES (1, 'Count'),
       (2, 'Weight')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO roles (id, name)
VALUES (1, 'Contributor'),
       (2, 'Manager'),
       (3, 'Admin'),
       (4, 'Owner')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.seed_quantity_units (id, name)
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

INSERT INTO seedbank.source_plant_origins (id, name)
VALUES (1, 'Wild'),
       (2, 'Outplant')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO species_problem_fields (id, name)
VALUES (1, 'Scientific Name')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO species_problem_types (id, name)
VALUES (1, 'Name Misspelled'),
       (2, 'Name Not Found'),
       (3, 'Name Is Synonym')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.storage_conditions (id, name)
VALUES (1, 'Refrigerator'),
       (2, 'Freezer')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO timeseries_types (id, name)
VALUES (1, 'Numeric'),
       (2, 'Text')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO upload_problem_types (id, name)
VALUES (1, 'Unrecognized Value'),
       (2, 'Missing Required Value'),
       (3, 'Duplicate Value'),
       (4, 'Malformed Value')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- These are not in time order; add new intermediate statuses to the end rather than renumbering
-- existing ones.
INSERT INTO upload_statuses (id, name, finished)
VALUES (1, 'Receiving', FALSE),
       (2, 'Validating', FALSE),
       (3, 'Processing', FALSE),
       (4, 'Completed', TRUE),
       (5, 'Processing Failed', TRUE),
       (6, 'Invalid', TRUE),
       (7, 'Receiving Failed', TRUE),
       (8, 'Awaiting Validation', FALSE),
       (9, 'Awaiting User Action', FALSE),
       (10, 'Awaiting Processing', FALSE)
ON CONFLICT (id) DO UPDATE SET name     = excluded.name,
                               finished = excluded.finished;

INSERT INTO upload_types (id, name, expire_files)
VALUES (1, 'Species CSV', TRUE),
       (2, 'Accession CSV', TRUE),
       (3, 'Seedling Batch CSV', TRUE)
ON CONFLICT (id) DO UPDATE SET name         = excluded.name,
                               expire_files = excluded.expire_files;

INSERT INTO user_types (id, name)
VALUES (1, 'Individual'),
       (2, 'Super Admin'),
       (3, 'Device Manager'),
       (4, 'System')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.viability_test_seed_types (id, name)
VALUES (1, 'Fresh'),
       (2, 'Stored')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.viability_test_substrates (id, name)
VALUES (1, 'Nursery Media'),
       (2, 'Agar'),
       (3, 'Paper'),
       (4, 'Other'),
       (5, 'Sand'),
       (6, 'Media Mix'),
       (7, 'Soil'),
       (8, 'Moss'),
       (9, 'Perlite/Vermiculite')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.viability_test_treatments (id, name)
VALUES (1, 'Soak'),
       (2, 'Scarify'),
       (3, 'Chemical'),
       (4, 'Stratification'),
       (5, 'Other'),
       (6, 'Light')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.viability_test_types (id, name)
VALUES (1, 'Lab'),
       (2, 'Nursery'),
       (3, 'Cut')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO nursery.withdrawal_purposes (id, name)
VALUES (1, 'Nursery Transfer'), -- ID 1 is used in a check constraint; don't change it
       (2, 'Dead'),
       (3, 'Out Plant'),
       (4, 'Other')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO seedbank.withdrawal_purposes (id, name)
VALUES (1, 'Propagation'),
       (2, 'Outreach or Education'),
       (3, 'Research'),
       (4, 'Broadcast'),
       (5, 'Share with Another Site'),
       (6, 'Other'),
       (7, 'Viability Testing'),
       (8, 'Out-planting'),
       (9, 'Nursery')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- When adding new tables, put them in alphabetical (ASCII) order.
