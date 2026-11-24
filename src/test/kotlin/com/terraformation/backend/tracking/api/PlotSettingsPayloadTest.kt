package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlotSettingsPayloadTest {
  private val site = existingSite()
  private val entry =
      UpdateStratumPlotSettingPayload(
          site.strata.single().id,
          ObservationType.Monitoring,
          PlotSelectionMode.Custom,
          confidenceLevel = 80,
          targetMarginOfError = BigDecimal("25"),
          variability = BigDecimal("5"),
      )

  @Test
  fun `omitted observation type applies settings to all types and rejects overlaps`() {
    val allTypes =
        UpdateStratumPlotSettingPayload(
            stratumId = entry.stratumId,
            mode = PlotSelectionMode.Custom,
            confidenceLevel = 80,
            targetMarginOfError = BigDecimal("25"),
            variability = BigDecimal("5"),
        )
    val request = UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High, listOf(allTypes))
    val expected =
        ObservationType.entries.associateWith { type ->
          site.strata
              .single()
              .plotCounts(type)
              .copy(
                  mode = PlotSelectionMode.Custom,
                  plotPrecisionLevel = null,
                  studentsT = BigDecimal("1.282"),
                  errorMargin = BigDecimal("25"),
                  variance = BigDecimal("25"),
                  numPermanentPlots = if (type == ObservationType.BaselineSurvey) 0 else 1,
                  numTemporaryPlots = 1,
              )
        }
    assertEquals(
        mapOf(entry.stratumId to expected),
        request.toSettings(site),
        "All observation types receive the custom setting",
    )
    val precision =
        allTypes.copy(
            mode = PlotSelectionMode.PrecisionLevel,
            plotPrecisionLevel = PlotPrecisionLevel.High,
        )
    assertEquals(
        UpdatePlotSettingsRequestPayload(
                PlotPrecisionLevel.High,
                ObservationType.entries.map { precision.copy(observationType = it) },
            )
            .toSettings(site),
        request.copy(strata = listOf(precision)).toSettings(site),
        "All-types precision edit matches three explicit edits",
    )
    listOf(listOf(allTypes, entry), listOf(entry, allTypes), listOf(allTypes, allTypes)).forEach {
        entries ->
      assertThrows<IllegalArgumentException> { request.copy(strata = entries).toEdits(site) }
    }
    val direct =
        entry.copy(
            observationType = null,
            mode = PlotSelectionMode.Direct,
            numPermanentPlots = 7,
            numTemporaryPlots = 3,
        )
    val directRequest = UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High, listOf(direct))
    val expectedDirect =
        ObservationType.entries.associateWith { type ->
          site.strata
              .single()
              .plotCounts(type)
              .copy(
                  mode = PlotSelectionMode.Direct,
                  plotPrecisionLevel = null,
                  numPermanentPlots = if (type == ObservationType.BaselineSurvey) 0 else 7,
                  numTemporaryPlots = if (type == ObservationType.BaselineSurvey) 10 else 3,
              )
        }
    val edits = directRequest.toEdits(site).getValue(entry.stratumId)
    assertEquals(
        expectedDirect,
        edits.mapValues { (type, edit) -> edit(site.strata.single().plotCounts(type)) },
        "PUT edits preserve monitoring and biomass counts and combine baseline counts",
    )
    assertEquals(
        mapOf(entry.stratumId to expectedDirect),
        directRequest.toSettings(site),
        "Preview uses the same all-types Direct allocation as PUT",
    )
    assertThrows<IllegalArgumentException> {
      directRequest
          .copy(strata = listOf(direct.copy(observationType = ObservationType.BaselineSurvey)))
          .toSettings(site)
    }
    listOf(
            direct.copy(numPermanentPlots = 0, numTemporaryPlots = 0),
            direct.copy(numPermanentPlots = -1),
            direct.copy(numTemporaryPlots = -1),
            direct.copy(numPermanentPlots = null),
            direct.copy(numTemporaryPlots = null),
        )
        .forEach { invalid ->
          assertThrows<IllegalArgumentException> {
            directRequest.copy(strata = listOf(invalid)).toSettings(site)
          }
        }
    val excessive = direct.copy(numPermanentPlots = 7000, numTemporaryPlots = 3001)
    assertThrows<IllegalArgumentException> {
      directRequest.copy(strata = listOf(excessive)).toSettings(site)
    }
    assertThrows<IllegalArgumentException> {
      directRequest.copy(strata = listOf(excessive)).toEdits(site).forEach { (id, edits) ->
        val stratum = site.strata.first { it.id == id }
        edits.forEach { (type, edit) -> edit(stratum.plotCounts(type)) }
      }
    }
    val boundary =
        UpdatePlotSettingsRequestPayload(
                PlotPrecisionLevel.High,
                listOf(
                    direct.copy(
                        numPermanentPlots = 7000,
                        numTemporaryPlots = 3000,
                    )
                ),
            )
            .toSettings(site)
            .getValue(entry.stratumId)
    assertEquals(
        10000,
        boundary.getValue(ObservationType.BaselineSurvey).numTemporaryPlots,
        "An all-types Direct edit accepts a baseline sum exactly at the limit",
    )
  }

  @Test
  fun `get plot settings payload includes placeable counts and nulls them without a map`() {
    val smallSite =
        existingSite(width = 60, height = 60) {
          stratum(plantNumPermanent = 6, plantNumTemporary = 2) {
            substratum {
              plot(x = 0, y = 0, permanentIndex = 1)
              plot(x = 30, y = 0, permanentIndex = 2)
              plot(x = 0, y = 30, permanentIndex = 3)
              plot(x = 30, y = 30, permanentIndex = 4)
            }
          }
        }
    val allObserved =
        smallSite.strata.single().substrata.single().monitoringPlots.map { it.id }.toSet()

    val monitoring =
        PlotSettingsResponsePayload(smallSite).strata.single().settings.single {
          it.observationType == ObservationType.Monitoring
        }
    assertEquals(3, monitoring.numPlaceablePermanentPlots, "Shortfall placeable permanent")
    assertEquals(1, monitoring.numPlaceableTemporaryPlots, "Shortfall placeable temporary")

    val monitoringObserved =
        PlotSettingsResponsePayload(smallSite, allObserved).strata.single().settings.single {
          it.observationType == ObservationType.Monitoring
        }
    assertEquals(4, monitoringObserved.numPlaceablePermanentPlots, "Observed plots stay permanent")
    assertEquals(0, monitoringObserved.numPlaceableTemporaryPlots, "No room for temporary plots")

    val unmapped =
        PlotSettingsResponsePayload(smallSite.copy(gridOrigin = null))
            .strata
            .single()
            .settings
            .single { it.observationType == ObservationType.Monitoring }
    assertNull(unmapped.numPlaceablePermanentPlots, "Unmapped placeable permanent")
    assertNull(unmapped.numPlaceableTemporaryPlots, "Unmapped placeable temporary")
  }
}
