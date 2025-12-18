package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.util.equalsOrBothNull
import java.math.BigDecimal
import java.time.Instant
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

data class PlantingSiteHistoryModel(
    val areaHa: BigDecimal?,
    val boundary: MultiPolygon,
    val createdTime: Instant,
    val exclusion: MultiPolygon? = null,
    val gridOrigin: Point?,
    val id: PlantingSiteHistoryId,
    val plantingSiteId: PlantingSiteId,
    val strata: List<StratumHistoryModel>,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is PlantingSiteHistoryModel &&
        id == other.id &&
        plantingSiteId == other.plantingSiteId &&
        areaHa == other.areaHa &&
        createdTime == other.createdTime &&
        boundary.equalsExact(other.boundary, tolerance) &&
        exclusion.equalsOrBothNull(other.exclusion, tolerance) &&
        gridOrigin.equalsOrBothNull(other.gridOrigin, tolerance) &&
        strata.size == other.strata.size &&
        strata.zip(other.strata).all { it.first.equals(it.second, tolerance) }
  }
}

data class StratumHistoryModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: StratumHistoryId,
    val name: String,
    val substrata: List<SubstratumHistoryModel>,
    /** ID of stratum if it currently exists. Null if the stratum has been deleted. */
    val stratumId: StratumId?,
    val stableId: StableId,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is StratumHistoryModel &&
        id == other.id &&
        name == other.name &&
        stratumId == other.stratumId &&
        stableId == other.stableId &&
        areaHa == other.areaHa &&
        boundary.equalsExact(other.boundary, tolerance) &&
        substrata.size == other.substrata.size &&
        substrata.zip(other.substrata).all { it.first.equals(it.second, tolerance) }
  }
}

data class SubstratumHistoryModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val fullName: String,
    val id: SubstratumHistoryId,
    val monitoringPlots: List<MonitoringPlotHistoryModel>,
    val name: String,
    /** ID of substratum if it currently exists. Null if the stratum has been deleted. */
    val substratumId: SubstratumId?,
    val stableId: StableId,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is SubstratumHistoryModel &&
        fullName == other.fullName &&
        id == other.id &&
        name == other.name &&
        substratumId == other.substratumId &&
        stableId == other.stableId &&
        areaHa == other.areaHa &&
        boundary.equalsExact(other.boundary, tolerance) &&
        monitoringPlots.size == other.monitoringPlots.size &&
        monitoringPlots.zip(other.monitoringPlots).all { it.first.equals(it.second, tolerance) }
  }
}

data class MonitoringPlotHistoryModel(
    /** Current monitoring plot boundary. Boundaries do not change over time. */
    val boundary: Polygon,
    val createdBy: UserId,
    val createdTime: Instant,
    val id: MonitoringPlotHistoryId,
    val monitoringPlotId: MonitoringPlotId,
    val sizeMeters: Int,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is MonitoringPlotHistoryModel &&
        createdBy == other.createdBy &&
        createdTime == other.createdTime &&
        id == other.id &&
        monitoringPlotId == other.monitoringPlotId &&
        sizeMeters == other.sizeMeters &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
