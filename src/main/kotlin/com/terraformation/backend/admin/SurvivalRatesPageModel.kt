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
import com.terraformation.backend.tracking.model.ObservationResultsModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

data class SurvivalRatesPageModel(
    val latestCompletedObservationId: ObservationId?,
    val latestCompletedSurvivalRate: Int?,
    val latestCompletedTime: String?,
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
    ): SurvivalRatesPageModel {
      val latestCompleted = results.firstOrNull()
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
          latestCompletedTime = latestCompleted?.completedTime?.toDisplayString(),
          monitoringPlots =
              site.strata.flatMap { stratum ->
                stratum.substrata.flatMap { substratum ->
                  substratum.monitoringPlots.map { plot ->
                    val source = monitoringPlotsById[plot.id]
                    MonitoringPlotSurvivalRateRow(
                        id = plot.id,
                        isPermanent = source?.value?.isPermanent,
                        observationCompletedTime =
                            source?.observationCompletedTime?.toDisplayString(),
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
              siteSurvivalRateSource?.completedTime?.toDisplayString(),
          strata =
              site.strata.map { stratum ->
                val source = strataById[stratum.id]
                StratumSurvivalRateRow(
                    id = stratum.id,
                    name = stratum.name,
                    observationCompletedTime = source?.observationCompletedTime?.toDisplayString(),
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
                      observationCompletedTime =
                          source?.observationCompletedTime?.toDisplayString(),
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

private val displayTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

private fun Instant.toDisplayString(): String = displayTimeFormatter.format(this)

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
