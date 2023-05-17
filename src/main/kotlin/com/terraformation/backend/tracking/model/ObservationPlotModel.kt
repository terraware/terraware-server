package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import java.time.Instant
import org.jooq.Record

data class ObservationPlotModel(
    val claimedBy: UserId? = null,
    val claimedTime: Instant? = null,
    val completedBy: UserId? = null,
    val completedTime: Instant? = null,
    val isPermanent: Boolean,
    val monitoringPlotId: MonitoringPlotId,
    val notes: String? = null,
    val observedTime: Instant? = null,
    val observationId: ObservationId,
) {
  companion object {
    fun of(record: Record): ObservationPlotModel {
      return ObservationPlotModel(
          claimedBy = record[OBSERVATION_PLOTS.CLAIMED_BY],
          claimedTime = record[OBSERVATION_PLOTS.CLAIMED_TIME],
          completedBy = record[OBSERVATION_PLOTS.COMPLETED_BY],
          completedTime = record[OBSERVATION_PLOTS.COMPLETED_TIME],
          isPermanent = record[OBSERVATION_PLOTS.IS_PERMANENT]!!,
          monitoringPlotId = record[OBSERVATION_PLOTS.MONITORING_PLOT_ID]!!,
          notes = record[OBSERVATION_PLOTS.NOTES],
          observedTime = record[OBSERVATION_PLOTS.OBSERVED_TIME],
          observationId = record[OBSERVATION_PLOTS.OBSERVATION_ID]!!,
      )
    }
  }
}
