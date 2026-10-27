package com.terraformation.backend.tracking

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.tables.records.PlantingSiteHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.StrataRecord
import com.terraformation.backend.db.tracking.tables.records.SubstrataRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.ObservationResultsInvalidator
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.plantingSiteStore.BasePlantingSiteStoreTest
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditSummaryItem
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
  private val service: PlantingSiteMapEditService by lazy {
    PlantingSiteMapEditService(EntityLocker(dslContext), observationStore, store)
  }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        EntityLocker(dslContext),
        eventPublisher,
        ObservationResultsInvalidator(dslContext),
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubstrataDao,
        ParentStore(dslContext),
    )
  }

  private val editTime = Instant.ofEpochSecond(10000)

  @Nested
  inner class CalculateEdit {
    @Test
    fun `calculates edit without modifying site`() {
      val existing = createSite(newSite(width = 500))

      val mapEdit =
          PlantingSiteMapEditModel.of(existing).withSubstratumBoundary(0, 0, rectangle(400, 500))
      val desired = mapEdit.toDesiredModel(existing)
      val sitesBefore = dslContext.selectFrom(PLANTING_SITES).fetch()
      val historiesBefore = dslContext.selectFrom(PLANTING_SITE_HISTORIES).fetch()

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
      assertTableEquals(sitesBefore, "Planting site should not have changed")
      assertTableEquals(historiesBefore, "Site histories should not have changed")
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

      val siteRow = dslContext.fetchSingle(PLANTING_SITES, PLANTING_SITES.ID.eq(existing.id))
      val keptStratumRow = dslContext.fetchSingle(STRATA, STRATA.ID.eq(keptStratum.id))
      val keptSubstratumRows =
          dslContext.fetch(SUBSTRATA, SUBSTRATA.STRATUM_ID.eq(keptStratum.id)).sortedBy { it.id }

      clock.instant = editTime
      val edited = service.applyEdit(existing.id, mapEdit)

      val newStratum = edited.strata.single { it.name == "Deleted" }

      // Plot creation is random and geometry unions can add vertices, so boundaries are compared
      // via areas only.
      assertAll(
          {
            assertTableEquals(
                siteRow.apply {
                  areaHa = BigDecimal("37.500")
                  boundary = null
                  gridOrigin = null
                  modifiedTime = editTime
                },
                transform = {
                  it.apply {
                    boundary = null
                    gridOrigin = null
                  }
                },
            )
          },
          {
            assertTableEquals(
                listOf(
                    keptStratumRow.apply {
                      boundary = null
                      boundaryModifiedTime = editTime
                      modifiedTime = editTime
                      name = "Renamed"
                    },
                    StrataRecord(
                        id = newStratum.id,
                        plantingSiteId = existing.id,
                        name = "Deleted",
                        createdBy = user.userId,
                        createdTime = editTime,
                        modifiedBy = user.userId,
                        modifiedTime = editTime,
                        plantVariance = BigDecimal(850),
                        plantStudentsT = BigDecimal("1.645"),
                        plantErrorMargin = BigDecimal(10),
                        plantNumPermanentPlots = 18,
                        plantNumTemporaryPlots = 6,
                        areaHa = BigDecimal("12.500"),
                        initialPlantingDensity = BigDecimal(1500),
                        boundaryModifiedBy = user.userId,
                        boundaryModifiedTime = editTime,
                        stableId = StableId("Deleted-2"),
                        biomassErrorMargin = BigDecimal(10),
                        biomassNumPermanentPlots = 18,
                        biomassNumTemporaryPlots = 6,
                        biomassStudentsT = BigDecimal("1.645"),
                        biomassVariance = BigDecimal(850),
                    ),
                ),
                transform = { it.apply { boundary = null } },
            )
          },
          {
            assertTableEquals(
                keptSubstratumRows.map { row ->
                  row.apply {
                    boundary = null
                    fullName = "Renamed-$name"
                    modifiedTime = editTime
                  }
                } +
                    SubstrataRecord(
                        id = newStratum.substrata[0].id,
                        stratumId = newStratum.id,
                        name = "C",
                        fullName = "Deleted-C",
                        createdBy = user.userId,
                        createdTime = editTime,
                        modifiedBy = user.userId,
                        modifiedTime = editTime,
                        plantingSiteId = existing.id,
                        areaHa = BigDecimal("12.500"),
                        stableId = StableId("Deleted-C"),
                    ),
                transform = { it.apply { boundary = null } },
            )
          },
          {
            assertTableEquals(
                listOf(
                    PlantingSiteHistoriesRecord(
                        plantingSiteId = existing.id,
                        createdBy = user.userId,
                        createdTime = Instant.EPOCH,
                        areaHa = BigDecimal("50.000"),
                    ),
                    PlantingSiteHistoriesRecord(
                        plantingSiteId = existing.id,
                        createdBy = user.userId,
                        createdTime = editTime,
                        areaHa = BigDecimal("37.500"),
                    ),
                ),
                transform = {
                  it.apply {
                    boundary = null
                    gridOrigin = null
                  }
                },
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
            assertTableEquals(
                listOf(
                    PlantingSiteHistoriesRecord(
                        plantingSiteId = existing.id,
                        createdBy = user.userId,
                        createdTime = Instant.EPOCH,
                        boundary = rectangle(500),
                        areaHa = BigDecimal("25.000"),
                    ),
                    PlantingSiteHistoriesRecord(
                        plantingSiteId = existing.id,
                        createdBy = user.userId,
                        createdTime = editTime,
                        boundary = rectangle(800),
                        exclusion = rectangle(10),
                        areaHa = BigDecimal("63.990"),
                    ),
                ),
                transform = { it.apply { gridOrigin = null } },
            )
          },
      )
    }

    @Test
    fun `does not modify site if map is unchanged`() {
      val existing =
          createSite(newSite { stratum(plantNumPermanent = 1) { substratum { permanent() } } })

      val historiesBefore = dslContext.selectFrom(PLANTING_SITE_HISTORIES).fetch()

      clock.instant = editTime
      service.applyEdit(existing.id, PlantingSiteMapEditModel.of(existing))

      assertTableEquals(historiesBefore, "Site histories should not have changed")
    }
  }

  @Nested
  inner class SummarizeEdit {
    @Test
    fun `only includes items down to the requested depth`() {
      val existing =
          createSite(
              newSite(width = 500) { stratum(plantNumPermanent = 1) { substratum { permanent() } } }
          )
      val plotId = existing.strata[0].substrata[0].monitoringPlots[0].id
      val historyId = plantingSiteHistoriesDao.fetchByPlantingSiteId(existing.id).single().id!!
      val observationId =
          insertObservation(plantingSiteId = existing.id, plantingSiteHistoryId = historyId)
      val plotHistoryId =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotId,
              plantingSiteId = existing.id,
              plantingSiteHistoryId = historyId,
              substratumId = null,
          )
      insertObservationPlot(monitoringPlotId = plotId, monitoringPlotHistoryId = plotHistoryId)

      val mapEdit =
          PlantingSiteMapEditModel.of(existing).withSubstratumBoundary(0, 0, rectangle(400, 500))
      val edit = service.calculateEdit(existing.id, mapEdit)

      val siteItems =
          listOf(
              PlantingSiteEditSummaryItem.ObservationsAbandoned(listOf(observationId)),
              PlantingSiteEditSummaryItem.SiteAreaChanged(BigDecimal("-5.000")),
          )
      val stratumItems =
          siteItems +
              PlantingSiteEditSummaryItem.StratumUpdated(
                  stratumName = "S1",
                  areaHaDifference = BigDecimal("-5.000"),
                  overlapPercent = 100.0,
                  numPermanentPlots = 1,
              )
      val substratumItems =
          stratumItems +
              PlantingSiteEditSummaryItem.SubstratumUpdated(
                  stratumName = "S1",
                  substratumName = "Sub1",
                  areaHaDifference = BigDecimal("-5.000"),
                  overlapPercent = 100.0,
              )
      val plotItems =
          substratumItems +
              PlantingSiteEditSummaryItem.PlotAccepted(
                  stratumName = "S1",
                  substratumName = "Sub1",
                  monitoringPlotId = plotId,
                  permanentIndex = 1,
              )

      assertAll(
          { assertEquals(siteItems, service.summarizeEdit(edit, PlantingSiteDepth.Site), "Site") },
          {
            assertEquals(
                stratumItems,
                service.summarizeEdit(edit, PlantingSiteDepth.Stratum),
                "Stratum",
            )
          },
          {
            assertEquals(
                substratumItems,
                service.summarizeEdit(edit, PlantingSiteDepth.Substratum),
                "Substratum",
            )
          },
          { assertEquals(plotItems, service.summarizeEdit(edit, PlantingSiteDepth.Plot), "Plot") },
      )
    }

    @Test
    fun `does not list observations as abandoned if their strata are unchanged`() {
      val existing =
          createSite(newSite { stratum(plantNumPermanent = 1) { substratum { permanent() } } })
      val plotId = existing.strata[0].substrata[0].monitoringPlots[0].id
      val historyId = plantingSiteHistoriesDao.fetchByPlantingSiteId(existing.id).single().id!!
      insertObservation(plantingSiteId = existing.id, plantingSiteHistoryId = historyId)
      val plotHistoryId =
          insertMonitoringPlotHistory(
              monitoringPlotId = plotId,
              plantingSiteId = existing.id,
              plantingSiteHistoryId = historyId,
              substratumId = null,
          )
      insertObservationPlot(monitoringPlotId = plotId, monitoringPlotHistoryId = plotHistoryId)

      val edit = service.calculateEdit(existing.id, PlantingSiteMapEditModel.of(existing))

      assertEquals(
          emptyList<PlantingSiteEditSummaryItem.ObservationsAbandoned>(),
          service
              .summarizeEdit(edit, PlantingSiteDepth.Site)
              .filterIsInstance<PlantingSiteEditSummaryItem.ObservationsAbandoned>(),
      )
    }

    @Test
    fun `summarizes created and deleted strata and substrata`() {
      val existing =
          createSite(
              newSite(width = 500) {
                stratum(width = 250, plantNumPermanent = 1) { substratum() }
                stratum(width = 250, plantNumPermanent = 1) { substratum() }
              }
          )
      val desired =
          newSite(width = 500) {
            stratum(width = 250, plantNumPermanent = 1) { substratum() }
            stratum(width = 250, name = "New", plantNumPermanent = 1) {
              substratum(name = "NewSub")
            }
          }
      val edit = service.calculateEdit(existing, desired)

      assertEquals(
          listOf(
              PlantingSiteEditSummaryItem.SiteAreaChanged(edit.areaHaDifference),
              PlantingSiteEditSummaryItem.StratumCreated("New", StableId("New"), 1),
              PlantingSiteEditSummaryItem.SubstratumCreated(
                  "New",
                  "NewSub",
                  StableId("New-NewSub"),
              ),
              PlantingSiteEditSummaryItem.StratumUpdated("S1", edit.areaHaDifference, 100.0, 1),
              PlantingSiteEditSummaryItem.StratumDeleted("S2", StableId("S2")),
              PlantingSiteEditSummaryItem.SubstratumDeleted("S2", "Sub2", StableId("S2-Sub2")),
          ),
          service.summarizeEdit(edit, PlantingSiteDepth.Substratum),
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
