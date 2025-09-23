package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.coveragePercent
import com.terraformation.backend.util.differenceNullable
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import com.terraformation.backend.util.nearlyCoveredBy
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point

data class PlantingSiteModel<
    PSID : PlantingSiteId?,
    PZID : PlantingZoneId?,
    PSZID : PlantingSubzoneId?,
    TIMESTAMP : Instant?,
>(
    val adHocPlots: List<MonitoringPlotModel> = emptyList(),
    val areaHa: BigDecimal? = null,
    val boundary: MultiPolygon?,
    val countryCode: String? = null,
    val description: String? = null,
    val exclusion: MultiPolygon? = null,
    val exteriorPlots: List<MonitoringPlotModel> = emptyList(),
    val gridOrigin: Point? = null,
    /**
     * If this model is associated with a particular history entry, its ID. This property is not
     * populated when doing a simple fetch of the current site data, nor for planting sites without
     * maps.
     */
    val historyId: PlantingSiteHistoryId? = null,
    val id: PSID,
    /** The time of the latest observation, if the planting site has completed observations */
    val latestObservationCompletedTime: Instant? = null,
    /** The ID of the latest observation, if the planting site has completed observations */
    val latestObservationId: ObservationId? = null,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasons: List<ExistingPlantingSeasonModel> = emptyList(),
    val plantingZones: List<PlantingZoneModel<PZID, PSZID, TIMESTAMP>> = emptyList(),
    val projectId: ProjectId? = null,
    val timeZone: ZoneId? = null,
    val survivalRateIncludesTempPlots: Boolean = false,
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
  ): PlantingZoneModel<PZID, PSZID, TIMESTAMP>? {
    return plantingZones.firstOrNull { zone ->
      zone.findSubzoneWithMonitoringPlot(monitoringPlotId) != null
    }
  }

  /**
   * Checks that the planting site is valid.
   *
   * @return A list of validation problems, or null if the site is valid.
   */
  fun validate(): List<PlantingSiteValidationFailure>? {
    val problems = mutableListOf<PlantingSiteValidationFailure>()

    if (boundary != null) {
      val envelopeAreaHa = boundary.envelope.calculateAreaHectares()
      if (envelopeAreaHa > MAX_SITE_ENVELOPE_AREA_HA) {
        problems.add(PlantingSiteValidationFailure.siteTooLarge())
      }

      plantingZones
          .groupBy { it.name.lowercase() }
          .values
          .filter { it.size > 1 }
          .forEach { problems.add(PlantingSiteValidationFailure.duplicateZoneName(it[0].name)) }

      plantingZones.forEachIndexed { index, zone ->
        if (!zone.boundary.nearlyCoveredBy(boundary)) {
          problems.add(PlantingSiteValidationFailure.zoneNotInSite(zone.name))
        }

        plantingZones.drop(index + 1).forEach { otherZone ->
          val overlapPercent = zone.boundary.coveragePercent(otherZone.boundary)
          if (overlapPercent > REGION_OVERLAP_MAX_PERCENT) {
            problems.add(
                PlantingSiteValidationFailure.zoneBoundaryOverlaps(setOf(otherZone.name), zone.name)
            )
          }
        }

        problems.addAll(zone.validate(this))
      }
    } else {
      if (exclusion != null) {
        problems.add(PlantingSiteValidationFailure.exclusionWithoutBoundary())
      }
      if (plantingZones.isNotEmpty()) {
        problems.add(PlantingSiteValidationFailure.zonesWithoutSiteBoundary())
      }
    }

    return problems.ifEmpty { null }
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is AnyPlantingSiteModel &&
        countryCode == other.countryCode &&
        description == other.description &&
        id == other.id &&
        name == other.name &&
        timeZone == other.timeZone &&
        projectId == other.projectId &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        boundary.equalsOrBothNull(other.boundary) &&
        exclusion.equalsOrBothNull(other.exclusion) &&
        gridOrigin.equalsOrBothNull(other.gridOrigin) &&
        exteriorPlots.size == other.exteriorPlots.size &&
        exteriorPlots.zip(other.exteriorPlots).all { it.first.equals(it.second, tolerance) } &&
        plantingSeasons.size == other.plantingSeasons.size &&
        plantingSeasons.zip(other.plantingSeasons).all { it.first == it.second } &&
        plantingZones.size == other.plantingZones.size &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) }
  }

  fun toNew(): NewPlantingSiteModel =
      NewPlantingSiteModel(
          areaHa = areaHa,
          boundary = boundary,
          countryCode = countryCode,
          description = description,
          exclusion = exclusion,
          gridOrigin = gridOrigin,
          id = null,
          name = name,
          organizationId = organizationId,
          plantingSeasons = plantingSeasons,
          plantingZones = plantingZones.map { it.toNew() },
          projectId = projectId,
          timeZone = timeZone,
          survivalRateIncludesTempPlots = survivalRateIncludesTempPlots,
      )

  companion object {
    /**
     * Maximum percentage of a zone or subzone that can overlap with a neighboring one before
     * tripping the validation check for overlapping areas. This fuzz factor is needed to account
     * for floating-point inaccuracy.
     */
    const val REGION_OVERLAP_MAX_PERCENT = 0.01

    fun of(
        record: Record,
        plantingSeasonsMultiset: Field<List<ExistingPlantingSeasonModel>>?,
        plantingZonesMultiset: Field<List<ExistingPlantingZoneModel>>? = null,
        adHocPlotsField: Field<List<MonitoringPlotModel>>? = null,
        exteriorPlotsMultiset: Field<List<MonitoringPlotModel>>? = null,
        latestObservationIdField: Field<ObservationId?>? = null,
        latestObservationTimeField: Field<Instant?>? = null,
    ) =
        ExistingPlantingSiteModel(
            adHocPlots = adHocPlotsField?.let { record[it] } ?: emptyList(),
            areaHa = record[PLANTING_SITES.AREA_HA],
            boundary = record[PLANTING_SITES.BOUNDARY] as? MultiPolygon,
            countryCode = record[PLANTING_SITES.COUNTRY_CODE],
            description = record[PLANTING_SITES.DESCRIPTION],
            exclusion = record[PLANTING_SITES.EXCLUSION] as? MultiPolygon,
            exteriorPlots = exteriorPlotsMultiset?.let { record[it] } ?: emptyList(),
            gridOrigin = record[PLANTING_SITES.GRID_ORIGIN] as? Point,
            latestObservationCompletedTime = latestObservationTimeField?.let { record[it] },
            latestObservationId = latestObservationIdField?.let { record[it] },
            id = record[PLANTING_SITES.ID]!!,
            name = record[PLANTING_SITES.NAME]!!,
            organizationId = record[PLANTING_SITES.ORGANIZATION_ID]!!,
            plantingSeasons = plantingSeasonsMultiset?.let { record[it] } ?: emptyList(),
            plantingZones = plantingZonesMultiset?.let { record[it] } ?: emptyList(),
            projectId = record[PLANTING_SITES.PROJECT_ID],
            survivalRateIncludesTempPlots =
                record[PLANTING_SITES.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS]!!,
            timeZone = record[PLANTING_SITES.TIME_ZONE],
        )

    fun create(
        boundary: MultiPolygon? = null,
        description: String? = null,
        exclusion: MultiPolygon? = null,
        // The point that will be used as the origin for the grid of monitoring plots.
        gridOrigin: Point? = null,
        name: String,
        organizationId: OrganizationId,
        plantingSeasons: List<ExistingPlantingSeasonModel> = emptyList(),
        plantingZones: List<NewPlantingZoneModel> = emptyList(),
        projectId: ProjectId? = null,
        timeZone: ZoneId? = null,
    ): NewPlantingSiteModel {
      // If usable region is so small that its area rounds down to 0 hectares, treat the site as
      // having no area, so we don't try to calculate area-denominated statistics.
      val areaHa =
          boundary?.differenceNullable(exclusion)?.calculateAreaHectares()?.let { area ->
            if (area.signum() > 0) area else null
          }

      val gridOriginWithBoundaryCrs =
          if (boundary != null && gridOrigin != null) {
            val crs = CRS.decode("EPSG:${gridOrigin.srid}", true)
            val boundaryCrs = CRS.decode("EPSG:${boundary.srid}", true)
            if (crs == boundaryCrs) {
              gridOrigin
            } else {
              val transform = CRS.findMathTransform(crs, boundaryCrs)
              val transformedCoordinates = JTS.transform(gridOrigin.coordinate, null, transform)
              boundary.factory.createPoint(transformedCoordinates)
            }
          } else {
            // Use the southwest corner of the envelope (bounding box) of the site boundary by
            // default.
            boundary?.factory?.createPoint(boundary.envelope.coordinates[0])
          }

      return NewPlantingSiteModel(
          areaHa = areaHa,
          boundary = boundary,
          description = description,
          exclusion = exclusion,
          gridOrigin = gridOriginWithBoundaryCrs,
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
}

typealias AnyPlantingSiteModel =
    PlantingSiteModel<
        out PlantingSiteId?,
        out PlantingZoneId?,
        out PlantingSubzoneId?,
        out Instant?,
    >

typealias ExistingPlantingSiteModel =
    PlantingSiteModel<PlantingSiteId, PlantingZoneId, PlantingSubzoneId, Instant>

typealias NewPlantingSiteModel = PlantingSiteModel<Nothing?, Nothing?, Nothing?, Nothing?>
