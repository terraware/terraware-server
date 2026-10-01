package com.terraformation.backend.admin

import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ObservationIncludedPlotModel
import com.terraformation.backend.tracking.model.ObservationResultsModel
import java.time.Instant

data class StratumSurvivalRateRow(
    val id: StratumId,
    val name: String,
    val observationCompletedTime: String?,
    val observationId: ObservationId?,
    val plantingCompleted: Boolean?,
    val plantingDensity: Int?,
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    val totalPlants: Int?,
)

data class SubstratumSurvivalRateRow(
    val id: SubstratumId,
    val name: String,
    val observationCompletedTime: String?,
    val observationId: ObservationId?,
    val plantingCompleted: Boolean?,
    val plantingDensity: Int?,
    val stratumName: String,
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    val totalPlants: Int?,
)

data class MonitoringPlotSurvivalRateRow(
    val id: MonitoringPlotId,
    val isPermanent: Boolean?,
    val observationCompletedTime: String?,
    val observationId: ObservationId?,
    val plantingDensity: Int?,
    val plotNumber: Long,
    val status: ObservationPlotStatus?,
    val stratumName: String,
    val substratumName: String,
    val survivalRate: Int?,
    val totalPlants: Int?,
)

data class IncludedPlotRow(
    /**
     * True if the plot counts toward the aggregate survival rate but has no t0 density, which makes
     * the aggregate rate null.
     */
    val blocksAggregateRate: Boolean,
    val countsTowardSurvivalRate: Boolean,
    val hasT0Density: Boolean,
    val id: MonitoringPlotId,
    val isPermanent: Boolean,
    val observationCompletedTime: String?,
    val observationId: ObservationId,
    val plotNumber: Long,
    val stratumName: String?,
    val substratumName: String?,
    val survivalRate: Int?,
    val totalLive: Int?,
)

data class SurvivalRatesPageModel(
    val latestCompletedObservationId: ObservationId?,
    val latestCompletedSurvivalRate: Int?,
    val latestCompletedTime: String?,
    val latestIncludedPlots: List<IncludedPlotRow>,
    val monitoringPlots: List<MonitoringPlotSurvivalRateRow>,
    val organizationId: OrganizationId,
    val organizationName: String,
    val siteId: PlantingSiteId,
    val siteName: String,
    val siteSurvivalRate: Int?,
    val siteSurvivalRateObservationId: ObservationId?,
    val siteSurvivalRateObservationCompletedTime: String?,
    val strata: List<StratumSurvivalRateRow>,
    val substrata: List<SubstratumSurvivalRateRow>,
    val survivalRateIncludesTempPlots: Boolean,
) {
  companion object {
    fun of(
        site: ExistingPlantingSiteModel,
        organization: OrganizationModel,
        results: List<ObservationResultsModel>,
        latestIncludedPlots: List<ObservationIncludedPlotModel>,
    ): SurvivalRatesPageModel {
      val latestCompleted = results.firstOrNull()

      val stratumNames = site.strata.associate { it.id to it.name }
      val substratumNames =
          site.strata.flatMap { stratum -> stratum.substrata.map { it.id to it.name } }.toMap()
      val siteSurvivalRateSource = results.firstOrNull { it.survivalRate != null }

      val strataById =
          results
              .flatMap { result ->
                result.strata.mapNotNull { stratum ->
                  stratum.stratumId?.let {
                    it to ResultSource(result.observationId, result.completedTime, stratum)
                  }
                }
              }
              .toMapKeepingFirst()
      val substrataById =
          results
              .flatMap { result ->
                result.strata.flatMap { stratum ->
                  stratum.substrata.mapNotNull { substratum ->
                    substratum.substratumId?.let {
                      it to ResultSource(result.observationId, result.completedTime, substratum)
                    }
                  }
                }
              }
              .toMapKeepingFirst()
      val monitoringPlotsById =
          results
              .flatMap { result ->
                result.strata.flatMap { stratum ->
                  stratum.substrata.flatMap { substratum ->
                    substratum.monitoringPlots.map { plot ->
                      plot.monitoringPlotId to
                          ResultSource(result.observationId, result.completedTime, plot)
                    }
                  }
                }
              }
              .toMapKeepingFirst()

      return SurvivalRatesPageModel(
          latestCompletedObservationId = latestCompleted?.observationId,
          latestCompletedSurvivalRate = latestCompleted?.survivalRate,
          latestCompletedTime = latestCompleted?.completedTime?.toString(),
          latestIncludedPlots =
              latestIncludedPlots.map { plot ->
                val countsTowardSurvivalRate =
                    !plot.isRolledForwardFromDeletedSubstratum &&
                        (plot.isPermanent || site.survivalRateIncludesTempPlots)
                IncludedPlotRow(
                    blocksAggregateRate = countsTowardSurvivalRate && !plot.hasT0Density,
                    countsTowardSurvivalRate = countsTowardSurvivalRate,
                    hasT0Density = plot.hasT0Density,
                    id = plot.monitoringPlotId,
                    isPermanent = plot.isPermanent,
                    observationCompletedTime = plot.completedTime?.toString(),
                    observationId = plot.observationId,
                    plotNumber = plot.monitoringPlotNumber,
                    stratumName = plot.stratumId?.let { stratumNames[it] },
                    substratumName = plot.substratumId?.let { substratumNames[it] },
                    survivalRate = plot.survivalRate,
                    totalLive = plot.totalLive,
                )
              },
          monitoringPlots =
              site.strata.flatMap { stratum ->
                stratum.substrata.flatMap { substratum ->
                  substratum.monitoringPlots.map { plot ->
                    val source = monitoringPlotsById[plot.id]
                    MonitoringPlotSurvivalRateRow(
                        id = plot.id,
                        isPermanent = source?.value?.isPermanent,
                        observationCompletedTime = source?.observationCompletedTime?.toString(),
                        observationId = source?.observationId,
                        plantingDensity = source?.value?.plantingDensity,
                        plotNumber = plot.plotNumber,
                        status = source?.value?.status,
                        stratumName = stratum.name,
                        substratumName = substratum.name,
                        survivalRate = source?.value?.survivalRate,
                        totalPlants = source?.value?.totalPlants,
                    )
                  }
                }
              },
          organizationId = organization.id,
          organizationName = organization.name,
          siteId = site.id,
          siteName = site.name,
          siteSurvivalRate = siteSurvivalRateSource?.survivalRate,
          siteSurvivalRateObservationId = siteSurvivalRateSource?.observationId,
          siteSurvivalRateObservationCompletedTime =
              siteSurvivalRateSource?.completedTime?.toString(),
          strata =
              site.strata.map { stratum ->
                val source = strataById[stratum.id]
                StratumSurvivalRateRow(
                    id = stratum.id,
                    name = stratum.name,
                    observationCompletedTime = source?.observationCompletedTime?.toString(),
                    observationId = source?.observationId,
                    plantingCompleted = source?.value?.plantingCompleted,
                    plantingDensity = source?.value?.plantingDensity,
                    survivalRate = source?.value?.survivalRate,
                    survivalRateStdDev = source?.value?.survivalRateStdDev,
                    totalPlants = source?.value?.totalPlants,
                )
              },
          substrata =
              site.strata.flatMap { stratum ->
                stratum.substrata.map { substratum ->
                  val source = substrataById[substratum.id]
                  SubstratumSurvivalRateRow(
                      id = substratum.id,
                      name = substratum.name,
                      observationCompletedTime = source?.observationCompletedTime?.toString(),
                      observationId = source?.observationId,
                      plantingCompleted = source?.value?.plantingCompleted,
                      plantingDensity = source?.value?.plantingDensity,
                      stratumName = stratum.name,
                      survivalRate = source?.value?.survivalRate,
                      survivalRateStdDev = source?.value?.survivalRateStdDev,
                      totalPlants = source?.value?.totalPlants,
                  )
                }
              },
          survivalRateIncludesTempPlots = site.survivalRateIncludesTempPlots,
      )
    }
  }
}

private data class ResultSource<T>(
    val observationId: ObservationId,
    val observationCompletedTime: Instant?,
    val value: T,
)

/**
 * Builds a map without replacing existing keys. Observation results are ordered newest-first, so
 * this keeps the newest result for each entity.
 */
private fun <K, V> Iterable<Pair<K, V>>.toMapKeepingFirst(): Map<K, V> = buildMap {
  this@toMapKeepingFirst.forEach { (key, value) -> putIfAbsent(key, value) }
}
