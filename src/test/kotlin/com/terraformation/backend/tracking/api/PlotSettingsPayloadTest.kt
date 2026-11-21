package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.model.ConfidenceLevel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import com.terraformation.backend.tracking.model.PlotSelectionSetting
import com.terraformation.backend.tracking.model.StratumPlotCounts
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
  fun `converts mode specific inputs and rejects missing or invalid fields`() {
    assertEquals(
        PlotSelectionSetting.Custom(ConfidenceLevel.Eighty, BigDecimal("25"), BigDecimal("5")),
        entry.toSetting(),
        "converts mode specific inputs and rejects missing or invalid fields",
    )
    listOf(
            entry.copy(confidenceLevel = null),
            entry.copy(confidenceLevel = 85),
            entry.copy(targetMarginOfError = null),
            entry.copy(variability = null),
            entry.copy(mode = PlotSelectionMode.PrecisionLevel),
            entry.copy(mode = PlotSelectionMode.Direct, numPermanentPlots = 1),
            entry.copy(mode = PlotSelectionMode.Direct, numTemporaryPlots = 1),
        )
        .forEach { invalid -> assertThrows<IllegalArgumentException> { invalid.toSetting() } }
  }

  @Test
  fun `converts stratum edits and rejects duplicate pairs and unknown strata`() {
    val request = UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High, listOf(entry))
    val expected =
        site.strata
            .single()
            .plantPlotCounts
            .copy(
                mode = PlotSelectionMode.Custom,
                plotPrecisionLevel = null,
                studentsT = BigDecimal("1.282"),
                errorMargin = BigDecimal("25"),
                variance = BigDecimal("25"),
                numPermanentPlots = 1,
                numTemporaryPlots = 1,
            )
    assertEquals(
        mapOf(entry.stratumId to mapOf(ObservationType.Monitoring to expected)),
        request.toSettings(site),
        "converts stratum edits and rejects duplicate pairs and unknown strata",
    )
    assertEquals(
        emptyMap<StratumId, Any>(),
        UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High).toSettings(site),
        "converts stratum edits and rejects duplicate pairs and unknown strata",
    )
    assertThrows<IllegalArgumentException> {
      request.copy(strata = listOf(entry, entry)).toSettings(site)
    }
    assertThrows<StratumNotFoundException> {
      request.copy(strata = listOf(entry.copy(stratumId = StratumId(-1)))).toSettings(site)
    }
    assertThrows<IllegalArgumentException> {
      request.copy(strata = listOf(entry.copy(variability = BigDecimal.ZERO))).toSettings(site)
    }
  }

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
  }

  @Test
  fun `all-types direct edits allocate baseline plots as temporary plots`() {
    val direct =
        entry.copy(
            observationType = null,
            mode = PlotSelectionMode.Direct,
            numPermanentPlots = 7,
            numTemporaryPlots = 3,
        )
    val request = UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High, listOf(direct))
    val expected =
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
    val edits = request.toEdits(site).getValue(entry.stratumId)
    assertEquals(
        expected,
        edits.mapValues { (type, edit) -> edit(site.strata.single().plotCounts(type)) },
        "PUT edits preserve monitoring and biomass counts and combine baseline counts",
    )
    assertEquals(
        mapOf(entry.stratumId to expected),
        request.toSettings(site),
        "Preview uses the same all-types Direct allocation as PUT",
    )
    assertThrows<IllegalArgumentException> {
      request
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
            request.copy(strata = listOf(invalid)).toSettings(site)
          }
        }
  }

  @Test
  fun `direct edit preserves values supplied after request conversion`() {
    val direct =
        entry.copy(mode = PlotSelectionMode.Direct, numPermanentPlots = 7, numTemporaryPlots = 2)
    val edit =
        UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High, listOf(direct))
            .toEdits(site)
            .getValue(entry.stratumId)
            .getValue(ObservationType.Monitoring)
    val latest =
        StratumPlotCounts(
            variance = BigDecimal("75"),
            studentsT = BigDecimal("1.96"),
            errorMargin = BigDecimal("9.8"),
        )
    assertEquals(
        latest.copy(
            mode = PlotSelectionMode.Direct,
            plotPrecisionLevel = null,
            numPermanentPlots = 7,
            numTemporaryPlots = 2,
        ),
        edit(latest),
        "direct edit preserves values supplied after request conversion",
    )
  }

  @Test
  fun `PUT and preview reject resulting counts above the limit including all-types baseline sums`() {
    val direct =
        entry.copy(mode = PlotSelectionMode.Direct, numPermanentPlots = 7, numTemporaryPlots = 3)
    val invalidEntries =
        listOf(
            direct.copy(numPermanentPlots = 10001),
            direct.copy(numTemporaryPlots = 10001),
            direct.copy(observationType = null, numPermanentPlots = 7000, numTemporaryPlots = 3001),
            entry.copy(
                observationType = ObservationType.BaselineSurvey,
                confidenceLevel = 99,
                targetMarginOfError = BigDecimal("5"),
                variability = BigDecimal("200"),
            ),
        )
    invalidEntries.forEach { invalid ->
      val request = UpdatePlotSettingsRequestPayload(PlotPrecisionLevel.High, listOf(invalid))
      assertThrows<IllegalArgumentException> { request.toSettings(site) }
      assertThrows<IllegalArgumentException> {
        request.toEdits(site).forEach { (id, edits) ->
          val stratum = site.strata.first { it.id == id }
          edits.forEach { (type, edit) -> edit(stratum.plotCounts(type)) }
        }
      }
    }
    val boundary =
        UpdatePlotSettingsRequestPayload(
                PlotPrecisionLevel.High,
                listOf(
                    direct.copy(
                        observationType = null,
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
  fun `response maps all observation types and rounds only variability`() {
    val counts =
        StratumPlotCounts(
            mode = PlotSelectionMode.Custom,
            studentsT = BigDecimal("1.6450"),
            errorMargin = BigDecimal("15"),
            variance = BigDecimal("909.0225"),
            numPermanentPlots = 8,
            numTemporaryPlots = 3,
        )
    val stratum =
        site.strata
            .single()
            .copy(
                plantPlotCounts = counts,
                biomassPlotCounts = counts.copy(studentsT = BigDecimal("1.5")),
                baselinePlotCounts = counts.copy(numPermanentPlots = 0, numTemporaryPlots = 1),
            )
    val expected =
        PlotSelectionSettingPayload(
            ObservationType.Monitoring,
            PlotSelectionMode.Custom,
            null,
            90,
            BigDecimal("15"),
            BigDecimal("30.2"),
            8,
            3,
            ConfidenceLevel.Ninety.studentsTForPlotCount(11)!! * 30.15 / kotlin.math.sqrt(11.0),
        )
    val expectedStratum =
        StratumPlotSettingsPayload(
            stratum.id,
            stratum.name,
            listOf(
                expected,
                expected.copy(
                    observationType = ObservationType.BiomassMeasurements,
                    confidenceLevel = null,
                    expectedPrecision = null,
                ),
                expected.copy(
                    observationType = ObservationType.BaselineSurvey,
                    numPermanentPlots = 0,
                    numTemporaryPlots = 1,
                    expectedPrecision = null,
                ),
            ),
        )
    assertEquals(
        PlotSettingsResponsePayload(PlotPrecisionLevel.High, listOf(expectedStratum)),
        PlotSettingsResponsePayload(
            site.copy(defaultPlotPrecisionLevel = PlotPrecisionLevel.High, strata = listOf(stratum))
        ),
        "response maps all observation types and rounds only variability",
    )
  }
}
