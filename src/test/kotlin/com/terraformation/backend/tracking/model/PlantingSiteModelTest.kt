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

      assertHasProblem(site, PlantingSiteValidationFailure.duplicateZoneName("Duplicate"))
    }

    @Test
    fun `checks for zones not covered by site`() {
      val site = newSite(width = 100, height = 100) { zone(width = 200, height = 200) }

      assertHasProblem(site, PlantingSiteValidationFailure.zoneNotInSite("Z1"))
    }

    @Test
    fun `checks for overlapping zone boundaries`() {
      val site =
          newSite(width = 200) {
            zone(width = 100)
            zone(x = 50, width = 150)
          }

      assertHasProblem(site, PlantingSiteValidationFailure.zoneBoundaryOverlaps(setOf("Z2"), "Z1"))
    }

    @Test
    fun `checks that zones have subzones`() {
      val site = newSite()
      val siteWithoutSubzones =
          site.copy(
              plantingZones = site.plantingZones.map { it.copy(plantingSubzones = emptyList()) })

      assertHasProblem(siteWithoutSubzones, PlantingSiteValidationFailure.zoneHasNoSubzones("Z1"))
    }

    @Test
    fun `checks that zones are big enough for observations`() {
      val site = newSite(width = 50, height = 50)

      assertHasProblem(site, PlantingSiteValidationFailure.zoneTooSmall("Z1"))
    }

    @Test
    fun `checks for subzones not covered by zone`() {
      val site = newSite(width = 100, height = 100) { zone { subzone(width = 200, height = 200) } }

      assertHasProblem(site, PlantingSiteValidationFailure.subzoneNotInZone("S1", "Z1"))
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
          site, PlantingSiteValidationFailure.subzoneBoundaryOverlaps(setOf("S2"), "S1", "Z1"))
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

      assertHasProblem(site, PlantingSiteValidationFailure.subzoneInExclusionArea("S1", "Z1"))
    }

    @Test
    fun `checks that zone is big enough for a permanent cluster and a temporary plot`() {
      assertHasProblem(
          newSite(width = 51, height = 50),
          PlantingSiteValidationFailure.zoneTooSmall("Z1"),
          "Site is big enough for permanent cluster but not also for temporary plot")

      assertHasProblem(
          newSite(width = 500, height = 30),
          PlantingSiteValidationFailure.zoneTooSmall("Z1"),
          "Site is big enough for 5 plots but not a permanent cluster")
    }

    private fun assertHasProblem(
        site: PlantingSiteModel<*, *, *>,
        problem: PlantingSiteValidationFailure,
        message: String = "Expected problems list to contain entry",
    ) {
      val problems = site.validate()
      assertNotNull(problems, "Validation returned no problems")

      if (problems!!.none { it == problem }) {
        assertEquals(listOf(problem), problems, message)
      }
    }
  }
}
