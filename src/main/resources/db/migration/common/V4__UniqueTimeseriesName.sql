ALTER TABLE timeseries ADD CONSTRAINT timeseries_unique_name_per_device UNIQUE (device_id, name);
