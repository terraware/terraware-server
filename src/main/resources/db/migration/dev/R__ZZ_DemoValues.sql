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
