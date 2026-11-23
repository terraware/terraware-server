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
  fun `PUT and preview reject resulting counts above the limit for explicit observation types`() {
    val direct =
        entry.copy(mode = PlotSelectionMode.Direct, numPermanentPlots = 7, numTemporaryPlots = 3)
    val invalidEntries =
        listOf(
            direct.copy(numPermanentPlots = 10001),
            direct.copy(numTemporaryPlots = 10001),
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
  }
}
