package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

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
