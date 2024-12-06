package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneHistoryId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneHistoryId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.util.equalsOrBothNull
import java.time.Instant
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

data class PlantingSiteHistoryModel(
    val boundary: MultiPolygon,
    val exclusion: MultiPolygon? = null,
    val gridOrigin: Point,
    val id: PlantingSiteHistoryId,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<PlantingZoneHistoryModel>,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is PlantingSiteHistoryModel &&
        id == other.id &&
        plantingSiteId == other.plantingSiteId &&
        boundary.equalsExact(other.boundary, tolerance) &&
        exclusion.equalsOrBothNull(other.exclusion, tolerance) &&
        gridOrigin.equalsExact(other.gridOrigin, tolerance) &&
        plantingZones.size == other.plantingZones.size &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) }
  }
}

data class PlantingZoneHistoryModel(
    val boundary: MultiPolygon,
    val id: PlantingZoneHistoryId,
    val name: String,
    val plantingSubzones: List<PlantingSubzoneHistoryModel>,
    /** ID of planting zone if it currently exists. Null if the zone has been deleted. */
    val plantingZoneId: PlantingZoneId?,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is PlantingZoneHistoryModel &&
        id == other.id &&
        name == other.name &&
        plantingZoneId == other.plantingZoneId &&
        boundary.equalsExact(other.boundary, tolerance) &&
        plantingSubzones.size == other.plantingSubzones.size &&
        plantingSubzones.zip(other.plantingSubzones).all { it.first.equals(it.second, tolerance) }
  }
}

data class PlantingSubzoneHistoryModel(
    val boundary: MultiPolygon,
    val fullName: String,
    val id: PlantingSubzoneHistoryId,
    val monitoringPlots: List<MonitoringPlotHistoryModel>,
    val name: String,
    /** ID of planting subzone if it currently exists. Null if the zone has been deleted. */
    val plantingSubzoneId: PlantingSubzoneId?,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is PlantingSubzoneHistoryModel &&
        fullName == other.fullName &&
        id == other.id &&
        name == other.name &&
        plantingSubzoneId == other.plantingSubzoneId &&
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
    val fullName: String,
    val id: MonitoringPlotHistoryId,
    val name: String,
    val monitoringPlotId: MonitoringPlotId,
    val sizeMeters: Int,
) {
  fun equals(other: Any?, tolerance: Double = 0.00001): Boolean {
    return other is MonitoringPlotHistoryModel &&
        createdBy == other.createdBy &&
        createdTime == other.createdTime &&
        fullName == other.fullName &&
        id == other.id &&
        name == other.name &&
        monitoringPlotId == other.monitoringPlotId &&
        sizeMeters == other.sizeMeters &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
