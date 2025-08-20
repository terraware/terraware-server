package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.AnyPlantingSubzoneModel
import com.terraformation.backend.tracking.model.AnyPlantingZoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSubzoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingZoneModel
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.differenceNullable
import com.terraformation.backend.util.nearlyCoveredBy
import com.terraformation.backend.util.toNormalizedMultiPolygon
import java.math.BigDecimal
import kotlin.math.min
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon

class PlantingSiteEditCalculator(
    private val existingSite: ExistingPlantingSiteModel,
    private val desiredSite: AnyPlantingSiteModel,
) {
  fun calculateSiteEdit(): PlantingSiteEdit {
    if (desiredSite.boundary == null) {
      throw IllegalArgumentException("Cannot remove map from site")
    }

    val zoneEdits = calculateZoneEdits()

    return PlantingSiteEdit(
        areaHaDifference = calculateAreaHaDifference(existingSite.boundary, desiredSite.boundary),
        desiredModel = desiredSite,
        existingModel = existingSite,
        plantingZoneEdits = zoneEdits,
    )
  }

  private fun calculateZoneEdits(): List<PlantingZoneEdit> {
    val existingZonesInUse = existingZonesByDesiredZone.values.filterNotNull().toSet()

    val createEdits =
        existingZonesByDesiredZone
            .filterValues { it == null }
            .keys
            .map { desiredZone ->
              val monitoringPlotEdits =
                  calculateMonitoringPlotEdits(
                      desiredZone,
                      desiredZone.boundary.differenceNullable(desiredSite.exclusion),
                  )

              PlantingZoneEdit.Create(
                  desiredModel = desiredZone,
                  monitoringPlotEdits =
                      monitoringPlotEdits.filterIsInstance<MonitoringPlotEdit.Create>(),
                  plantingSubzoneEdits =
                      calculateSubzoneEdits(null, desiredZone, monitoringPlotEdits),
              )
            }

    val deleteEdits =
        existingSite.plantingZones.toSet().minus(existingZonesInUse).map { existingZone ->
          val plantingSubzoneEdits =
              existingZone.plantingSubzones
                  .filter { it !in desiredSubzonesByExistingSubzone }
                  .map { existingSubzone ->
                    PlantingSubzoneEdit.Delete(
                        existingSubzone,
                        existingSubzone.monitoringPlots
                            .filter { desiredSubzonesByMonitoringPlotId[it.id] == null }
                            .map { MonitoringPlotEdit.Eject(it.id) },
                    )
                  }

          PlantingZoneEdit.Delete(
              existingModel = existingZone,
              plantingSubzoneEdits = plantingSubzoneEdits,
          )
        }

    val updateEdits =
        existingZonesByDesiredZone
            .filterValues { it != null }
            .mapNotNull { (desiredZone, existingZone) ->
              val existingUsableBoundary =
                  existingZone!!.boundary.differenceNullable(existingSite.exclusion)
              val desiredUsableBoundary =
                  desiredZone.boundary.differenceNullable(desiredSite.exclusion)
              val monitoringPlotEdits =
                  calculateMonitoringPlotEdits(
                      desiredZone,
                      desiredUsableBoundary,
                      existingUsableBoundary,
                  )

              val createMonitoringPlotEdits =
                  monitoringPlotEdits.filterIsInstance<MonitoringPlotEdit.Create>()

              val subzoneEdits =
                  calculateSubzoneEdits(existingZone, desiredZone, monitoringPlotEdits)

              if (
                  subzoneEdits.isEmpty() &&
                      createMonitoringPlotEdits.isEmpty() &&
                      existingZone.errorMargin == desiredZone.errorMargin &&
                      existingZone.name == desiredZone.name &&
                      existingZone.studentsT == desiredZone.studentsT &&
                      existingZone.targetPlantingDensity == desiredZone.targetPlantingDensity &&
                      existingZone.variance == desiredZone.variance &&
                      existingUsableBoundary.equalsExact(desiredUsableBoundary, 0.00001)
              ) {
                null
              } else {
                PlantingZoneEdit.Update(
                    addedRegion =
                        desiredUsableBoundary
                            .difference(existingUsableBoundary)
                            .toNormalizedMultiPolygon(),
                    areaHaDifference =
                        calculateAreaHaDifference(existingUsableBoundary, desiredUsableBoundary),
                    desiredModel = desiredZone,
                    existingModel = existingZone,
                    monitoringPlotEdits = createMonitoringPlotEdits,
                    plantingSubzoneEdits = subzoneEdits,
                    removedRegion =
                        existingUsableBoundary
                            .difference(desiredUsableBoundary)
                            .toNormalizedMultiPolygon(),
                )
              }
            }

    return createEdits + updateEdits + deleteEdits
  }

  private fun calculateMonitoringPlotEdits(
      desiredZone: AnyPlantingZoneModel,
      desiredUsableBoundary: Geometry,
      existingUsableBoundary: Geometry = desiredUsableBoundary.factory.createMultiPolygon(),
  ): List<MonitoringPlotEdit> {
    // Now we need two lists of existing monitoring plots: the ones in the part of the
    // zone that overlap with the zone's old geometry, and the ones in the newly-added
    // part of the zone, which may already have monitoring plots if this edit is
    // changing the boundary between two existing zones.
    //
    // We want to pick permanent plots from both lists in proportion to the area of the
    // two parts of the zone. For example, if 60% of the desired zone boundary overlaps
    // with the existing zone boundary, then we want 60% of the permanent plots to come
    // from the first list.
    //
    // If either list runs out of existing plots before we've assigned the required
    // number of permanent plots to the zone, we want to create new plots in the
    // part of the zone the list comes from.
    val overlappingBoundary =
        desiredUsableBoundary.intersection(existingUsableBoundary).toNormalizedMultiPolygon()
    val nonOverlappingBoundary =
        desiredUsableBoundary.difference(existingUsableBoundary).toNormalizedMultiPolygon()
    val fractionOfDesiredAreaOverlappingWithExisting =
        min(1.0, overlappingBoundary.area / desiredUsableBoundary.area)

    val existingPlotsInDesiredZone =
        existingMonitoringPlots.filter { it.boundary.nearlyCoveredBy(desiredUsableBoundary) }
    val (candidatePlots, disqualifiedPlots) =
        existingPlotsInDesiredZone.partition {
          it.isAvailable && !it.isAdHoc && it.sizeMeters == MONITORING_PLOT_SIZE_INT
        }
    val (existingPlotsInOverlappingArea, existingPlotsInNewArea) =
        candidatePlots
            .partition { it.boundary.nearlyCoveredBy(existingUsableBoundary) }
            .toList()
            .map { it.toMutableList() }

    // Returns a function that returns the next plot edit (create or adopt operation) for
    // either the overlapping or the non-overlapping part of the zone.
    fun nextPlotForArea(
        plotList: MutableList<MonitoringPlotModel>,
        boundary: MultiPolygon,
    ): (Int) -> MonitoringPlotEdit {
      return { index ->
        val permanentIndex = index + 1
        val nextExistingPlot = plotList.removeFirstOrNull()
        if (nextExistingPlot != null) {
          MonitoringPlotEdit.Adopt(nextExistingPlot.id, permanentIndex)
        } else {
          MonitoringPlotEdit.Create(boundary, permanentIndex)
        }
      }
    }

    val desiredPermanentPlotEdits =
        (zipProportionally(
            desiredZone.numPermanentPlots,
            fractionOfDesiredAreaOverlappingWithExisting,
            nextPlotForArea(existingPlotsInOverlappingArea, overlappingBoundary),
            nextPlotForArea(existingPlotsInNewArea, nonOverlappingBoundary),
        ))

    // If we didn't use up all the existing permanent plots, remove the remaining ones from the
    // permanent list by setting their permanent indexes to null so that any permanent plots we
    // create later will be randomly placed in the zone. We still want to adopt them into the
    // correct subzones, though.
    val dropExcessExistingPlotEdits =
        (existingPlotsInOverlappingArea + existingPlotsInNewArea + disqualifiedPlots).map {
          MonitoringPlotEdit.Adopt(it.id, permanentIndex = null)
        }

    return desiredPermanentPlotEdits + dropExcessExistingPlotEdits
  }

  private fun calculateSubzoneEdits(
      existingZone: ExistingPlantingZoneModel?,
      desiredZone: AnyPlantingZoneModel,
      monitoringPlotEdits: List<MonitoringPlotEdit>,
  ): List<PlantingSubzoneEdit> {
    val subzoneMappings: Map<AnyPlantingSubzoneModel, ExistingPlantingSubzoneModel?> =
        desiredZone.plantingSubzones.associateWith { existingSubzonesByDesiredSubzone[it] }
    val existingSubzonesInUse =
        existingZone
            ?.plantingSubzones
            ?.filter { desiredSubzonesByExistingSubzone[it] != null }
            ?.toSet() ?: emptySet()

    val createEdits =
        subzoneMappings
            .filterValues { it == null }
            .keys
            .map { newSubzone ->
              val adoptEdits =
                  monitoringPlotEdits.filter {
                    it is MonitoringPlotEdit.Adopt &&
                        desiredSubzonesByMonitoringPlotId[it.monitoringPlotId] == newSubzone
                  }
              PlantingSubzoneEdit.Create(newSubzone, adoptEdits)
            }

    val deleteEdits =
        existingZone?.plantingSubzones?.toSet()?.minus(existingSubzonesInUse)?.map { existingSubzone
          ->
          PlantingSubzoneEdit.Delete(
              existingSubzone,
              existingSubzone.monitoringPlots
                  .filter { desiredSubzonesByMonitoringPlotId[it.id] == null }
                  .map { MonitoringPlotEdit.Eject(it.id) },
          )
        } ?: emptyList()

    val updateEdits =
        subzoneMappings
            .filterValues { it != null }
            .mapNotNull { (desiredSubzone, existingSubzone) ->
              val desiredUsableBoundary =
                  desiredSubzone.boundary.differenceNullable(desiredSite.exclusion)
              val existingUsableBoundary =
                  existingSubzone!!.boundary.differenceNullable(existingSite.exclusion)
              val adoptEdits =
                  monitoringPlotEdits
                      .filterIsInstance<MonitoringPlotEdit.Adopt>()
                      .filter { plotEdit ->
                        desiredSubzonesByMonitoringPlotId[plotEdit.monitoringPlotId] ==
                            desiredSubzone
                      }
                      .filter { plotEdit ->
                        // If the plot is already in the right subzone with the right permanent
                        // index, no need to adopt it.
                        val monitoringPlotId = plotEdit.monitoringPlotId
                        val existingMonitoringPlot = existingMonitoringPlotsById[monitoringPlotId]
                        existingSubzonesByMonitoringPlotId[monitoringPlotId] != existingSubzone ||
                            existingMonitoringPlot?.permanentIndex != plotEdit.permanentIndex
                      }
              val ejectEdits =
                  existingSubzone.monitoringPlots
                      .filter { desiredSubzonesByMonitoringPlotId[it.id] == null }
                      .map { MonitoringPlotEdit.Eject(it.id) }

              if (
                  existingSubzone.fullName == desiredSubzone.fullName &&
                      existingSubzone.boundary.equalsExact(desiredSubzone.boundary, 0.00001) &&
                      existingUsableBoundary.equalsExact(desiredUsableBoundary, 0.00001) &&
                      existingZonesByExistingSubzone[existingSubzone] == existingZone &&
                      adoptEdits.isEmpty() &&
                      ejectEdits.isEmpty()
              ) {
                null
              } else {
                PlantingSubzoneEdit.Update(
                    addedRegion =
                        desiredUsableBoundary
                            .difference(existingUsableBoundary)
                            .toNormalizedMultiPolygon(),
                    areaHaDifference =
                        calculateAreaHaDifference(
                            existingSubzone.boundary,
                            desiredSubzone.boundary,
                        ),
                    desiredModel = desiredSubzone,
                    existingModel = existingSubzone,
                    monitoringPlotEdits = ejectEdits + adoptEdits,
                    removedRegion =
                        existingUsableBoundary
                            .difference(desiredUsableBoundary)
                            .toNormalizedMultiPolygon(),
                )
              }
            }

    return createEdits + updateEdits + deleteEdits
  }

  private fun calculateAreaHaDifference(existing: Geometry?, desired: Geometry): BigDecimal {
    val desiredArea = desired.differenceNullable(desiredSite.exclusion).calculateAreaHectares()
    return if (existing != null) {
      desiredArea - existing.differenceNullable(existingSite.exclusion).calculateAreaHectares()
    } else {
      desiredArea
    }
  }

  /**
   * The existing version of each desired planting zone, or null if the zone is being newly created.
   */
  private val existingZonesByDesiredZone:
      Map<AnyPlantingZoneModel, ExistingPlantingZoneModel?> by lazy {
    val existingZonesByStableId = existingSite.plantingZones.associateBy { it.stableId }
    desiredSite.plantingZones.associateWith { existingZonesByStableId[it.stableId] }
  }

  /**
   * The existing version of each desired planting zone, or null if the zone is being newly created.
   */
  private val existingZonesByExistingSubzone:
      Map<ExistingPlantingSubzoneModel, ExistingPlantingZoneModel> by lazy {
    existingSite.plantingZones
        .flatMap { existingZone -> existingZone.plantingSubzones.map { it to existingZone } }
        .toMap()
  }

  /** The desired version of each existing subzone that isn't being deleted. */
  private val desiredSubzonesByExistingSubzone:
      Map<ExistingPlantingSubzoneModel, AnyPlantingSubzoneModel> by lazy {
    val desiredSubzonesByStableId =
        desiredSite.plantingZones.flatMap { it.plantingSubzones }.associateBy { it.stableId }

    existingSite.plantingZones
        .flatMap { it.plantingSubzones }
        .mapNotNull { subzone ->
          desiredSubzonesByStableId[subzone.stableId]?.let { subzone to it }
        }
        .toMap()
  }

  /** The existing version of each desired subzone that isn't being newly created. */
  private val existingSubzonesByDesiredSubzone:
      Map<AnyPlantingSubzoneModel, ExistingPlantingSubzoneModel> by lazy {
    val existingSubzonesByStableId =
        existingSite.plantingZones.flatMap { it.plantingSubzones }.associateBy { it.stableId }

    desiredSite.plantingZones
        .flatMap { it.plantingSubzones }
        .mapNotNull { subzone ->
          existingSubzonesByStableId[subzone.stableId]?.let { subzone to it }
        }
        .toMap()
  }

  /** Flattened list of all the site's monitoring plots. */
  private val existingMonitoringPlots: List<MonitoringPlotModel> by lazy {
    val plotsInSubzones =
        existingSite.plantingZones.flatMap { plantingZone ->
          plantingZone.plantingSubzones.flatMap { it.monitoringPlots }
        }
    (plotsInSubzones + existingSite.exteriorPlots).sortedWith(
        compareBy({ it.permanentIndex ?: Int.MAX_VALUE }, { it.plotNumber })
    )
  }

  private val existingMonitoringPlotsById: Map<MonitoringPlotId, MonitoringPlotModel> by lazy {
    existingMonitoringPlots.associateBy { it.id }
  }

  /**
   * For each existing monitoring plot, the desired zone that covers it, or null if it isn't covered
   * by any zone. Plots that straddle two zones aren't considered to be in either zone.
   */
  private val desiredZonesByMonitoringPlotId: Map<MonitoringPlotId, AnyPlantingZoneModel?> by lazy {
    existingMonitoringPlots.associate { plot ->
      plot.id to
          desiredSite.plantingZones.firstOrNull { zone ->
            plot.boundary.nearlyCoveredBy(zone.boundary.differenceNullable(desiredSite.exclusion))
          }
    }
  }

  /**
   * For each existing monitoring plot, the desired subzone that covers the largest fraction of it,
   * or null if the plot is outside all subzones.
   */
  private val desiredSubzonesByMonitoringPlotId:
      Map<MonitoringPlotId, AnyPlantingSubzoneModel?> by lazy {
    existingMonitoringPlots.associate { plot ->
      plot.id to
          desiredZonesByMonitoringPlotId[plot.id]?.let { desiredZone ->
            desiredZone.plantingSubzones
                .mapNotNull { desiredSubzone ->
                  if (desiredSubzone.boundary.intersects(plot.boundary)) {
                    desiredSubzone to desiredSubzone.boundary.intersection(plot.boundary).area
                  } else {
                    null
                  }
                }
                .maxByOrNull { it.second }
                ?.first
          }
    }
  }

  /** Which subzone each each existing monitoring plot is in. */
  private val existingSubzonesByMonitoringPlotId:
      Map<MonitoringPlotId, ExistingPlantingSubzoneModel> by lazy {
    existingSite.plantingZones
        .flatMap { zone ->
          zone.plantingSubzones.flatMap { subzone ->
            subzone.monitoringPlots.map { plot -> plot.id to subzone }
          }
        }
        .toMap()
  }
}
