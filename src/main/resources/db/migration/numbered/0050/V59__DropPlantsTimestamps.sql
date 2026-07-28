-- Plant information is part of a feature's data; timestamps are tracked on the feature.
ALTER TABLE plants DROP COLUMN created_time;
ALTER TABLE plants DROP COLUMN modified_time;
