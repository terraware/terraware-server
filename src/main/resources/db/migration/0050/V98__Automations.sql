-- Move common configuration settings to top-level columns.
ALTER TABLE automations RENAME COLUMN configuration TO settings;
ALTER TABLE automations ADD COLUMN type TEXT;
ALTER TABLE automations ADD COLUMN device_id BIGINT REFERENCES devices;
ALTER TABLE automations ADD COLUMN timeseries_name TEXT;
ALTER TABLE automations ADD COLUMN verbosity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE automations ADD COLUMN lower_threshold FLOAT;
ALTER TABLE automations ADD COLUMN upper_threshold FLOAT;

UPDATE automations
SET device_id       = (settings ->> 'monitorDeviceId')::BIGINT,
    lower_threshold = (settings ->> 'lowerThreshold')::FLOAT,
    timeseries_name = settings ->> 'monitorTimeseriesName',
    type            = settings ->> 'type',
    upper_threshold = (settings ->> 'upperThreshold')::FLOAT,
    verbosity       = COALESCE((settings ->> 'verbosity')::INTEGER, 0),
    settings        = settings
        - 'lowerThreshold'
        - 'monitorDeviceId'
        - 'monitorTimeseriesName'
        - 'type'
        - 'upperThreshold'
        - 'verbosity'
WHERE settings IS NOT NULL;

UPDATE automations
SET settings = NULL
WHERE settings = '{}';

ALTER TABLE automations ALTER COLUMN type SET NOT NULL;

CREATE INDEX ON automations (device_id, timeseries_name);
