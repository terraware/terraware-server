INSERT INTO site_module_type VALUES (1, 'Seed Bank') ON CONFLICT DO NOTHING;
INSERT INTO site_module_type VALUES (2, 'Desalination') ON CONFLICT DO NOTHING;
INSERT INTO site_module_type VALUES (3, 'Reverse Osmosis') ON CONFLICT DO NOTHING;

INSERT INTO timeseries_type VALUES (1, 'Numeric') ON CONFLICT DO NOTHING;
INSERT INTO timeseries_type VALUES (2, 'Text') ON CONFLICT DO NOTHING;
