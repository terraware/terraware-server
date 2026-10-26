package com.terraformation.backend.tracking.model

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.db.SubstratumNotFoundException
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.edit.StratumEdit
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
  fun `unchanged map results in no-op edit`() {
    // No permanent plots, so the edit calculator doesn't add plots to fill out the strata.
    val existing =
        existingSite(width = 1000) {
          stratum(width = 600, plantNumPermanent = 0, plantNumTemporary = 3) {
            plantErrorMargin = BigDecimal(50)
            plantVariance = BigDecimal(30000)
            initialPlantingDensity = BigDecimal(1500)
            substratum(width = 200) { plantingCompletedTime = Instant.ofEpochSecond(1000) }
            substratum()
          }
          stratum(plantNumPermanent = 0, plantNumTemporary = 4)
        }

    val desired = PlantingSiteMapEditModel.of(existing).toDesiredModel(existing)
    val edit = PlantingSiteEditCalculator(existing, desired).calculateSiteEdit()

    assertEquals(emptyList<StratumEdit>(), edit.stratumEdits.filterNot { it.isNoOp() })
  }

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
  fun `new strata and substrata get stable IDs that do not collide with existing ones`() {
    val existing = existingSite(width = 1000) { stratum(name = "A", width = 500) }

    val desired =
        PlantingSiteMapEditModel(
                boundary = null,
                exclusion = null,
                strata =
                    listOf(
                        // Replaces the existing stratum with a new one of the same name.
                        StratumMapEditModel(
                            id = null,
                            name = "A",
                            substrata =
                                listOf(SubstratumMapEditModel(null, "Sub1", rectangle(500))),
                        ),
                        StratumMapEditModel(
                            id = null,
                            name = "A-2",
                            substrata =
                                listOf(SubstratumMapEditModel(null, "X", rectangle(500, x = 500))),
                        ),
                    ),
            )
            .toDesiredModel(existing)

    val expected =
        listOf(
            StratumModel.create(
                boundary = rectangle(500),
                name = "A",
                stableId = StableId("A-2"),
                substrata =
                    listOf(
                        SubstratumModel.create(
                            boundary = rectangle(500),
                            fullName = "A-Sub1",
                            name = "Sub1",
                            stableId = StableId("A-Sub1-2"),
                        )
                    ),
            ),
            StratumModel.create(
                boundary = rectangle(500, x = 500),
                name = "A-2",
                stableId = StableId("A-2-2"),
                substrata =
                    listOf(
                        SubstratumModel.create(
                            boundary = rectangle(500, x = 500),
                            fullName = "A-2-X",
                            name = "X",
                            stableId = StableId("A-2-X"),
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

    @Test
    fun `throws exception if all strata are removed`() {
      val existing = existingSite()

      assertThrows<IllegalArgumentException> {
        PlantingSiteMapEditModel(rectangle(500), null, emptyList()).toDesiredModel(existing)
      }
    }

    @Test
    fun `throws exception if stratum has no substrata`() {
      val existing = existingSite()

      val edit =
          PlantingSiteMapEditModel.of(existing).let { edit ->
            edit.copy(strata = listOf(edit.strata[0].copy(substrata = emptyList())))
          }

      val exception =
          assertThrows<PlantingSiteMapInvalidException> { edit.toDesiredModel(existing) }
      assertEquals(
          listOf(PlantingSiteValidationFailure.stratumHasNoSubstrata("S1")),
          exception.problems,
      )
    }

    @Test
    fun `throws exception if stratum ID is not from the site`() {
      val existing = existingSite()

      val edit =
          PlantingSiteMapEditModel.of(existing).let { edit ->
            edit.copy(strata = listOf(edit.strata[0].copy(id = StratumId(1000))))
          }

      assertThrows<StratumNotFoundException> { edit.toDesiredModel(existing) }
    }

    @Test
    fun `throws exception if substratum ID is not from the site`() {
      val existing = existingSite()

      val edit =
          PlantingSiteMapEditModel.of(existing).let { edit ->
            edit.copy(
                strata =
                    listOf(
                        edit.strata[0].copy(
                            substrata =
                                listOf(edit.strata[0].substrata[0].copy(id = SubstratumId(1000)))
                        )
                    )
            )
          }

      assertThrows<SubstratumNotFoundException> { edit.toDesiredModel(existing) }
    }

    @Test
    fun `throws exception if stratum ID appears more than once`() {
      val existing = existingSite()

      val edit =
          PlantingSiteMapEditModel.of(existing).let { edit ->
            edit.copy(
                strata =
                    listOf(
                        edit.strata[0],
                        edit.strata[0].copy(name = "Other", substrata = emptyList()),
                    )
            )
          }

      assertThrows<IllegalArgumentException> { edit.toDesiredModel(existing) }
    }

    @Test
    fun `throws exception if substratum ID appears more than once`() {
      val existing = existingSite()

      val edit =
          PlantingSiteMapEditModel.of(existing).let { edit ->
            val substratum = edit.strata[0].substrata[0]
            edit.copy(
                strata =
                    listOf(
                        edit.strata[0].copy(
                            substrata = listOf(substratum, substratum.copy(name = "X"))
                        )
                    )
            )
          }

      assertThrows<IllegalArgumentException> { edit.toDesiredModel(existing) }
    }
  }
}
