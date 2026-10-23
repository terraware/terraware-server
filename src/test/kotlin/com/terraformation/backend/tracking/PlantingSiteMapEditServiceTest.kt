package com.terraformation.backend.tracking

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.plantingSiteStore.BasePlantingSiteStoreTest
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.StratumEdit
import com.terraformation.backend.tracking.edit.SubstratumEdit
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteMapEditModel
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailureType
import com.terraformation.backend.tracking.model.StratumMapEditModel
import com.terraformation.backend.tracking.model.SubstratumMapEditModel
import io.mockk.every
import io.mockk.spyk
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.TopologyException
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteMapEditServiceTest : BasePlantingSiteStoreTest() {
  private val service: PlantingSiteMapEditService by lazy { PlantingSiteMapEditService(store) }

  private val editTime = Instant.ofEpochSecond(10000)

  @Nested
  inner class CalculateEdit {
    @Test
    fun `calculates edit without modifying site`() {
      val existing = createSite(newSite(width = 500))

      val mapEdit =
          PlantingSiteMapEditModel.of(existing).withSubstratumBoundary(0, 0, rectangle(400, 500))
      val desired = mapEdit.toDesiredModel(existing)

      val edit = service.calculateEdit(existing.id, mapEdit)

      assertEditEquals(
          PlantingSiteEdit(
              areaHaDifference = BigDecimal("-5.000"),
              desiredModel = desired,
              existingModel = existing,
              stratumEdits =
                  listOf(
                      StratumEdit.Update(
                          addedRegion = rectangle(0),
                          areaHaDifference = BigDecimal("-5.000"),
                          desiredModel = desired.strata[0],
                          existingModel = existing.strata[0],
                          monitoringPlotEdits =
                              (1..18).map { MonitoringPlotEdit.Create(desired.boundary!!, it) },
                          substratumEdits =
                              listOf(
                                  SubstratumEdit.Update(
                                      addedRegion = rectangle(0),
                                      areaHaDifference = BigDecimal("-5.000"),
                                      desiredModel = desired.strata[0].substrata[0],
                                      existingModel = existing.strata[0].substrata[0],
                                      removedRegion = rectangle(100, 500, x = 400),
                                  )
                              ),
                          removedRegion = rectangle(100, 500, x = 400),
                      )
                  ),
          ),
          edit,
      )
      assertGeometryEquals(
          existing.boundary,
          store.fetchSiteById(existing.id, PlantingSiteDepth.Site).boundary,
          "Site boundary should not have changed",
      )
      assertEquals(1, plantingSiteHistoriesDao.findAll().size, "Number of site histories")
    }

    @Test
    fun `throws exception if edited map is invalid`() {
      val existing = createSite(newSite(width = 500) { stratum { substratum(width = 250) } })

      val mapEdit =
          PlantingSiteMapEditModel.of(existing).let { edit ->
            val stratum = edit.strata[0]
            edit.copy(
                strata =
                    listOf(
                        stratum.copy(
                            substrata =
                                stratum.substrata +
                                    SubstratumMapEditModel(null, "Overlapping", rectangle(500))
                        )
                    )
            )
          }

      val exception =
          assertThrows<PlantingSiteMapInvalidException> {
            service.calculateEdit(existing.id, mapEdit)
          }
      assertEquals(
          setOf(PlantingSiteValidationFailureType.SubstratumBoundaryOverlaps),
          exception.problems.map { it.type }.toSet(),
      )
    }

    @Test
    fun `reports topology failures while building desired site as invalid arguments`() {
      val existing = createSite(newSite())
      val mapEdit = spyk(PlantingSiteMapEditModel.of(existing))

      every { mapEdit.toDesiredModel(any()) } throws TopologyException("Test failure")

      assertThrows<IllegalArgumentException> { service.calculateEdit(existing.id, mapEdit) }
    }

    @Test
    fun `can skip validation of desired site`() {
      val existing = createSite(newSite())
      val desired = newSite {
        stratum {
          substratum(width = 300)
          substratum(x = 200)
        }
      }

      assertThrows<PlantingSiteMapInvalidException> { service.calculateEdit(existing, desired) }
      assertDoesNotThrow { service.calculateEdit(existing, desired, validate = false) }
    }

    @Test
    fun `throws exception if no permission to update planting site`() {
      val existing = createSite(newSite())

      every { user.canUpdatePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> {
        service.calculateEdit(existing.id, PlantingSiteMapEditModel.of(existing))
      }
    }
  }

  @Nested
  inner class ApplyEdit {
    @Test
    fun `applies edits to strata and substrata`() {
      val existing =
          createSite(
              newSite(width = 1000) {
                stratum(name = "Kept", width = 500, plantNumPermanent = 7) {
                  plantVariance = BigDecimal(12345)
                  substratum(name = "A", width = 250)
                  substratum(name = "B")
                }
                stratum(name = "Deleted")
              }
          )
      val keptStratum = existing.strata.single { it.name == "Kept" }

      val mapEdit =
          PlantingSiteMapEditModel(
              boundary = null,
              exclusion = null,
              strata =
                  listOf(
                      StratumMapEditModel(
                          id = keptStratum.id,
                          name = "Renamed",
                          substrata =
                              listOf(
                                  SubstratumMapEditModel(
                                      keptStratum.substrata[0].id,
                                      "A",
                                      rectangle(250, 500),
                                  ),
                                  SubstratumMapEditModel(
                                      keptStratum.substrata[1].id,
                                      "B",
                                      rectangle(250, 500, x = 250),
                                  ),
                              ),
                      ),
                      StratumMapEditModel(
                          id = null,
                          name = "Deleted",
                          substrata =
                              listOf(
                                  SubstratumMapEditModel(null, "C", rectangle(250, 500, x = 500))
                              ),
                      ),
                  ),
          )

      clock.instant = editTime
      val edited = service.applyEdit(existing.id, mapEdit)

      val renamedStratum = edited.strata.single { it.name == "Renamed" }
      val newStratum = edited.strata.single { it.name == "Deleted" }

      assertAll(
          { assertEquals(keptStratum.id, renamedStratum.id, "Kept stratum ID") },
          { assertEquals(keptStratum.stableId, renamedStratum.stableId, "Kept stable ID") },
          {
            assertEquals(
                keptStratum.plantPlotCounts,
                renamedStratum.plantPlotCounts,
                "Kept stratum plot counts",
            )
          },
          {
            assertEquals(
                keptStratum.substrata.map { it.id },
                renamedStratum.substrata.map { it.id },
                "Kept substratum IDs",
            )
          },
          {
            assertEquals(
                listOf("Renamed-A", "Renamed-B"),
                renamedStratum.substrata.map { it.fullName },
                "Kept substratum full names",
            )
          },
          {
            assertEquals(
                StableId("Deleted-2"),
                newStratum.stableId,
                "Replacement stratum gets a new stable ID",
            )
          },
          {
            assertEquals(
                BigDecimal("37.500"),
                edited.areaHa,
                "Site area is that of union of strata",
            )
          },
          {
            assertEquals(
                2,
                plantingSiteHistoriesDao.fetchByPlantingSiteId(existing.id).size,
                "Number of site histories",
            )
          },
      )
    }

    @Test
    fun `clears planting completed flags of requested substrata`() {
      val existing =
          createSite(
              newSite {
                stratum {
                  substratum(width = 250) { plantingCompletedTime = Instant.EPOCH }
                  substratum { plantingCompletedTime = Instant.EPOCH }
                }
              }
          )
      val substratumToMarkIncomplete = existing.strata[0].substrata[0]

      service.applyEdit(
          existing.id,
          PlantingSiteMapEditModel.of(existing)
              .withSubstratumBoundary(0, 0, rectangle(300, 500))
              .withSubstratumBoundary(0, 1, rectangle(200, 500, x = 300)),
          setOf(substratumToMarkIncomplete.id),
      )

      val completedTimes =
          dslContext
              .select(SUBSTRATA.ID, SUBSTRATA.PLANTING_COMPLETED_TIME)
              .from(SUBSTRATA)
              .fetchMap(SUBSTRATA.ID, SUBSTRATA.PLANTING_COMPLETED_TIME)

      assertAll(
          { assertNull(completedTimes[substratumToMarkIncomplete.id], "Marked incomplete") },
          { assertNotNull(completedTimes[existing.strata[0].substrata[1].id], "Still complete") },
      )
    }

    @Test
    fun `edits boundary of simple site`() {
      val existing = createSite(newSite(width = 500).copy(strata = emptyList()))

      clock.instant = editTime
      val edited =
          service.applyEdit(
              existing.id,
              PlantingSiteMapEditModel(
                  boundary = rectangle(800),
                  exclusion = rectangle(10),
                  strata = emptyList(),
              ),
          )

      assertAll(
          { assertGeometryEquals(rectangle(800), edited.boundary, "Boundary") },
          { assertGeometryEquals(rectangle(10), edited.exclusion, "Exclusion") },
          { assertEquals(emptyList<Any>(), edited.strata, "Strata") },
          {
            assertEquals(
                2,
                plantingSiteHistoriesDao.fetchByPlantingSiteId(existing.id).size,
                "Number of site histories",
            )
          },
      )
    }

    @Test
    fun `does not modify site if map is unchanged`() {
      val existing =
          createSite(newSite { stratum(plantNumPermanent = 1) { substratum { permanent() } } })

      clock.instant = editTime
      service.applyEdit(existing.id, PlantingSiteMapEditModel.of(existing))

      assertEquals(
          1,
          plantingSiteHistoriesDao.fetchByPlantingSiteId(existing.id).size,
          "Number of site histories",
      )
    }
  }

  private fun createSite(initial: NewPlantingSiteModel): ExistingPlantingSiteModel {
    clock.instant = Instant.EPOCH
    val created = store.createPlantingSite(initial.copy(organizationId = organizationId))

    // createPlantingSite doesn't create monitoring plots since they are expected to be created on
    // demand later on, so we need to create them ourselves.
    initial.strata.forEach { initialStratum ->
      val stratum = created.strata.single { it.name == initialStratum.name }
      initialStratum.substrata.forEach { initialSubstratum ->
        val substratum = stratum.substrata.single { it.name == initialSubstratum.name }
        initialSubstratum.monitoringPlots.forEach { plot ->
          insertMonitoringPlot(
              boundary = plot.boundary,
              permanentIndex = plot.permanentIndex,
              plantingSiteId = created.id,
              plotNumber = plot.plotNumber,
              substratumId = substratum.id,
          )
        }
      }
    }

    return store.fetchSiteById(created.id, PlantingSiteDepth.Plot)
  }

  private fun assertEditEquals(expected: PlantingSiteEdit, actual: PlantingSiteEdit) {
    if (!actual.equalsExact(expected)) {
      // The JSON form produces a readable diff; the native form includes type information.
      assertAll(
          { assertJsonEquals(expected, actual) },
          { assertEquals(expected, actual) },
      )
    }
  }

  private fun PlantingSiteMapEditModel.withSubstratumBoundary(
      stratumIndex: Int,
      substratumIndex: Int,
      boundary: MultiPolygon,
  ): PlantingSiteMapEditModel {
    val stratum = strata[stratumIndex]
    val substrata =
        stratum.substrata.toMutableList().also {
          it[substratumIndex] = it[substratumIndex].copy(boundary = boundary)
        }
    return copy(
        strata =
            strata.toMutableList().also { it[stratumIndex] = stratum.copy(substrata = substrata) }
    )
  }
}
