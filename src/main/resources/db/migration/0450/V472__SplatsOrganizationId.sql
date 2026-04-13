ALTER TABLE splats ADD COLUMN organization_id BIGINT REFERENCES organizations;

CREATE INDEX ON splats (organization_id);

DELETE FROM splats s
WHERE NOT EXISTS (
    SELECT 1
    FROM observation_media_files omf
    WHERE omf.file_id = s.file_id
);

UPDATE splats s
SET organization_id = (
    SELECT mp.organization_id
    FROM tracking.observation_media_files omf
    JOIN tracking.monitoring_plots mp ON omf.monitoring_plot_id = mp.id
    WHERE omf.file_id = s.file_id
);

ALTER TABLE splats ALTER COLUMN organization_id SET NOT NULL;
