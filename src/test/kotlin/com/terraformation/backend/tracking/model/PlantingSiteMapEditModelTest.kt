package com.terraformation.backend.tracking.model

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.StableId
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class PlantingSiteMapEditModelTest {
  @Test
  fun `existing strata and substrata keep their settings and new ones use defaults`() {
    val completedTime = Instant.ofEpochSecond(1000)
    val existing =
        existingSite(width = 1500) {
          stratum(name = "Old", width = 500, stableId = StableId("stable"), plantNumPermanent = 7) {
            plantErrorMargin = BigDecimal(40)
            plantStudentsT = BigDecimal(2)
            plantVariance = BigDecimal(20000)
            initialPlantingDensity = BigDecimal(1234)
            substratum(width = 250)
            substratum { plantingCompletedTime = completedTime }
          }
          stratum(name = "Dest", width = 500)
        }
    val renamedStratum = existing.strata[0]
    val destinationStratum = existing.strata[1]
    val movedSubstratum = renamedStratum.substrata[1]

    val desired =
        PlantingSiteMapEditModel.of(existing)
            .let { edit ->
              edit.copy(
                  strata =
                      listOf(
                          edit.strata[0].copy(
                              name = "New",
                              substrata = edit.strata[0].substrata.take(1),
                          ),
                          edit.strata[1].copy(
                              substrata =
                                  edit.strata[1].substrata +
                                      edit.strata[0].substrata[1].copy(name = "Moved")
                          ),
                          StratumMapEditModel(
                              null,
                              "Added",
                              listOf(SubstratumMapEditModel(null, "Sub", rectangle(500, x = 1000))),
                          ),
                      )
              )
            }
            .toDesiredModel(existing)

    val expected =
        listOf(
            renamedStratum.toNew(
                areaHa = BigDecimal("12.500"),
                boundary = renamedStratum.substrata[0].boundary,
                name = "New",
                substrata =
                    listOf(
                        renamedStratum.substrata[0]
                            .toNew()
                            .copy(fullName = "New-${renamedStratum.substrata[0].name}")
                    ),
            ),
            destinationStratum.toNew(
                // Merging the substrata adds vertices, so check the merged boundary via its area.
                areaHa = BigDecimal("37.500"),
                boundary = desired.strata[1].boundary,
                substrata =
                    destinationStratum.substrata.map { it.toNew() } +
                        movedSubstratum.toNew().copy(fullName = "Dest-Moved", name = "Moved"),
            ),
            StratumModel.create(
                boundary = rectangle(500, x = 1000),
                name = "Added",
                substrata =
                    listOf(
                        SubstratumModel.create(
                            boundary = rectangle(500, x = 1000),
                            fullName = "Added-Sub",
                            name = "Sub",
                        )
                    ),
            ),
        )

    assertEquals(expected, desired.strata)
  }

  @Test
  fun `site boundary is union of strata for sites with strata`() {
    val existing = existingSite(width = 1000) { stratum(width = 500) }

    val desired =
        PlantingSiteMapEditModel.of(existing)
            .copy(boundary = rectangle(5000))
            .let { it.copy(strata = it.strata.take(1)) }
            .toDesiredModel(existing)

    assertGeometryEquals(rectangle(500), desired.boundary)
  }

  @Test
  fun `site boundary and exclusion come from edit for simple sites`() {
    val existing = existingSite().copy(strata = emptyList())
    val exclusion = rectangle(10)

    val desired =
        PlantingSiteMapEditModel(boundary = rectangle(800), exclusion = exclusion, emptyList())
            .toDesiredModel(existing)

    assertAll(
        { assertGeometryEquals(rectangle(800), desired.boundary, "Boundary") },
        { assertGeometryEquals(exclusion, desired.exclusion, "Exclusion") },
        { assertEquals(emptyList<NewStratumModel>(), desired.strata, "Strata") },
    )
  }

  @Nested
  inner class Validation {
    @Test
    fun `throws exception if simple site boundary is removed`() {
      val existing = existingSite().copy(strata = emptyList())

      assertThrows<IllegalArgumentException> {
        PlantingSiteMapEditModel(null, null, emptyList()).toDesiredModel(existing)
      }
    }
  }
}
