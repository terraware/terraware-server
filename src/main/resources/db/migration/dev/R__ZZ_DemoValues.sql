-- Insert some dummy data into the database for demo purposes
INSERT INTO organization
VALUES (1, 'dev')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- This is an API key of "dummyKey"
INSERT INTO api_key (hash, key_part, organization_id, created_time)
VALUES ('f7b5ee554cbb700b597979ff26bbba58197e1427', 'du..ey', 1, NOW())
ON CONFLICT DO NOTHING;

-- A dummy site+device configuration matching the "dev" site in the terraware-sites repo
INSERT INTO site (id, organization_id, name, latitude, longitude, locale, timezone)
VALUES (10, 1, 'sim', 123.456789, -98.76543, 'en-US', 'US/Pacific')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO site_module (id, site_id, type_id, name)
VALUES (100, 10, 1, 'ohana'),
       (101, 10, 1, 'garage')
ON CONFLICT (id) DO UPDATE SET site_id = excluded.site_id,
                               name    = excluded.name;

INSERT INTO device (id, site_module_id, device_type_id, name)
VALUES (1000, 100, 1, 'generator-relay'),
       (1001, 100, 1, 'BMU-L'),
       (1002, 100, 1, 'BMU-R'),
       (1003, 101, 1, 'RO')
ON CONFLICT (id) DO UPDATE SET device_type_id = excluded.device_type_id,
                               name           = excluded.name;

INSERT INTO timeseries (id, type_id, device_id, name, units, decimal_places)
VALUES (10000, 1, 1000, 'relay-1', NULL, NULL)
ON CONFLICT (id) DO UPDATE SET type_id        = excluded.type_id,
                               device_id      = excluded.device_id,
                               name           = excluded.name,
                               units          = excluded.units,
                               decimal_places = excluded.decimal_places;

INSERT INTO species (id, name, created_time, modified_time)
VALUES (10000, 'Kousa Dogwood', NOW(), NOW()),
       (10001, 'Other Dogwood', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO species_family (id, name, created_time)
VALUES (20000, 'Dogwood', NOW())
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

INSERT INTO storage_location (id, site_module_id, name, condition_id)
VALUES (1000, 100, 'Refrigerator 1', 1),
       (1001, 100, 'Freezer 1', 2),
       (1002, 100, 'Freezer 2', 2)
ON CONFLICT (id) DO UPDATE SET name         = excluded.name,
                               condition_id = excluded.condition_id;

INSERT INTO accession (id, number, state_id, site_module_id, created_time, species_id,
                       species_family_id, collection_trees)
VALUES (1000, 'XYZ', 30, 100, '2021-01-03T15:31:20Z', 10000, 20000, 1),
       (1001, 'ABCDEFG', 20, 100, '2021-01-10T13:08:11Z', 10001, 20000, 2)
ON CONFLICT (id) DO UPDATE SET number           = excluded.number,
                               state_id         = excluded.state_id,
                               species_id       = excluded.species_id,
                               collection_trees = excluded.collection_trees;

INSERT INTO notification (id, site_id, type_id, accession_id, created_time, read, message,
                          accession_state_id)
VALUES (100000, 10, 1, NULL, '2021-01-01T11:22:33Z', FALSE, 'This is an example alert', NULL),
       (100001, 10, 3, 1000, '2021-01-15T08:01:00Z', FALSE, 'Accession XYZ has exploded!', NULL),
       (100002, 10, 2, NULL, '2021-01-25T08:05:00Z', FALSE, 'Accessions are pending!', 10),
       (100003, 10, 2, NULL, '2021-01-25T08:05:01Z', FALSE, 'Accessions are processing!', 20),
       (100004, 10, 2, NULL, '2021-01-25T08:05:02Z', FALSE, 'Accessions are processed!', 30),
       (100005, 10, 2, NULL, '2021-01-25T08:05:03Z', FALSE, 'Accessions are drying!', 40),
       (100006, 10, 2, NULL, '2021-01-25T08:05:04Z', FALSE, 'Accessions are dried!', 50),
       (100007, 10, 2, NULL, '2021-01-25T08:05:05Z', FALSE, 'Accessions are in storage!', 60),
       (100008, 10, 2, NULL, '2021-01-25T08:05:06Z', FALSE, 'Accessions are withdrawn!', 70),
       (100009, 10, 3, 1001, '2021-01-27T08:00:00Z', FALSE, 'Accession ABCDEFG needs help!', NULL)
ON CONFLICT (id) DO UPDATE SET type_id            = excluded.type_id,
                               accession_id       = excluded.accession_id,
                               created_time       = excluded.created_time,
                               read               = excluded.read,
                               message            = excluded.message,
                               accession_state_id = excluded.accession_state_id;
