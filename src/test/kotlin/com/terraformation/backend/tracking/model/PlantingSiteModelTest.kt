package com.terraformation.backend.tracking.model

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

      assertHasProblem(site, "Site must be contained within an envelope.*actual envelope")
    }

    @Test
    fun `checks for duplicate zone names`() {
      val site = newSite {
        zone(width = 250, name = "Duplicate")
        zone(width = 250, name = "Duplicate")
      }

      assertHasProblem(site, "Zone name Duplicate appears 2 times")
    }

    @Test
    fun `checks for zones not covered by site`() {
      val site = newSite(width = 100, height = 100) { zone(width = 200, height = 200) }

      assertHasProblem(site, "75\\.00% of planting zone .* is not contained")
    }

    @Test
    fun `checks for overlapping zone boundaries`() {
      val site =
          newSite(width = 200) {
            zone(width = 100)
            zone(x = 50, width = 150)
          }

      assertHasProblem(site, "50\\.00% of planting zone Z1 overlaps with zone Z2")
    }

    @Test
    fun `checks that zones have subzones`() {
      val site = newSite()
      val siteWithoutSubzones =
          site.copy(
              plantingZones = site.plantingZones.map { it.copy(plantingSubzones = emptyList()) })

      assertHasProblem(siteWithoutSubzones, "Planting zone Z1 has no subzones")
    }

    @Test
    fun `checks that zones are big enough for observations`() {
      val site = newSite(width = 50, height = 50)

      assertHasProblem(site, "Planting zone Z1 is too small")
    }

    @Test
    fun `checks for subzones not covered by zone`() {
      val site = newSite(width = 100, height = 100) { zone { subzone(width = 200, height = 200) } }

      assertHasProblem(site, "75\\.00% of planting subzone .* is not contained")
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

      assertHasProblem(site, "50\\.00% of subzone S1 in zone Z1 overlaps with subzone S2")
    }

    private fun assertHasProblem(site: PlantingSiteModel<*, *, *>, problemRegex: String) {
      val regex = Regex(problemRegex)

      val problems = site.validate()
      assertNotNull(problems, "Validation returned no problems")

      if (problems!!.none { it.contains(regex) }) {
        assertEquals(listOf(problemRegex), problems, "Expected problems list to contain entry")
      }
    }
  }
}
