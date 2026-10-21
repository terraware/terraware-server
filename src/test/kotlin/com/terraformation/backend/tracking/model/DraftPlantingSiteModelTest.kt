package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.DraftSubstratumId
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.DraftStratumNotFoundException
import com.terraformation.backend.tracking.db.DraftSubstratumNotFoundException
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

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

    assertEquals(
        listOf(PlantingSiteValidationFailure.stratumBoundaryOverlaps(setOf("B"), "A")),
        problems,
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

    assertEquals(2 to 3, model.numStrata to model.numSubstrata, "numStrata to numSubstrata")
  }

  @Nested
  inner class RequireValid {
    private val existing =
        draftMap(
            listOf(
                draftStratum(
                    "A",
                    250,
                    id = DraftStratumId(1),
                    substrata = listOf(draftSubstratum("1", 250, id = DraftSubstratumId(10))),
                ),
                draftStratum(
                    "B",
                    250,
                    x = 250,
                    id = DraftStratumId(2),
                    substrata =
                        listOf(draftSubstratum("2", 250, x = 250, id = DraftSubstratumId(20))),
                ),
            )
        )

    @Test
    fun `accepts new map without ids`() {
      assertDoesNotThrow {
        draftMap(listOf(draftStratum("A", 250), draftStratum("B", 250, x = 250))).requireValid(null)
      }
    }

    @Test
    fun `accepts existing ids in their original places`() {
      assertDoesNotThrow { existing.requireValid(existing) }
    }

    @Test
    fun `rejects strata on simple site`() {
      assertThrows<IllegalArgumentException> {
        draftMap(listOf(draftStratum("A", 250)), DraftPlantingSiteType.Simple).requireValid(null)
      }
    }

    @Test
    fun `rejects changing site type`() {
      assertThrows<IllegalArgumentException> {
        draftMap(emptyList(), DraftPlantingSiteType.Simple)
            .requireValid(draftMap(emptyList(), DraftPlantingSiteType.Detailed))
      }
    }

    @Test
    fun `rejects duplicate stratum names`() {
      assertThrows<IllegalArgumentException> {
        draftMap(listOf(draftStratum("A", 250), draftStratum("A", 250, x = 250))).requireValid(null)
      }
    }

    @Test
    fun `rejects duplicate substratum names in a stratum`() {
      assertThrows<IllegalArgumentException> {
        draftMap(
                listOf(
                    draftStratum(
                        "A",
                        250,
                        substrata = listOf(draftSubstratum("1", 125), draftSubstratum("1", 125)),
                    )
                )
            )
            .requireValid(null)
      }
    }

    @Test
    fun `rejects unknown stratum id`() {
      assertThrows<DraftStratumNotFoundException> {
        draftMap(listOf(draftStratum("A", 250, id = DraftStratumId(1)))).requireValid(null)
      }
    }

    @Test
    fun `rejects repeated stratum id`() {
      val stratum = existing.strata[0]

      assertThrows<IllegalArgumentException> {
        existing.copy(strata = listOf(stratum, stratum.copy(name = "C"))).requireValid(existing)
      }
    }

    @Test
    fun `rejects substratum id from another stratum`() {
      val strata = existing.strata

      assertThrows<DraftSubstratumNotFoundException> {
        existing
            .copy(strata = listOf(strata[0].copy(substrata = strata[1].substrata), strata[1]))
            .requireValid(existing)
      }
    }

    @Test
    fun `rejects repeated substratum id`() {
      val stratum = existing.strata[0]
      val substratum = stratum.substrata[0]

      assertThrows<IllegalArgumentException> {
        existing
            .copy(
                strata =
                    listOf(
                        stratum.copy(substrata = listOf(substratum, substratum.copy(name = "3"))),
                        existing.strata[1],
                    )
            )
            .requireValid(existing)
      }
    }
  }

  private fun draftSubstratum(
      name: String,
      width: Int,
      x: Int = 0,
      id: DraftSubstratumId? = null,
  ) = DraftSubstratumModel(id, name, rectangle(width = width, height = 500, x = x))

  private fun draftStratum(
      name: String,
      width: Int,
      x: Int = 0,
      id: DraftStratumId? = null,
      substrata: List<DraftSubstratumModel> = listOf(draftSubstratum("1", width, x)),
  ) =
      DraftStratumModel(
          id = id,
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
          map = draftMap(strata, siteType),
      )

  private fun draftMap(
      strata: List<DraftStratumModel>,
      siteType: DraftPlantingSiteType = DraftPlantingSiteType.Detailed,
  ) =
      DraftPlantingSiteMap(
          siteType = siteType,
          siteEditStep = null,
          boundarySource = null,
          boundary = rectangle(width = 500, height = 500),
          exclusion = null,
          strata = strata,
      )
}
