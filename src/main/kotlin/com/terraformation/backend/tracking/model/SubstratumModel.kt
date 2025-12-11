package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.differenceNullable
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.time.Instant
import org.locationtech.jts.geom.MultiPolygon

data class SubstratumModel<SSID : SubstratumId?>(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: SSID,
    val fullName: String,
    /** The time of the latest observation, if the substratum has completed observations */
    val latestObservationCompletedTime: Instant? = null,
    /** The ID of the latest observation, if the substratum has completed observations */
    val latestObservationId: ObservationId? = null,
    val monitoringPlots: List<MonitoringPlotModel> = emptyList(),
    val name: String,
    val observedTime: Instant? = null,
    val plantingCompletedTime: Instant? = null,
    val stableId: StableId,
) {
  fun findMonitoringPlot(monitoringPlotId: MonitoringPlotId): MonitoringPlotModel? =
      monitoringPlots.find { it.id == monitoringPlotId }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is SubstratumModel<*> &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        observedTime == other.observedTime &&
        plantingCompletedTime == other.plantingCompletedTime &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        monitoringPlots.zip(other.monitoringPlots).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }

  fun toNew(): NewSubstratumModel =
      NewSubstratumModel(
          areaHa = areaHa,
          boundary = boundary,
          id = null,
          fullName = fullName,
          monitoringPlots = monitoringPlots,
          name = name,
          observedTime = null,
          plantingCompletedTime = plantingCompletedTime,
          stableId = stableId,
      )

  companion object {
    fun create(
        boundary: MultiPolygon,
        fullName: String,
        name: String,
        exclusion: MultiPolygon? = null,
        plantingCompletedTime: Instant? = null,
        monitoringPlots: List<MonitoringPlotModel> = emptyList(),
        stableId: StableId = StableId(fullName),
    ): NewSubstratumModel {
      val areaHa: BigDecimal = boundary.differenceNullable(exclusion).calculateAreaHectares()

      return NewSubstratumModel(
          areaHa = areaHa,
          boundary = boundary,
          fullName = fullName,
          id = null,
          monitoringPlots = monitoringPlots,
          name = name,
          plantingCompletedTime = plantingCompletedTime,
          stableId = stableId,
      )
    }
  }
}

typealias AnySubstratumModel = SubstratumModel<out SubstratumId?>

typealias ExistingSubstratumModel = SubstratumModel<SubstratumId>

typealias NewSubstratumModel = SubstratumModel<Nothing?>
