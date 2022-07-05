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

INSERT INTO device_template_categories (id, name)
VALUES (1, 'PV'),
       (2, 'Seed Bank Default')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO facility_types (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Desalination'),
       (3, 'Reverse Osmosis')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO facility_connection_states (id, name)
VALUES (1, 'Not Connected'),
       (2, 'Connected'),
       (3, 'Configured')
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

INSERT INTO species_problem_fields (id, name)
VALUES (1, 'Scientific Name')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO species_problem_types (id, name)
VALUES (1, 'Name Misspelled'),
       (2, 'Name Not Found'),
       (3, 'Name Is Synonym')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO storage_conditions (id, name)
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
VALUES (1, 'Species CSV', TRUE)
ON CONFLICT (id) DO UPDATE SET name         = excluded.name,
                               expire_files = excluded.expire_files;

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
       (4, 'Success')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO notification_types (id, name, notification_criticality_id)
VALUES (1, 'User Added to Organization', 1),
       (2, 'Facility Idle', 2),
       (3, 'Facility Alert Requested', 3),
       (4, 'User Added to Project', 1),
       (5, 'Accession Scheduled for Drying', 1),
       (6, 'Accession Scheduled to End Drying', 1),
       (7, 'Accession Scheduled for Withdrawal', 1),
       (8, 'Accession Scheduled for Germination Test', 1),
       (9, 'Accessions Awaiting Processing', 1),
       (10, 'Accessions Ready for Testing', 1),
       (11, 'Accessions Finished Drying', 1),
       (12, 'Sensor Out Of Bounds', 3),
       (13, 'Unknown Automation Triggered', 3),
       (14, 'Device Unresponsive', 3)
ON CONFLICT (id) DO UPDATE SET name                        = excluded.name,
                               notification_criticality_id = excluded.notification_criticality_id;
