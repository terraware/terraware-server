package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.AnyStratumModel
import com.terraformation.backend.tracking.model.AnySubstratumModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.ExistingSubstratumModel
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

    val stratumEdits = calculateStratumEdits()

    return PlantingSiteEdit(
        areaHaDifference = calculateAreaHaDifference(existingSite.boundary, desiredSite.boundary),
        desiredModel = desiredSite,
        existingModel = existingSite,
        stratumEdits = stratumEdits,
    )
  }

  private fun calculateStratumEdits(): List<StratumEdit> {
    val existingStrataInUse = existingStrataByDesiredStratum.values.filterNotNull().toSet()

    val createEdits =
        existingStrataByDesiredStratum
            .filterValues { it == null }
            .keys
            .map { desiredStratum ->
              val monitoringPlotEdits =
                  calculateMonitoringPlotEdits(
                      desiredStratum,
                      desiredStratum.boundary.differenceNullable(desiredSite.exclusion),
                  )

              StratumEdit.Create(
                  desiredModel = desiredStratum,
                  monitoringPlotEdits =
                      monitoringPlotEdits.filterIsInstance<MonitoringPlotEdit.Create>(),
                  substratumEdits =
                      calculateSubstratumEdits(null, desiredStratum, monitoringPlotEdits),
              )
            }

    val deleteEdits =
        existingSite.strata.toSet().minus(existingStrataInUse).map { existingStratum ->
          val substratumEdits =
              existingStratum.substrata
                  .filter { it !in desiredSubstrataByExistingSubstratum }
                  .map { existingSubstratum ->
                    SubstratumEdit.Delete(
                        existingSubstratum,
                        existingSubstratum.monitoringPlots
                            .filter { desiredSubstrataByMonitoringPlotId[it.id] == null }
                            .map { MonitoringPlotEdit.Eject(it.id) },
                    )
                  }

          StratumEdit.Delete(
              existingModel = existingStratum,
              substratumEdits = substratumEdits,
          )
        }

    val updateEdits =
        existingStrataByDesiredStratum
            .filterValues { it != null }
            .mapNotNull { (desiredStratum, existingStratum) ->
              val existingUsableBoundary =
                  existingStratum!!.boundary.differenceNullable(existingSite.exclusion)
              val desiredUsableBoundary =
                  desiredStratum.boundary.differenceNullable(desiredSite.exclusion)
              val monitoringPlotEdits =
                  calculateMonitoringPlotEdits(
                      desiredStratum,
                      desiredUsableBoundary,
                      existingUsableBoundary,
                  )

              val createMonitoringPlotEdits =
                  monitoringPlotEdits.filterIsInstance<MonitoringPlotEdit.Create>()

              val substratumEdits =
                  calculateSubstratumEdits(existingStratum, desiredStratum, monitoringPlotEdits)

              if (
                  substratumEdits.isEmpty() &&
                      createMonitoringPlotEdits.isEmpty() &&
                      existingStratum.errorMargin == desiredStratum.errorMargin &&
                      existingStratum.name == desiredStratum.name &&
                      existingStratum.studentsT == desiredStratum.studentsT &&
                      existingStratum.targetPlantingDensity ==
                          desiredStratum.targetPlantingDensity &&
                      existingStratum.variance == desiredStratum.variance &&
                      existingUsableBoundary.equalsExact(desiredUsableBoundary, 0.00001)
              ) {
                null
              } else {
                StratumEdit.Update(
                    addedRegion =
                        desiredUsableBoundary
                            .difference(existingUsableBoundary)
                            .toNormalizedMultiPolygon(),
                    areaHaDifference =
                        calculateAreaHaDifference(existingUsableBoundary, desiredUsableBoundary),
                    desiredModel = desiredStratum,
                    existingModel = existingStratum,
                    monitoringPlotEdits = createMonitoringPlotEdits,
                    substratumEdits = substratumEdits,
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
      desiredStratum: AnyStratumModel,
      desiredUsableBoundary: Geometry,
      existingUsableBoundary: Geometry = desiredUsableBoundary.factory.createMultiPolygon(),
  ): List<MonitoringPlotEdit> {
    // Now we need two lists of existing monitoring plots: the ones in the part of the
    // stratum that overlap with the stratum's old geometry, and the ones in the newly-added
    // part of the stratum, which may already have monitoring plots if this edit is
    // changing the boundary between two existing strata.
    //
    // We want to pick permanent plots from both lists in proportion to the area of the
    // two parts of the stratum. For example, if 60% of the desired stratum boundary overlaps
    // with the existing stratum boundary, then we want 60% of the permanent plots to come
    // from the first list.
    //
    // If either list runs out of existing plots before we've assigned the required
    // number of permanent plots to the stratum, we want to create new plots in the
    // part of the stratum the list comes from.
    val overlappingBoundary =
        desiredUsableBoundary.intersection(existingUsableBoundary).toNormalizedMultiPolygon()
    val nonOverlappingBoundary =
        desiredUsableBoundary.difference(existingUsableBoundary).toNormalizedMultiPolygon()
    val fractionOfDesiredAreaOverlappingWithExisting =
        min(1.0, overlappingBoundary.area / desiredUsableBoundary.area)

    val existingPlotsInDesiredStratum =
        existingMonitoringPlots.filter { it.boundary.nearlyCoveredBy(desiredUsableBoundary) }
    val (candidatePlots, disqualifiedPlots) =
        existingPlotsInDesiredStratum.partition {
          it.isAvailable && !it.isAdHoc && it.sizeMeters == MONITORING_PLOT_SIZE_INT
        }
    val (existingPlotsInOverlappingArea, existingPlotsInNewArea) =
        candidatePlots
            .partition { it.boundary.nearlyCoveredBy(existingUsableBoundary) }
            .toList()
            .map { it.toMutableList() }

    // Returns a function that returns the next plot edit (create or adopt operation) for
    // either the overlapping or the non-overlapping part of the stratum.
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
            desiredStratum.numPermanentPlots,
            fractionOfDesiredAreaOverlappingWithExisting,
            nextPlotForArea(existingPlotsInOverlappingArea, overlappingBoundary),
            nextPlotForArea(existingPlotsInNewArea, nonOverlappingBoundary),
        ))

    // If we didn't use up all the existing permanent plots, remove the remaining ones from the
    // permanent list by setting their permanent indexes to null so that any permanent plots we
    // create later will be randomly placed in the stratum. We still want to adopt them into the
    // correct substrata, though.
    val dropExcessExistingPlotEdits =
        (existingPlotsInOverlappingArea + existingPlotsInNewArea + disqualifiedPlots).map {
          MonitoringPlotEdit.Adopt(it.id, permanentIndex = null)
        }

    return desiredPermanentPlotEdits + dropExcessExistingPlotEdits
  }

  private fun calculateSubstratumEdits(
      existingStratum: ExistingStratumModel?,
      desiredStratum: AnyStratumModel,
      monitoringPlotEdits: List<MonitoringPlotEdit>,
  ): List<SubstratumEdit> {
    val substratumMappings: Map<AnySubstratumModel, ExistingSubstratumModel?> =
        desiredStratum.substrata.associateWith { existingSubstrataByDesiredSubstratum[it] }
    val existingSubstrataInUse =
        existingStratum
            ?.substrata
            ?.filter { desiredSubstrataByExistingSubstratum[it] != null }
            ?.toSet() ?: emptySet()

    val createEdits =
        substratumMappings
            .filterValues { it == null }
            .keys
            .map { newSubstratum ->
              val adoptEdits =
                  monitoringPlotEdits.filter {
                    it is MonitoringPlotEdit.Adopt &&
                        desiredSubstrataByMonitoringPlotId[it.monitoringPlotId] == newSubstratum
                  }
              SubstratumEdit.Create(newSubstratum, adoptEdits)
            }

    val deleteEdits =
        existingStratum?.substrata?.toSet()?.minus(existingSubstrataInUse)?.map { existingSubstratum
          ->
          SubstratumEdit.Delete(
              existingSubstratum,
              existingSubstratum.monitoringPlots
                  .filter { desiredSubstrataByMonitoringPlotId[it.id] == null }
                  .map { MonitoringPlotEdit.Eject(it.id) },
          )
        } ?: emptyList()

    val updateEdits =
        substratumMappings
            .filterValues { it != null }
            .mapNotNull { (desiredSubstratum, existingSubstratum) ->
              val desiredUsableBoundary =
                  desiredSubstratum.boundary.differenceNullable(desiredSite.exclusion)
              val existingUsableBoundary =
                  existingSubstratum!!.boundary.differenceNullable(existingSite.exclusion)
              val adoptEdits =
                  monitoringPlotEdits
                      .filterIsInstance<MonitoringPlotEdit.Adopt>()
                      .filter { plotEdit ->
                        desiredSubstrataByMonitoringPlotId[plotEdit.monitoringPlotId] ==
                            desiredSubstratum
                      }
                      .filter { plotEdit ->
                        // If the plot is already in the right substratum with the right permanent
                        // index, no need to adopt it.
                        val monitoringPlotId = plotEdit.monitoringPlotId
                        val existingMonitoringPlot = existingMonitoringPlotsById[monitoringPlotId]
                        existingSubstrataByMonitoringPlotId[monitoringPlotId] !=
                            existingSubstratum ||
                            existingMonitoringPlot?.permanentIndex != plotEdit.permanentIndex
                      }
              val ejectEdits =
                  existingSubstratum.monitoringPlots
                      .filter { desiredSubstrataByMonitoringPlotId[it.id] == null }
                      .map { MonitoringPlotEdit.Eject(it.id) }

              if (
                  existingSubstratum.fullName == desiredSubstratum.fullName &&
                      existingSubstratum.boundary.equalsExact(
                          desiredSubstratum.boundary,
                          0.00001,
                      ) &&
                      existingUsableBoundary.equalsExact(desiredUsableBoundary, 0.00001) &&
                      existingStrataByExistingSubstratum[existingSubstratum] == existingStratum &&
                      adoptEdits.isEmpty() &&
                      ejectEdits.isEmpty()
              ) {
                null
              } else {
                SubstratumEdit.Update(
                    addedRegion =
                        desiredUsableBoundary
                            .difference(existingUsableBoundary)
                            .toNormalizedMultiPolygon(),
                    areaHaDifference =
                        calculateAreaHaDifference(
                            existingSubstratum.boundary,
                            desiredSubstratum.boundary,
                        ),
                    desiredModel = desiredSubstratum,
                    existingModel = existingSubstratum,
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
   * The existing version of each desired stratum, or null if the stratum is being newly created.
   */
  private val existingStrataByDesiredStratum: Map<AnyStratumModel, ExistingStratumModel?> by lazy {
    val existingStrataByStableId = existingSite.strata.associateBy { it.stableId }
    desiredSite.strata.associateWith { existingStrataByStableId[it.stableId] }
  }

  /**
   * The existing version of each desired stratum, or null if the stratum is being newly created.
   */
  private val existingStrataByExistingSubstratum:
      Map<ExistingSubstratumModel, ExistingStratumModel> by lazy {
    existingSite.strata
        .flatMap { existingStratum -> existingStratum.substrata.map { it to existingStratum } }
        .toMap()
  }

  /** The desired version of each existing substratum that isn't being deleted. */
  private val desiredSubstrataByExistingSubstratum:
      Map<ExistingSubstratumModel, AnySubstratumModel> by lazy {
    val desiredSubstrataByStableId =
        desiredSite.strata.flatMap { it.substrata }.associateBy { it.stableId }

    existingSite.strata
        .flatMap { it.substrata }
        .mapNotNull { substratum ->
          desiredSubstrataByStableId[substratum.stableId]?.let { substratum to it }
        }
        .toMap()
  }

  /** The existing version of each desired substratum that isn't being newly created. */
  private val existingSubstrataByDesiredSubstratum:
      Map<AnySubstratumModel, ExistingSubstratumModel> by lazy {
    val existingSubstrataByStableId =
        existingSite.strata.flatMap { it.substrata }.associateBy { it.stableId }

    desiredSite.strata
        .flatMap { it.substrata }
        .mapNotNull { substratum ->
          existingSubstrataByStableId[substratum.stableId]?.let { substratum to it }
        }
        .toMap()
  }

  /** Flattened list of all the site's monitoring plots. */
  private val existingMonitoringPlots: List<MonitoringPlotModel> by lazy {
    val plotsInSubstrata =
        existingSite.strata.flatMap { stratum -> stratum.substrata.flatMap { it.monitoringPlots } }
    (plotsInSubstrata + existingSite.exteriorPlots).sortedWith(
        compareBy({ it.permanentIndex ?: Int.MAX_VALUE }, { it.plotNumber })
    )
  }

  private val existingMonitoringPlotsById: Map<MonitoringPlotId, MonitoringPlotModel> by lazy {
    existingMonitoringPlots.associateBy { it.id }
  }

  /**
   * For each existing monitoring plot, the desired stratum that covers it, or null if it isn't
   * covered by any stratum. Plots that straddle two strata aren't considered to be in either
   * stratum.
   */
  private val desiredStrataByMonitoringPlotId: Map<MonitoringPlotId, AnyStratumModel?> by lazy {
    existingMonitoringPlots.associate { plot ->
      plot.id to
          desiredSite.strata.firstOrNull { stratum ->
            plot.boundary.nearlyCoveredBy(
                stratum.boundary.differenceNullable(desiredSite.exclusion)
            )
          }
    }
  }

  /**
   * For each existing monitoring plot, the desired substratum that covers the largest fraction of
   * it, or null if the plot is outside all substrata.
   */
  private val desiredSubstrataByMonitoringPlotId:
      Map<MonitoringPlotId, AnySubstratumModel?> by lazy {
    existingMonitoringPlots.associate { plot ->
      plot.id to
          desiredStrataByMonitoringPlotId[plot.id]?.let { desiredStratum ->
            desiredStratum.substrata
                .mapNotNull { desiredSubstratum ->
                  if (desiredSubstratum.boundary.intersects(plot.boundary)) {
                    desiredSubstratum to desiredSubstratum.boundary.intersection(plot.boundary).area
                  } else {
                    null
                  }
                }
                .maxByOrNull { it.second }
                ?.first
          }
    }
  }

  /** Which substratum each each existing monitoring plot is in. */
  private val existingSubstrataByMonitoringPlotId:
      Map<MonitoringPlotId, ExistingSubstratumModel> by lazy {
    existingSite.strata
        .flatMap { stratum ->
          stratum.substrata.flatMap { substratum ->
            substratum.monitoringPlots.map { plot -> plot.id to substratum }
          }
        }
        .toMap()
  }
}
