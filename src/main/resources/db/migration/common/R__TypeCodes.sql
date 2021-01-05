-- TODO: Update with finalized state names
INSERT INTO accession_state (id, name)
VALUES (10, 'Dropped Off'),
       (20, 'Processing'),
       (30, 'All Processed'),
       (40, 'Drying'),
       (50, 'All Dried'),
       (60, 'In Storage'),
       (70, 'All Withdrawn')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO site_module_type (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Desalination'),
       (3, 'Reverse Osmosis')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO timeseries_type (id, name)
VALUES (1, 'Numeric'),
       (2, 'Text')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;
