package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.rectangle
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DraftPlantingSiteModelTest {
  @Test
  fun `validate returns null for a valid detailed draft`() {
    assertNull(
        draftSite(strata = listOf(draftStratum("A", 250), draftStratum("B", 250, x = 250)))
            .validate()
    )
  }

  @Test
  fun `validate reports overlapping strata`() {
    val problems =
        draftSite(strata = listOf(draftStratum("A", 300), draftStratum("B", 300, x = 200)))
            .validate()

    assertNotNull(problems, "Validation returned no problems")
    assertEquals(
        true,
        problems!!.contains(PlantingSiteValidationFailure.stratumBoundaryOverlaps(setOf("B"), "A")),
        "Expected problems to contain overlap failure but got $problems",
    )
  }

  @Test
  fun `validate returns null for a simple draft with only a boundary`() {
    assertNull(draftSite(siteType = DraftPlantingSiteType.Simple, strata = emptyList()).validate())
  }

  @Test
  fun `numStrata and numSubstrata count rows`() {
    val model =
        draftSite(
            strata =
                listOf(
                    draftStratum(
                        "A",
                        250,
                        substrata = listOf(draftSubstratum("1", 125), draftSubstratum("2", 125)),
                    ),
                    draftStratum(
                        "B",
                        250,
                        x = 250,
                        substrata = listOf(draftSubstratum("3", 250, x = 250)),
                    ),
                )
        )

    assertEquals(2, model.numStrata, "numStrata")
    assertEquals(3, model.numSubstrata, "numSubstrata")
  }

  private fun draftSubstratum(name: String, width: Int, x: Int = 0) =
      DraftSubstratumModel(null, name, rectangle(width = width, height = 500, x = x))

  private fun draftStratum(
      name: String,
      width: Int,
      x: Int = 0,
      substrata: List<DraftSubstratumModel> = listOf(draftSubstratum("1", width, x)),
  ) =
      DraftStratumModel(
          id = null,
          name = name,
          boundary = rectangle(width = width, height = 500, x = x),
          initialPlantingDensity = BigDecimal(1500),
          substrata = substrata,
      )

  private fun draftSite(
      siteType: DraftPlantingSiteType = DraftPlantingSiteType.Detailed,
      strata: List<DraftStratumModel>,
  ) =
      DraftPlantingSiteModel(
          id = null,
          organizationId = OrganizationId(1),
          projectId = null,
          name = "Draft",
          description = null,
          timeZone = null,
          map =
              DraftPlantingSiteMap(
                  siteType = siteType,
                  siteEditStep = null,
                  boundarySource = null,
                  boundary = rectangle(width = 500, height = 500),
                  exclusion = null,
                  strata = strata,
              ),
      )
}
