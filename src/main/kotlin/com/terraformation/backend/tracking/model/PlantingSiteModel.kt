package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point

data class PlantingSiteModel<
    PSID : PlantingSiteId?,
    PZID : PlantingZoneId?,
    PSZID : PlantingSubzoneId?,
>(
    val areaHa: BigDecimal? = null,
    val boundary: MultiPolygon?,
    val description: String? = null,
    val exclusion: MultiPolygon? = null,
    val gridOrigin: Point? = null,
    val id: PSID,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasons: List<ExistingPlantingSeasonModel> = emptyList(),
    val plantingZones: List<PlantingZoneModel<PZID, PSZID>> = emptyList(),
    val projectId: ProjectId? = null,
    val timeZone: ZoneId? = null,
) {
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
  fun findZoneWithMonitoringPlot(
      monitoringPlotId: MonitoringPlotId
  ): PlantingZoneModel<PZID, PSZID>? {
    return plantingZones.firstOrNull { zone ->
      zone.findSubzoneWithMonitoringPlot(monitoringPlotId) != null
    }
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSiteModel<*, *, *> &&
        description == other.description &&
        id == other.id &&
        name == other.name &&
        timeZone == other.timeZone &&
        plantingZones.size == other.plantingZones.size &&
        projectId == other.projectId &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsOrBothNull(other.boundary) &&
        exclusion.equalsOrBothNull(other.exclusion) &&
        gridOrigin.equalsOrBothNull(other.gridOrigin)
  }

  companion object {
    fun of(
        record: Record,
        plantingSeasonsMultiset: Field<List<ExistingPlantingSeasonModel>>?,
        plantingZonesMultiset: Field<List<ExistingPlantingZoneModel>>? = null
    ) =
        ExistingPlantingSiteModel(
            areaHa = record[PLANTING_SITES.AREA_HA],
            boundary = record[PLANTING_SITES.BOUNDARY] as? MultiPolygon,
            description = record[PLANTING_SITES.DESCRIPTION],
            exclusion = record[PLANTING_SITES.EXCLUSION] as? MultiPolygon,
            gridOrigin = record[PLANTING_SITES.GRID_ORIGIN] as? Point,
            id = record[PLANTING_SITES.ID]!!,
            name = record[PLANTING_SITES.NAME]!!,
            organizationId = record[PLANTING_SITES.ORGANIZATION_ID]!!,
            plantingSeasons = plantingSeasonsMultiset?.let { record[it] } ?: emptyList(),
            plantingZones = plantingZonesMultiset?.let { record[it] } ?: emptyList(),
            projectId = record[PLANTING_SITES.PROJECT_ID],
            timeZone = record[PLANTING_SITES.TIME_ZONE],
        )

    fun create(
        areaHa: BigDecimal? = null,
        boundary: MultiPolygon? = null,
        description: String? = null,
        exclusion: MultiPolygon? = null,
        gridOrigin: Point? = null,
        name: String,
        organizationId: OrganizationId,
        plantingSeasons: List<ExistingPlantingSeasonModel> = emptyList(),
        plantingZones: List<NewPlantingZoneModel> = emptyList(),
        projectId: ProjectId? = null,
        timeZone: ZoneId? = null,
    ) =
        NewPlantingSiteModel(
            areaHa = areaHa,
            boundary = boundary,
            description = description,
            exclusion = exclusion,
            gridOrigin = gridOrigin,
            id = null,
            name = name,
            organizationId = organizationId,
            plantingSeasons = plantingSeasons,
            plantingZones = plantingZones,
            projectId = projectId,
            timeZone = timeZone,
        )
  }
}

typealias ExistingPlantingSiteModel =
    PlantingSiteModel<PlantingSiteId, PlantingZoneId, PlantingSubzoneId>

typealias NewPlantingSiteModel = PlantingSiteModel<Nothing?, Nothing?, Nothing?>
