-- Insert some dummy data into the database for demo purposes
INSERT INTO organization VALUES (1, 'Terraformation');

-- This is an API key of "dummyKey"
INSERT INTO key VALUES ('f7b5ee554cbb700b597979ff26bbba58197e1427', 'du..ey', 1, now());

-- A dummy site
INSERT INTO site (organization_id, name, latitude, longitude, language, timezone)
VALUES (1, 'Demo Site', 123.456789, -98.76543, 'en-US', 'US/Pacific');
