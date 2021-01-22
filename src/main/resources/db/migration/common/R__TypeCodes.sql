INSERT INTO accession_state (id, name, active)
VALUES (10, 'Pending', TRUE),
       (20, 'Processing', TRUE),
       (30, 'Processed', TRUE),
       (40, 'Drying', TRUE),
       (50, 'Dried', TRUE),
       (60, 'In Storage', TRUE),
       (70, 'Withdrawn', FALSE)
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO device_type (id, name)
VALUES (1, 'Generic Sensor')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO germination_test_type (id, name)
VALUES (1, 'Lab'),
       (2, 'Nursery')
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

INSERT INTO site_module_type (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Desalination'),
       (3, 'Reverse Osmosis')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO storage_condition (id, name)
VALUES (1, 'Refrigerator'),
       (2, 'Freezer')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO timeseries_type (id, name)
VALUES (1, 'Numeric'),
       (2, 'Text')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;
