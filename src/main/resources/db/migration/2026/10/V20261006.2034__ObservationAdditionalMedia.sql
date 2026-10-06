-- Try to identify media files that were uploaded as part of an observation and mark them as
-- is_original=true. There's no surefire way to distinguish additional observation-time files
-- from ones uploaded after the fact, but we use the file creation times as an approximation:
-- any files uploaded within 30 minutes of the plot being marked as complete will be treated
-- as original.

UPDATE tracking.observation_media_files omf
SET is_original = TRUE
FROM tracking.observation_plots op
JOIN files f ON TRUE
WHERE omf.observation_id = op.observation_id
AND omf.monitoring_plot_id = op.monitoring_plot_id
AND omf.is_original = FALSE
AND omf.file_id = f.id
AND f.created_time <= (op.completed_time + INTERVAL '30 minutes');
