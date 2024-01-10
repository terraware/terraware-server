package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.MultiPolygon

data class PlantingSiteModel(
    val areaHa: BigDecimal? = null,
    val boundary: MultiPolygon?,
    val description: String?,
    val exclusion: MultiPolygon? = null,
    val id: PlantingSiteId,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasons: List<ExistingPlantingSeasonModel> = emptyList(),
    val plantingZones: List<PlantingZoneModel>,
    val projectId: ProjectId? = null,
    val timeZone: ZoneId? = null,
) {
  constructor(
      record: Record,
      plantingSeasonsMultiset: Field<List<ExistingPlantingSeasonModel>>?,
      plantingZonesMultiset: Field<List<PlantingZoneModel>>? = null
  ) : this(
      areaHa = record[PLANTING_SITES.AREA_HA],
      boundary = record[PLANTING_SITES.BOUNDARY] as? MultiPolygon,
      description = record[PLANTING_SITES.DESCRIPTION],
      exclusion = record[PLANTING_SITES.EXCLUSION] as? MultiPolygon,
      id = record[PLANTING_SITES.ID]!!,
      name = record[PLANTING_SITES.NAME]!!,
      organizationId = record[PLANTING_SITES.ORGANIZATION_ID]!!,
      plantingSeasons = plantingSeasonsMultiset?.let { record[it] } ?: emptyList(),
      plantingZones = plantingZonesMultiset?.let { record[it] } ?: emptyList(),
      projectId = record[PLANTING_SITES.PROJECT_ID],
      timeZone = record[PLANTING_SITES.TIME_ZONE],
  )

  /**
   * Returns the start date of the next observation for this planting site, or null if the planting
   * season end date is not set.
   *
   * The next observation starts on the first of the month after the end of the planting season.
   */
  fun getNextObservationStart(clock: Clock): LocalDate? {
    val now = clock.instant()

    return plantingSeasons
        .firstOrNull { it.endTime >= now }
        ?.endDate
        ?.plusMonths(1)
        ?.withDayOfMonth(1)
  }

  /**
   * Returns the planting zone that contains a monitoring plot, or null if the plot wasn't found.
   */
  fun findZoneWithMonitoringPlot(monitoringPlotId: MonitoringPlotId): PlantingZoneModel? {
    return plantingZones.firstOrNull { zone ->
      zone.findSubzoneWithMonitoringPlot(monitoringPlotId) != null
    }
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSiteModel &&
        description == other.description &&
        id == other.id &&
        name == other.name &&
        timeZone == other.timeZone &&
        plantingZones.size == other.plantingZones.size &&
        projectId == other.projectId &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) } &&
        (boundary == null && other.boundary == null ||
            boundary != null &&
                other.boundary != null &&
                boundary.equalsExact(other.boundary, tolerance))
  }
}
