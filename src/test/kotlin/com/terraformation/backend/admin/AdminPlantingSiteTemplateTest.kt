package com.terraformation.backend.admin

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.thymeleaf.TemplateSpec
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

class AdminPlantingSiteTemplateTest {
  private val engine by lazy {
    SpringTemplateEngine().apply {
      setTemplateResolver(
          ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
          }
      )
    }
  }

  @Test
  fun `renders editable baseline settings with disabled zero permanent count`() {
    val site = existingSite { stratum(baselineNumPermanent = 0, baselineNumTemporary = 10) }

    val html = render(site)

    assertThat(html).contains("Baseline Survey", "rowspan=\"3\"")
    assertThat(Regex("<input[^>]*name=\"baselineNumPermanent\"[^>]*>").find(html)?.value)
        .contains("disabled", "value=\"0\"")
    for (field in listOf("Variance", "ErrorMargin", "StudentsT", "NumTemporary")) {
      assertThat(Regex("<input[^>]*name=\"baseline$field\"[^>]*>").find(html)?.value)
          .contains("form=\"stratum-${site.strata.single().id}\"", "required")
          .doesNotContain("disabled")
    }
    assertThat(html).contains("id=\"baselineTotalPlots-${site.strata.single().id}\"")
    Path.of("build/test-results/admin-plantingSite.html").writeText(html)
  }

  @Test
  fun `renders empty stratum settings without baseline inputs`() {
    val html = render(existingSite {}.copy(strata = emptyList()))

    assertThat(html).doesNotContain("name=\"baselineNumTemporary\"")
  }

  @Test
  fun `labels baseline observations without offering baseline scheduling`() {
    val html = render(existingSite {})

    assertThat(html).contains("<td>Baseline</td>", "<td>Biomass</td>", "<td>Plant</td>")
    assertThat(Regex("<select[^>]*name=\"observationType\"[\\s\\S]*?</select>").find(html)?.value)
        .contains("value=\"Biomass Measurements\"", "value=\"Monitoring\"")
        .doesNotContain("Baseline")
  }

  private fun render(site: ExistingPlantingSiteModel): String {
    val observations =
        ObservationType.entries.mapIndexed { index, type ->
          ExistingObservationModel(
              endDate = LocalDate.of(2026, 11, 30),
              id = ObservationId(index.toLong() + 1),
              isAdHoc = false,
              observationType = type,
              plantingSiteId = site.id,
              startDate = LocalDate.of(2026, 11, 1),
              state = ObservationState.Upcoming,
          )
        }
    val context =
        Context(
            Locale.ENGLISH,
            mapOf(
                "site" to site,
                "observations" to observations,
                "canCreateObservation" to true,
                "canStartObservations" to emptyMap<ObservationId, Boolean>(),
                "observationMessages" to emptyMap<ObservationId, String>(),
                "substrataById" to emptyMap<Any, Any>(),
                "reportedPlants" to mapOf("totalPlants" to 0, "totalSpecies" to 0),
            ),
        )

    return engine.process(
        TemplateSpec(
            "admin/plantingSite",
            setOf("additionalScript", "additionalStyle", "#plotCounts", "#observations"),
            TemplateMode.HTML,
            null,
        ),
        context,
    )
  }
}
