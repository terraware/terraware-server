package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.tracking.model.PlaceablePlotCounts
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import com.terraformation.backend.tracking.model.StratumPlotCounts
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlotSettingsPreviewPayloadTest {
  private val site = existingSite()
  private val current =
      StratumPlotCounts(
          studentsT = BigDecimal("1.645"),
          variance = BigDecimal("100"),
          errorMargin = BigDecimal("15"),
          numPermanentPlots = 8,
          numTemporaryPlots = 3,
      )

  @Test
  fun `preview maps current and proposed settings with proposed-variance alternatives`() {
    val proposed =
        current.copy(
            mode = PlotSelectionMode.Direct,
            plotPrecisionLevel = null,
            variance = BigDecimal("900"),
            numPermanentPlots = 7,
        )
    val stratum = site.strata.single().copy(plantPlotCounts = current)
    val actual =
        PlotSettingsPreviewResponsePayload(
                site.copy(strata = listOf(stratum)),
                mapOf(stratum.id to mapOf(ObservationType.Monitoring to proposed)),
            )
            .strata
            .single()
            .settings
    val monitoring = actual.first()
    assertEquals(
        ObservationType.entries,
        actual.map { it.observationType },
        "Observation type order",
    )
    assertEquals(
        PlaceablePlotCountsPreviewPayload(8, 3, 8, 3, current.expectedPrecision),
        monitoring.current,
        "Current settings retain stored counts, placeable counts, and precision",
    )
    assertEquals(
        PlaceablePlotCountsPreviewPayload(7, 3, 7, 3, proposed.expectedPrecision),
        monitoring.proposed,
        "Proposed settings use edited counts, placeable counts, and variance",
    )
    val biomass = actual.single { it.observationType == ObservationType.BiomassMeasurements }
    assertEquals(biomass.current, biomass.proposed, "Omitted edits preserve current settings")
    assertEquals(
        PlotPrecisionLevel.entries,
        monitoring.precisionLevels.map { it.plotPrecisionLevel },
        "Every precision-level alternative is present in order",
    )
    val medium =
        PlotCountsPreviewPayload(8, 3, proposed.copy(numPermanentPlots = 8).expectedPrecision)
    assertEquals(
        medium,
        monitoring.precisionLevels
            .single { it.plotPrecisionLevel == PlotPrecisionLevel.Medium }
            .counts,
        "Precision-level alternatives use proposed variance",
    )
    assertEquals(medium, monitoring.custom, "Custom alternative uses proposed parameters")
  }

  @Test
  fun `oversized alternatives are null but a requested oversized setting is rejected`() {
    val counts = current.copy(variance = BigDecimal("250000"), errorMargin = BigDecimal("5"))
    val stratum = site.strata.single().copy(plantPlotCounts = counts)
    val model = site.copy(strata = listOf(stratum))
    val direct =
        UpdateStratumPlotSettingPayload(
            stratum.id,
            ObservationType.Monitoring,
            PlotSelectionMode.Direct,
            numPermanentPlots = 7,
            numTemporaryPlots = 3,
        )
    val request = UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High, listOf(direct))
    val actual =
        PlotSettingsPreviewResponsePayload(model, request.toSettings(model))
            .strata
            .single()
            .settings
            .first()
    assertEquals(
        PlaceablePlotCountsPreviewPayload(
            7,
            3,
            7,
            3,
            counts.copy(numPermanentPlots = 7).expectedPrecision,
        ),
        actual.proposed,
        "A valid Direct proposal remains available despite oversized alternatives",
    )
    assertEquals(
        PlotPrecisionLevel.entries,
        actual.precisionLevels.map { it.plotPrecisionLevel },
        "Oversized alternatives retain their precision levels",
    )
    assertEquals(
        PlotCountsPreviewPayload(null, null, null),
        actual.precisionLevels
            .single { it.plotPrecisionLevel == PlotPrecisionLevel.VeryHigh }
            .counts,
        "An oversized precision alternative has null counts and precision",
    )
    val medium = counts.copy(numPermanentPlots = 2255, numTemporaryPlots = 752)
    assertEquals(
        PlotCountsPreviewPayload(2255, 752, medium.expectedPrecision),
        actual.precisionLevels.single { it.plotPrecisionLevel == PlotPrecisionLevel.Medium }.counts,
        "Alternatives below the cap remain available",
    )
    assertEquals(
        PlotCountsPreviewPayload(null, null, null),
        actual.custom,
        "An oversized custom alternative has null counts and precision",
    )
    assertThrows<IllegalArgumentException> {
      request
          .copy(
              strata =
                  listOf(
                      direct.copy(
                          mode = PlotSelectionMode.PrecisionLevel,
                          plotPrecisionLevel = PlotPrecisionLevel.VeryHigh,
                      )
                  )
          )
          .toSettings(model)
    }
  }

  @Test
  fun `preview reports placeable permanent and temporary counts for small and unmapped sites`() {
    val smallSite =
        existingSite(width = 60, height = 60) {
          stratum(plantNumPermanent = 2, plantNumTemporary = 1) {
            substratum {
              plot(x = 0, y = 0, permanentIndex = 1)
              plot(x = 30, y = 0, permanentIndex = 2)
              plot(x = 0, y = 30, permanentIndex = 3)
              plot(x = 30, y = 30, permanentIndex = 4)
            }
          }
        }
    val stratum = smallSite.strata.single()
    val allObserved = stratum.substrata.single().monitoringPlots.map { it.id }.toSet()
    val proposedCounts = stratum.plantPlotCounts.copy(numPermanentPlots = 6, numTemporaryPlots = 2)
    val oversized = mapOf(stratum.id to mapOf(ObservationType.Monitoring to proposedCounts))

    fun expectedResponse(
        monitoringCurrent: PlaceablePlotCounts?,
        monitoringProposed: PlaceablePlotCounts?,
        biomassCurrent: PlaceablePlotCounts?,
        biomassProposed: PlaceablePlotCounts?,
        baselineCurrent: PlaceablePlotCounts?,
        baselineProposed: PlaceablePlotCounts?,
    ) =
        PlotSettingsPreviewResponsePayload(
            listOf(
                StratumPlotSettingsPreviewPayload(
                    stratumId = stratum.id,
                    name = stratum.name,
                    settings =
                        listOf(
                            PlotSettingPreviewPayload(
                                ObservationType.Monitoring,
                                stratum.plantPlotCounts,
                                proposedCounts,
                                monitoringCurrent,
                                monitoringProposed,
                            ),
                            PlotSettingPreviewPayload(
                                ObservationType.BiomassMeasurements,
                                stratum.biomassPlotCounts,
                                stratum.biomassPlotCounts,
                                biomassCurrent,
                                biomassProposed,
                            ),
                            PlotSettingPreviewPayload(
                                ObservationType.BaselineSurvey,
                                stratum.baselinePlotCounts,
                                stratum.baselinePlotCounts,
                                baselineCurrent,
                                baselineProposed,
                            ),
                        ),
                )
            )
        )

    assertEquals(
        expectedResponse(
            monitoringCurrent = PlaceablePlotCounts(2, 1),
            monitoringProposed = PlaceablePlotCounts(3, 1),
            biomassCurrent = PlaceablePlotCounts(2, 1),
            biomassProposed = PlaceablePlotCounts(2, 0),
            baselineCurrent = PlaceablePlotCounts(0, 2),
            baselineProposed = PlaceablePlotCounts(0, 0),
        ),
        PlotSettingsPreviewResponsePayload(smallSite, oversized),
        "Shortfall proposal reports 75/25 placeable counts and excludes newly active permanent plots from other types",
    )
    assertEquals(
        expectedResponse(
            monitoringCurrent = PlaceablePlotCounts(2, 1),
            monitoringProposed = PlaceablePlotCounts(4, 0),
            biomassCurrent = PlaceablePlotCounts(2, 1),
            biomassProposed = PlaceablePlotCounts(2, 0),
            baselineCurrent = PlaceablePlotCounts(0, 2),
            baselineProposed = PlaceablePlotCounts(0, 0),
        ),
        PlotSettingsPreviewResponsePayload(smallSite, oversized, allObserved),
        "Observed permanent plots stay pinned with no room for temporary plots",
    )
    assertEquals(
        expectedResponse(
            monitoringCurrent = null,
            monitoringProposed = null,
            biomassCurrent = null,
            biomassProposed = null,
            baselineCurrent = null,
            baselineProposed = null,
        ),
        PlotSettingsPreviewResponsePayload(smallSite.copy(gridOrigin = null), oversized),
        "Unmapped site leaves all placeable counts null",
    )
  }
}
