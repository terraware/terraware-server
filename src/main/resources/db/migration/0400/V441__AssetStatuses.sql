ALTER TABLE mux_asset_statuses RENAME TO asset_statuses;
ALTER TABLE asset_statuses RENAME CONSTRAINT mux_asset_statuses_pkey TO asset_statuses_pkey;
ALTER TABLE asset_statuses RENAME CONSTRAINT mux_asset_statuses_name_key TO asset_statuses_name_key;

ALTER TABLE mux_assets RENAME COLUMN mux_asset_status_id TO asset_status_id;
