package com.terraformation.backend.tracking.model

import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlantingSiteModelTest {
  @Nested
  inner class Validate {
    @Test
    fun `checks for maximum envelope area`() {
      val site = newSite(width = 200000, height = 100000)

      assertHasProblem(site, PlantingSiteValidationFailure.siteTooLarge())
    }

    @Test
    fun `checks for duplicate zone names`() {
      val site = newSite {
        zone(width = 250, name = "Duplicate")
        zone(width = 250, name = "Duplicate")
      }

      assertHasProblem(site, PlantingSiteValidationFailure.duplicateStratumName("Duplicate"))
    }

    @Test
    fun `checks for zones not covered by site`() {
      val site = newSite(width = 100, height = 100) { zone(width = 200, height = 200) }

      assertHasProblem(site, PlantingSiteValidationFailure.stratumNotInSite("Z1"))
    }

    @Test
    fun `checks for overlapping zone boundaries`() {
      val site =
          newSite(width = 200) {
            zone(width = 100)
            zone(x = 50, width = 150)
          }

      assertHasProblem(
          site,
          PlantingSiteValidationFailure.stratumBoundaryOverlaps(setOf("Z2"), "Z1"),
      )
    }

    @Test
    fun `checks that zones have subzones`() {
      val site = newSite()
      val siteWithoutSubzones =
          site.copy(strata = site.strata.map { it.copy(substrata = emptyList()) })

      assertHasProblem(
          siteWithoutSubzones,
          PlantingSiteValidationFailure.stratumHasNoSubstrata("Z1"),
      )
    }

    @Test
    fun `allows zone that is only big enough for two plots`() {
      val site = newSite(width = MONITORING_PLOT_SIZE_INT, height = MONITORING_PLOT_SIZE_INT * 2)

      assertHasNoProblems(site)
    }

    @Test
    fun `checks for subzones not covered by zone`() {
      val site = newSite(width = 100, height = 100) { zone { subzone(width = 200, height = 200) } }

      assertHasProblem(site, PlantingSiteValidationFailure.substratumNotInStratum("S1", "Z1"))
    }

    @Test
    fun `checks for overlapping subzone boundaries`() {
      val site =
          newSite(width = 200) {
            zone {
              subzone(width = 100)
              subzone(x = 50, width = 150)
            }
          }

      assertHasProblem(
          site,
          PlantingSiteValidationFailure.substratumBoundaryOverlaps(setOf("S2"), "S1", "Z1"),
      )
    }

    @Test
    fun `checks that no subzones are completely covered by exclusion area`() {
      val site = newSite {
        exclusion = rectangle(width = 200, height = 500)
        zone {
          subzone(width = 150)
          subzone()
        }
      }

      assertHasProblem(site, PlantingSiteValidationFailure.substratumInExclusionArea("S1", "Z1"))
    }

    @Test
    fun `checks that zone is big enough for a permanent plot and a temporary plot`() {
      assertHasProblem(
          newSite(width = MONITORING_PLOT_SIZE_INT * 2 - 1, height = MONITORING_PLOT_SIZE_INT),
          PlantingSiteValidationFailure.stratumTooSmall("Z1"),
          "Site is big enough for permanent plot but not also for temporary plot",
      )
    }

    @Test
    fun `checks that site has boundary if it has exclusion`() {
      assertHasProblem(
          newSite().copy(boundary = null, exclusion = rectangle(1)),
          PlantingSiteValidationFailure.exclusionWithoutBoundary(),
      )
    }

    @Test
    fun `checks that site has boundary if it has zones`() {
      assertHasProblem(
          newSite().copy(boundary = null),
          PlantingSiteValidationFailure.strataWithoutSiteBoundary(),
      )
    }

    private fun assertHasProblem(
        site: AnyPlantingSiteModel,
        problem: PlantingSiteValidationFailure,
        message: String = "Expected problems list to contain entry",
    ) {
      val problems = site.validate()
      assertNotNull(problems, "Validation returned no problems")

      if (problems!!.none { it == problem }) {
        assertEquals(listOf(problem), problems, message)
      }
    }

    private fun assertHasNoProblems(site: AnyPlantingSiteModel) {
      val problems = site.validate()

      assertNull(problems, "Validation returned problems")
    }
  }
}
