-- Update "Completed" and "Abandoned" observations to have the latest completed plot time
UPDATE tracking.observations as observations
SET completed_time = (
    SELECT max(plots.completed_time)
    FROM tracking.observation_plots as plots
    WHERE plots.observation_id = observations.id
    AND plots.status_id = 3
    )
WHERE (observations.state_id = 3 OR observations.state_id = 5);
