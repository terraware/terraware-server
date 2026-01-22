ALTER TABLE mux_asset_statuses RENAME TO asset_statuses;
ALTER TABLE mux_assets RENAME COLUMN mux_asset_status_id TO asset_status_id;
