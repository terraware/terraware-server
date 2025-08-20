package com.terraformation.backend.device

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES_VALUES
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.InstantSource
import java.time.temporal.ChronoUnit
import org.jobrunr.jobs.annotations.Recurring
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class TimeseriesPruner(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  /** Only delete this many old values at a time from each timeseries. */
  var maxRowsToDelete: Int = 10000

  private val log = perClassLogger()

  /**
   * Prunes data from the `timeseries_values` table based on per-timeseries retention settings. By
   * default, a timeseries isn't pruned at all; an admin needs to set its `retention_days` column to
   * the number of days of data to retain.
   *
   * To avoid slamming the database with a gigantic delete operation when a retention limit is first
   * configured on a timeseries with a lot of existing values, this will only delete up to
   * [maxRowsToDelete] rows per timeseries each time it runs. Since this job runs repeatedly, the
   * deletion will gradually catch up to the desired number of retention days.
   */
  @Recurring(id = "pruneTimeseriesValues", cron = "8,23,38,53 * * * *")
  fun pruneTimeseriesValues() {
    var totalRowsDeleted = 0
    val timeseriesIdsDeleted = mutableListOf<TimeseriesId>()

    log.debug("Scanning for timeseries to prune")

    try {
      dslContext
          .select(TIMESERIES.ID.asNonNullable(), TIMESERIES.RETENTION_DAYS.asNonNullable())
          .from(TIMESERIES)
          .where(TIMESERIES.RETENTION_DAYS.isNotNull)
          .orderBy(TIMESERIES.ID)
          .fetch()
          .forEach { (timeseriesId, retentionDays) ->
            val minimumCreatedTime = clock.instant().minus(retentionDays.toLong(), ChronoUnit.DAYS)
            val rowsDeleted =
                with(TIMESERIES_VALUES) {
                  dslContext
                      .deleteFrom(TIMESERIES_VALUES)
                      .where(TIMESERIES_ID.eq(timeseriesId))
                      .and(
                          CREATED_TIME.`in`(
                              DSL.select(CREATED_TIME)
                                  .from(TIMESERIES_VALUES)
                                  .where(TIMESERIES_ID.eq(timeseriesId))
                                  .and(CREATED_TIME.lessThan(minimumCreatedTime))
                                  .orderBy(CREATED_TIME)
                                  .limit(maxRowsToDelete)
                          )
                      )
                      .execute()
                }

            if (rowsDeleted > 0) {
              totalRowsDeleted += rowsDeleted
              timeseriesIdsDeleted.add(timeseriesId)
            }
          }
    } catch (e: Exception) {
      log.error("Error while pruning timeseries values", e)
    }

    if (totalRowsDeleted > 0) {
      log.info("Deleted $totalRowsDeleted values from timeseries $timeseriesIdsDeleted")
    }
  }
}
