package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.tracking.model.PlaceablePlotCounts
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
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
  fun `get plot settings payload copies placeable counts and nulls missing entries`() {
    val stratum = site.strata.single()
    val expectedBase =
        PlotSelectionSettingPayload(ObservationType.Monitoring, stratum.plantPlotCounts)

    assertEquals(
        expectedBase.copy(numPlaceablePermanentPlots = 3, numPlaceableTemporaryPlots = 1),
        PlotSettingsResponsePayload(
                site,
                mapOf(stratum.id to mapOf(ObservationType.Monitoring to PlaceablePlotCounts(3, 1))),
            )
            .strata
            .single()
            .settings
            .single { it.observationType == ObservationType.Monitoring },
        "Provided placeable counts are copied onto the setting payload",
    )
    assertEquals(
        expectedBase,
        PlotSettingsResponsePayload(site).strata.single().settings.single {
          it.observationType == ObservationType.Monitoring
        },
        "Missing placeable counts stay null",
    )
  }
}
