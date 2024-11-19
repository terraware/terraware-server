-- Allow FK checking to be deferred so cascading delete of a planting site doesn't fail if
-- PostgreSQL deletes a parent history row then tries to apply an ON DELETE SET NULL on a child row
-- that will be deleted by the time the cascading is done, but currently still has another column
-- referencing the parent row that was just deleted. Without deferral, that update of the child row
-- would check the constraints on the entire row, including the now-invalid foreign key reference,
-- and the delete would fail.

ALTER TABLE tracking.planting_zone_histories
    ALTER CONSTRAINT planting_zone_histories_planting_site_history_id_fkey
        DEFERRABLE;

ALTER TABLE tracking.planting_subzone_histories
    ALTER CONSTRAINT planting_subzone_histories_planting_zone_history_id_fkey
        DEFERRABLE;

ALTER TABLE tracking.monitoring_plot_histories
    ALTER CONSTRAINT monitoring_plot_histories_planting_site_history_id_fkey
        DEFERRABLE;

ALTER TABLE tracking.monitoring_plot_histories
    ALTER CONSTRAINT monitoring_plot_histories_planting_subzone_history_id_fkey
        DEFERRABLE;
