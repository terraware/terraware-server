INSERT INTO organization_media_files (file_id, organization_id, caption)
    SELECT splats.file_id, sites.organization_id, obmf.caption
    FROM splats
    JOIN tracking.observation_media_files AS obmf
    ON obmf.file_id = splats.file_id
    JOIN tracking.observations as obs
    ON obmf.observation_id = obs.id
    JOIN tracking.planting_sites as sites
    ON obs.planting_site_id = sites.id;
