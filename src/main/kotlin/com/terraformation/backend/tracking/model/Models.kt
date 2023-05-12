package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.time.Month
import java.time.ZoneId
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon

data class MonitoringPlotModel(
    val boundary: Polygon,
    val id: MonitoringPlotId,
    val fullName: String,
    val name: String,
    val permanentCluster: Int? = null,
    val permanentClusterSubplot: Int? = null,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is MonitoringPlotModel &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        permanentCluster == other.permanentCluster &&
        permanentClusterSubplot == other.permanentClusterSubplot &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingSubzoneModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val id: PlantingSubzoneId,
    val fullName: String,
    val name: String,
    val monitoringPlots: List<MonitoringPlotModel>,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSubzoneModel &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        monitoringPlots.zip(other.monitoringPlots).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingZoneModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val errorMargin: BigDecimal,
    val id: PlantingZoneId,
    val name: String,
    val numPermanentClusters: Int,
    val numTemporaryPlots: Int,
    val plantingSubzones: List<PlantingSubzoneModel>,
    val studentsT: BigDecimal,
    val variance: BigDecimal,
) {
  /**
   * Chooses a set of plots to act as temporary monitoring plots. The number of plots is determined
   * by [numTemporaryPlots].
   *
   * This follows a few rules:
   * - Plots that are already selected as permanent plots aren't eligible.
   * - Plots must be spread across subzones as evenly as possible: the number of temporary plots
   *   can't vary by more than 1 between subzones.
   * - If plots can't be exactly evenly spread across subzones (that is, [numTemporaryPlots] is not
   *   a multiple of the number of subzones) the remaining ones must be placed in the subzones that
   *   have the fewest number of permanent plots. If multiple subzones have the same number of
   *   permanent plots (including 0 of them), temporary plots should be placed in subzones that have
   *   been planted before being "placed" in ones that haven't (but see the next point).
   * - Only subzones that have been planted should have temporary plots. However, they should be
   *   excluded only after all the preceding rules have been followed. That is, we want to choose
   *   plots based on the rules above, and then filter out plots in unplanted subzones, as opposed
   *   to only spreading plots across planted subzones. Otherwise, for a new project with a small
   *   number of planted subzones, we would end up piling the entire planting zone's worth of
   *   temporary plots into a handful of subzones.
   *
   * @throws IllegalArgumentException The number of temporary plots hasn't been configured or the
   *   planting zone has no subzones.
   * @throws PlantingSubzoneFullException There weren't enough eligible plots available in a subzone
   *   to choose the required number.
   */
  fun chooseTemporaryPlots(
      permanentPlotIds: Set<MonitoringPlotId>,
      plantedSubzoneIds: Set<PlantingSubzoneId>,
  ): Collection<MonitoringPlotModel> {
    if (plantingSubzones.isEmpty()) {
      throw IllegalArgumentException("No subzones found for planting zone $id (wrong fetch depth?)")
    }

    // We will assign as many plots as possible evenly across all subzones.
    val numEvenlySpreadPlotsPerSubzone = numTemporaryPlots / plantingSubzones.size
    val numExcessPlots = numTemporaryPlots.rem(plantingSubzones.size)

    // Any plots that can't be spread evenly will be placed in the subzones with the smallest
    // number of permanent plots, with priority given to subzones that have been planted.
    //
    // If we sort the subzones by permanent plot count (always a multiple of 4) plus 1 if the
    // subzone has no plants, this means we can assign one extra plot each to the first N subzones
    // on that sorted list where N is the number of excess plots.
    return plantingSubzones
        .sortedBy { subzone ->
          val numPermanentPlots = subzone.monitoringPlots.count { it.id in permanentPlotIds }
          if (subzone.id in plantedSubzoneIds) numPermanentPlots else numPermanentPlots + 1
        }
        .flatMapIndexed { index, subzone ->
          if (subzone.id in plantedSubzoneIds) {
            val numPlots =
                if (index < numExcessPlots) {
                  numEvenlySpreadPlotsPerSubzone + 1
                } else {
                  numEvenlySpreadPlotsPerSubzone
                }

            val remainingPlots = subzone.monitoringPlots.filter { it.id !in permanentPlotIds }
            if (remainingPlots.size < numPlots) {
              throw PlantingSubzoneFullException(subzone.id, numPlots, remainingPlots.size)
            }

            remainingPlots.shuffled().take(numPlots)
          } else {
            // This subzone has no plants, so it gets no temporary plots.
            emptyList()
          }
        }
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingZoneModel &&
        id == other.id &&
        name == other.name &&
        numPermanentClusters == other.numPermanentClusters &&
        numTemporaryPlots == other.numTemporaryPlots &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        errorMargin.equalsIgnoreScale(other.errorMargin) &&
        studentsT.equalsIgnoreScale(other.studentsT) &&
        variance.equalsIgnoreScale(other.variance) &&
        plantingSubzones.zip(other.plantingSubzones).all { (a, b) -> a.equals(b, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingSiteModel(
    val areaHa: BigDecimal? = null,
    val boundary: MultiPolygon?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasonEndMonth: Month? = null,
    val plantingSeasonStartMonth: Month? = null,
    val plantingZones: List<PlantingZoneModel>,
    val timeZone: ZoneId? = null,
) {
  constructor(
      record: Record,
      plantingZonesMultiset: Field<List<PlantingZoneModel>>? = null
  ) : this(
      areaHa = record[PLANTING_SITES.AREA_HA],
      boundary = record[PLANTING_SITES.BOUNDARY] as? MultiPolygon,
      description = record[PLANTING_SITES.DESCRIPTION],
      id = record[PLANTING_SITES.ID]!!,
      name = record[PLANTING_SITES.NAME]!!,
      organizationId = record[PLANTING_SITES.ORGANIZATION_ID]!!,
      plantingSeasonEndMonth = record[PLANTING_SITES.PLANTING_SEASON_END_MONTH],
      plantingSeasonStartMonth = record[PLANTING_SITES.PLANTING_SEASON_START_MONTH],
      plantingZones = plantingZonesMultiset?.let { record[it] } ?: emptyList(),
      timeZone = record[PLANTING_SITES.TIME_ZONE],
  )

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSiteModel &&
        description == other.description &&
        id == other.id &&
        name == other.name &&
        timeZone == other.timeZone &&
        plantingSeasonEndMonth == other.plantingSeasonEndMonth &&
        plantingSeasonStartMonth == other.plantingSeasonStartMonth &&
        plantingZones.size == other.plantingZones.size &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) } &&
        (boundary == null && other.boundary == null ||
            boundary != null &&
                other.boundary != null &&
                boundary.equalsExact(other.boundary, tolerance))
  }
}

data class PlantingModel(
    val id: PlantingId,
    val notes: String? = null,
    val numPlants: Int,
    val plantingSubzoneId: PlantingSubzoneId? = null,
    val speciesId: SpeciesId,
    val type: PlantingType,
) {
  constructor(
      record: Record
  ) : this(
      record[PLANTINGS.ID]!!,
      record[PLANTINGS.NOTES],
      record[PLANTINGS.NUM_PLANTS]!!,
      record[PLANTINGS.PLANTING_SUBZONE_ID],
      record[PLANTINGS.SPECIES_ID]!!,
      record[PLANTINGS.PLANTING_TYPE_ID]!!,
  )
}

data class DeliveryModel(
    val id: DeliveryId,
    val plantings: List<PlantingModel>,
    val plantingSiteId: PlantingSiteId,
    val withdrawalId: WithdrawalId,
) {
  constructor(
      record: Record,
      plantingsMultisetField: Field<List<PlantingModel>>
  ) : this(
      record[DELIVERIES.ID]!!,
      record[plantingsMultisetField],
      record[DELIVERIES.PLANTING_SITE_ID]!!,
      record[DELIVERIES.WITHDRAWAL_ID]!!,
  )
}

enum class PlantingSiteDepth {
  Site,
  Zone,
  Subzone,
  Plot
}

/** Number of square meters in a hectare. */
const val SQUARE_METERS_PER_HECTARE: Double = 10000.0
