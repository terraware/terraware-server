ALTER TABLE devices DROP COLUMN polling_interval;
ALTER TABLE devices ADD COLUMN verbosity INTEGER;

UPDATE devices
SET verbosity = (settings ->> 'diagnosticMode')::INTEGER,
    settings  = CASE
                    WHEN settings - 'diagnosticMode' = '{}' THEN NULL
                    ELSE settings - 'diagnosticMode' END
WHERE settings ? 'diagnosticMode';

ALTER TABLE device_templates DROP COLUMN polling_interval;
ALTER TABLE device_templates ADD COLUMN verbosity INTEGER;

UPDATE device_templates
SET verbosity = (settings ->> 'diagnosticMode')::INTEGER,
    settings  = settings - 'diagnosticMode'
WHERE settings ? 'diagnosticMode';
