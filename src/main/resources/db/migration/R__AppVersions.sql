-- Minimum versions of mobile apps are configured here. The statements here will only be run when
-- this file has changed since the last time terraware-server was launched; if it hasn't, any
-- local modifications to the version data won't be affected by a server restart.

DELETE FROM app_versions;

INSERT INTO app_versions (app_name, platform, minimum_version, recommended_version)
VALUES ('SeedCollector', 'Android', '0.0.1', '0.0.1'),
       ('SeedCollector', 'iOS',     '0.0.1', '0.0.1');
