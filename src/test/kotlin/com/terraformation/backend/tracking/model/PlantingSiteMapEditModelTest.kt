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
  fun `existing strata keep their stable IDs and settings when renamed`() {
    val completedTime = Instant.ofEpochSecond(1000)
    val existing = existingSite {
      stratum(name = "Old", stableId = StableId("stable"), plantNumPermanent = 7) {
        plantErrorMargin = BigDecimal(40)
        plantStudentsT = BigDecimal(2)
        plantVariance = BigDecimal(20000)
        initialPlantingDensity = BigDecimal(1234)
        substratum { plantingCompletedTime = completedTime }
      }
    }
    val existingStratum = existing.strata[0]

    val desired =
        existing
            .toMapEdit()
            .let { it.copy(strata = listOf(it.strata[0].copy(name = "New"))) }
            .toDesiredModel(existing)

    val expected =
        existingStratum
            .toNew()
            .copy(
                name = "New",
                substrata =
                    existingStratum.substrata.map { it.toNew().copy(fullName = "New-${it.name}") },
            )

    assertEquals(expected, desired.strata[0])
  }

  @Test
  fun `new strata use default settings`() {
    val existing = existingSite(width = 1000) { stratum(width = 500, plantNumPermanent = 10) }

    val desired =
        existing
            .toMapEdit()
            .let {
              it.copy(
                  strata =
                      it.strata +
                          StratumMapEditModel(
                              null,
                              "New",
                              listOf(SubstratumMapEditModel(null, "Sub", rectangle(500, x = 500))),
                          )
              )
            }
            .toDesiredModel(existing)

    val expected =
        StratumModel.create(
            boundary = rectangle(500, x = 500),
            name = "New",
            substrata =
                listOf(
                    SubstratumModel.create(
                        boundary = rectangle(500, x = 500),
                        fullName = "New-Sub",
                        name = "Sub",
                    )
                ),
        )

    assertEquals(expected, desired.strata[1])
  }

  @Test
  fun `substratum moved to another stratum keeps its stable ID and planting completion`() {
    val completedTime = Instant.ofEpochSecond(1000)
    val existing =
        existingSite(width = 1000) {
          stratum(width = 500) {
            substratum(width = 250)
            substratum { plantingCompletedTime = completedTime }
          }
          stratum()
        }
    val movedSubstratum = existing.strata[0].substrata[1]
    val destinationStratum = existing.strata[1]

    val desired =
        existing
            .toMapEdit()
            .let { edit ->
              edit.copy(
                  strata =
                      listOf(
                          edit.strata[0].copy(substrata = edit.strata[0].substrata.take(1)),
                          edit.strata[1].copy(
                              substrata =
                                  edit.strata[1].substrata +
                                      edit.strata[0].substrata[1].copy(name = "Moved")
                          ),
                      )
              )
            }
            .toDesiredModel(existing)

    val expectedSubstratum =
        movedSubstratum
            .toNew()
            .copy(fullName = "${destinationStratum.name}-Moved", name = "Moved")

    assertAll(
        { assertEquals(expectedSubstratum, desired.strata[1].substrata[1]) },
        {
          assertEquals(
              BigDecimal("37.500"),
              desired.strata[1].areaHa,
              "Stratum area includes moved substratum",
          )
        },
    )
  }

  @Test
  fun `site boundary is union of strata for sites with strata`() {
    val existing = existingSite(width = 1000) { stratum(width = 500) }

    val desired =
        existing
            .toMapEdit()
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
