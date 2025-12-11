package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.NumericIdentifierType
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumHistoriesRow
import com.terraformation.backend.db.tracking.tables.records.MonitoringPlotHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.SubstratumHistoriesRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculatorTest
import com.terraformation.backend.tracking.edit.StratumEdit
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.util.nearlyCoveredBy
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry
import org.springframework.security.access.AccessDeniedException

/**
 * Tests for applying edit operations to planting sites. Most of these tests don't manually
 * construct [PlantingSiteEdit] objects, but instead use [PlantingSiteEditCalculator] and rely on
 * the coverage in [PlantingSiteEditCalculatorTest] to verify that the calculated edits would be
 * correct.
 */
internal class PlantingSiteStoreApplyEditTest : BasePlantingSiteStoreTest() {
  private val editTime = Instant.ofEpochSecond(10000)

  @Nested
  inner class ApplyPlantingSiteEdit {
    @Test
    fun `updates existing boundaries`() {
      val (edited, existing) =
          runScenario(
              initial =
                  newSite(y = 800000, width = 100500) {
                    exclusion = rectangle(10)
                    gridOrigin = point(1)
                  },
              desired =
                  newSite(y = 800000) {
                    exclusion = rectangle(20)
                    gridOrigin = point(10, 0)
                  },
              expected =
                  newSite(y = 800000, countryCode = "TG") {
                    exclusion = rectangle(20)
                    gridOrigin = point(1)
                  },
          )

      fun ExistingPlantingSiteModel.allZoneIds(): Set<StratumId> = strata.map { it.id }.toSet()

      fun ExistingPlantingSiteModel.allSubzoneIds(): Set<SubstratumId> =
          strata.flatMap { zone -> zone.substrata.map { it.id } }.toSet()

      assertNull(
          existing.countryCode,
          "Initial boundary spans 2 countries so country code should be null",
      )
      assertEquals(existing.allZoneIds(), edited.allZoneIds(), "Planting zone IDs")
      assertEquals(existing.allSubzoneIds(), edited.allSubzoneIds(), "Planting subzone IDs")
    }

    @Test
    fun `updates settings`() {
      val newErrorMargin = BigDecimal(101)
      val newStudentsT = BigDecimal("1.646")
      val newTargetPlantingDensity = BigDecimal(1100)
      val newVariance = BigDecimal(40001)

      val (edited) =
          runScenario(
              initial = newSite(),
              desired =
                  newSite {
                    zone {
                      errorMargin = newErrorMargin
                      studentsT = newStudentsT
                      targetPlantingDensity = newTargetPlantingDensity
                      variance = newVariance
                    }
                  },
          )

      assertEquals(newErrorMargin, edited.strata[0].errorMargin, "Error margin")
      assertEquals(newStudentsT, edited.strata[0].studentsT, "Student's t")
      assertEquals(
          newTargetPlantingDensity,
          edited.strata[0].targetPlantingDensity,
          "Target planting density",
      )
      assertEquals(newVariance, edited.strata[0].variance, "Variance")
    }

    @Test
    fun `creates new zones`() {
      runScenario(
          newSite(width = 500),
          newSite(width = 750) {
            zone(width = 500)
            zone()
          },
      )
    }

    @Test
    fun `deletes existing zones`() {
      runScenario(
          initial =
              newSite(width = 750) {
                zone(width = 500)
                zone()
              },
          desired = newSite(width = 500),
      )
    }

    @Test
    fun `deletes existing subzones`() {
      runScenario(
          initial =
              newSite(width = 750) {
                zone(width = 750) {
                  subzone(width = 500)
                  subzone()
                }
              },
          desired = newSite(width = 500),
      )
    }

    @Test
    fun `updates plantable area totals when exclusion geometry changes`() {
      val (edited, existing) =
          runScenario(
              newSite { exclusion = rectangle(width = 100, height = 500) },
              newSite { exclusion = rectangle(width = 200, height = 500) },
          )

      assertEquals(BigDecimal("20.0"), existing.areaHa, "Site area before edit")
      assertEquals(BigDecimal("15.0"), edited.areaHa, "Site area after edit")
      assertEquals(BigDecimal("20.0"), existing.strata[0].areaHa, "Zone area before edit")
      assertEquals(BigDecimal("15.0"), edited.strata[0].areaHa, "Zone area after edit")
      assertEquals(
          BigDecimal("20.0"),
          existing.strata[0].substrata[0].areaHa,
          "Subzone area before edit",
      )
      assertEquals(
          BigDecimal("15.0"),
          edited.strata[0].substrata[0].areaHa,
          "Subzone area after edit",
      )
    }

    @Test
    fun `optionally marks expanded subzones as not complete`() {
      runScenario(
          initial =
              newSite {
                zone(numPermanent = 1) {
                  subzone(width = 250) {
                    plantingCompletedTime = Instant.EPOCH
                    permanent()
                  }
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                }
              },
          desired =
              newSite(height = 600) {
                zone(numPermanent = 1) {
                  subzone(width = 250)
                  subzone(width = 250)
                }
              },
          expected =
              newSite(height = 600) {
                zone(numPermanent = 1) {
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                  subzone(width = 250) { plantingCompletedTime = null }
                }
              },
          getSubzonesToMarkIncomplete = { existing ->
            // Ask it to mark just one of the two expanded subzones as incomplete.
            existing.strata[0].substrata.filter { it.name == "S2" }.map { it.id }.toSet()
          },
      )
    }

    @Test
    fun `updates zone boundary modified time if geometry changed`() {
      runScenario(
          initial =
              newSite(width = 1000) {
                zone(width = 500)
                zone(width = 500)
              },
          desired =
              newSite(width = 1100) {
                zone(width = 500)
                zone(width = 600)
              },
      )

      val zonesRows = plantingZonesDao.findAll().associateBy { it.name }
      assertEquals(
          Instant.EPOCH,
          zonesRows["Z1"]?.boundaryModifiedTime,
          "Zone with no boundary change",
      )
      assertEquals(editTime, zonesRows["Z2"]?.boundaryModifiedTime, "Zone with boundary change")
    }

    @Test
    fun `publishes event if site was edited`() {
      val results = runScenario(newSite(), newSite(width = 600))

      val monitoringPlotIds =
          results.edited.strata[0].substrata[0].monitoringPlots.map { it.id }.toSet()

      eventPublisher.assertEventPublished(
          PlantingSiteMapEditedEvent(
              results.edited,
              results.plantingSiteEdit,
              ReplacementResult(monitoringPlotIds, emptySet()),
          )
      )
    }

    @Test
    fun `does not publish event if edit was a no-op`() {
      runScenario(
          initial = newSite { zone(numPermanent = 1) { subzone { permanent() } } },
          desired = newSite { zone(numPermanent = 1) },
      )

      eventPublisher.assertEventNotPublished<PlantingSiteMapEditedEvent>()
    }

    @Test
    fun `throws exception if edit results in duplicate permanent indexes`() {
      val existing = createSite(newSite())

      assertThrows<IllegalStateException> {
        store.applyPlantingSiteEdit(
            PlantingSiteEdit(
                areaHaDifference = BigDecimal.ZERO,
                desiredModel = existing,
                existingModel = existing,
                stratumEdits =
                    listOf(
                        StratumEdit.Update(
                            addedRegion = rectangle(0),
                            areaHaDifference = BigDecimal.ZERO,
                            desiredModel = existing.strata[0],
                            existingModel = existing.strata[0],
                            monitoringPlotEdits =
                                listOf(
                                    MonitoringPlotEdit.Create(existing.boundary!!, 1),
                                    MonitoringPlotEdit.Create(existing.boundary!!, 1),
                                    MonitoringPlotEdit.Create(existing.boundary!!, 2),
                                    MonitoringPlotEdit.Create(existing.boundary!!, 2),
                                ),
                            substratumEdits = emptyList(),
                            removedRegion = rectangle(0),
                        )
                    ),
            )
        )
      }
    }

    @Test
    fun `throws exception if no permission to update site`() {
      val existing = store.createPlantingSite(newSite().copy(organizationId = organizationId))

      every { user.canUpdatePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.applyPlantingSiteEdit(calculateSiteEdit(existing, newSite(width = 600)))
      }
    }

    @Test
    fun `creates new monitoring plots in requested regions`() {
      val initial = newSite(width = 501)
      val expected = newSite(width = 1000) { zone(numPermanent = 4) }
      val existingArea = initial.boundary!!
      val newArea = rectangle(x = 500, width = 500, height = 500)

      val (edited) =
          runScenario(
              initial = initial,
              desired = expected,
              expectedPlotCounts = listOf(existingArea to 2, newArea to 2),
          )

      assertPermanentPlotRegions(
          edited,
          mapOf(
              1 to existingArea,
              2 to newArea,
              3 to existingArea,
              4 to newArea,
          ),
      )
    }

    @Test
    fun `moves existing monitoring plots between subzones`() {
      val initial = newSite {
        zone {
          subzone {
            permanent(x = 0)
            permanent(x = 300)
            permanent(x = 30)
            permanent(x = 330)
          }
        }
      }
      val expected = newSite {
        zone(numPermanent = 4) {
          subzone(width = 250)
          subzone(width = 250)
        }
      }

      val (edited) = runScenario(initial = initial, desired = expected)

      assertSubzonePermanentIndexes(edited, mapOf("S1" to listOf(1, 3), "S2" to listOf(2, 4)))
    }

    @Test
    fun `creates and adopts monitoring plots with permanent index renumbering`() {
      // This scenario is from the PRD and the edit calculation is covered in
      // PlantingSiteEditCalculatorV2Test.
      val initial =
          newSite(x = 0, width = 1000) {
            zone(name = "A", x = 0, width = 500) {
              subzone {
                permanent(plotNumber = 1, x = 300, y = 0)
                permanent(plotNumber = 2, x = 300, y = 30)
                permanent(plotNumber = 4, x = 300, y = 60)
                permanent(plotNumber = 7, x = 300, y = 90)
              }
            }
            zone(name = "B", x = 500, width = 500) {
              subzone {
                permanent(plotNumber = 16, x = 600, y = 0)
                permanent(plotNumber = 18, x = 600, y = 30)
                permanent(plotNumber = 19, x = 600, y = 60)
                permanent(plotNumber = 23, x = 600, y = 90)
                permanent(plotNumber = 25, x = 600, y = 120)
                permanent(plotNumber = 26, x = 600, y = 150)
                permanent(plotNumber = 27, x = 600, y = 180)
              }
            }
          }

      // 44% overlaps with old zone A, 56% doesn't.
      val desired =
          newSite(x = 280, width = 500) {
            gridOrigin = point(1)
            zone(name = "A", numPermanent = 11, numTemporary = 3) {}
          }

      runScenario(initial = initial, desired = desired)

      assertEquals(
          mapOf(
              16L to 1,
              1L to 2,
              18L to 3,
              2L to 4,
              19L to 5,
              4L to 6,
              23L to 7,
              25L to 8,
              7L to 9,
              26L to 10,
              29L to 11, // Newly-created plot
              27L to null,
          ),
          monitoringPlotsDao.findAll().associate { it.plotNumber to it.permanentIndex },
          "Permanent indexes for each plot number",
      )
    }

    @Test
    fun `moves existing monitoring plots between planting zones`() {
      val initial = newSite {
        zone(name = "A") {
          subzone {
            permanent(plotNumber = 1, x = 100)
            permanent(plotNumber = 2, x = 400)
          }
        }
      }

      val desired = newSite {
        zone(width = 300, name = "A", numPermanent = 1)
        zone(name = "B", numPermanent = 1)
      }

      val (edited) = runScenario(initial = initial, desired = desired)

      assertSubzonePermanentIndexes(edited, mapOf("S1" to listOf(1), "S2" to listOf(1)))
      assertSubzonePlotNumbers(edited, mapOf("S1" to listOf(1L), "S2" to listOf(2L)))
    }

    @Test
    fun `retains zone and subzone IDs on rename`() {
      val initial = newSite {
        zone(name = "A", numPermanent = 1, width = 250) { subzone(name = "Subzone") }
        zone(name = "B", numPermanent = 1, width = 250) { subzone(name = "Subzone") }
      }

      val desired = newSite {
        zone(name = "C", stableId = StableId("A"), numPermanent = 1, width = 250) {
          subzone(name = "Subzone", stableId = StableId("A-Subzone"))
        }
        zone(name = "D", stableId = StableId("B"), numPermanent = 1, width = 250) {
          subzone(name = "Subzone", stableId = StableId("B-Subzone"))
        }
      }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingZones = existing.strata.associateBy { it.name }

      assertEquals(
          mapOf("C" to existingZones["A"]!!.id, "D" to existingZones["B"]!!.id),
          edited.strata.associate { it.name to it.id },
          "Zone IDs",
      )
      assertEquals(
          mapOf(
              "C-Subzone" to existingZones["A"]!!.substrata.first().id,
              "D-Subzone" to existingZones["B"]!!.substrata.first().id,
          ),
          edited.strata.flatMap { it.substrata }.associate { it.fullName to it.id },
          "Subzone IDs",
      )
    }

    @Test
    fun `can swap zone names`() {
      val initial = newSite {
        zone(name = "A", width = 250)
        zone(name = "B")
      }

      val desired = newSite {
        zone(name = "A", stableId = StableId("B"), x = 250, width = 250) {
          subzone(stableId = StableId("B-S2"))
        }
        zone(name = "B", stableId = StableId("A"), x = 0, width = 250) {
          subzone(stableId = StableId("A-S1"))
        }
      }

      val (edited, existing) = runScenario(initial, desired)

      assertEquals(
          mapOf("A" to StableId("B"), "B" to StableId("A")),
          edited.strata.associate { it.name to it.stableId },
          "Stable IDs for zone names after name swap",
      )
      assertEquals(
          existing.strata.associate { it.stableId to it.id },
          edited.strata.associate { it.stableId to it.id },
          "Planting zone IDs by stable ID after name swap",
      )
    }

    @Test
    fun `can swap subzone names`() {
      val initial = newSite {
        zone {
          subzone(name = "S1", stableId = StableId("S1"), width = 250)
          subzone(name = "S2", stableId = StableId("S2"))
        }
      }

      val desired = newSite {
        zone {
          subzone(name = "S1", stableId = StableId("S2"), x = 250, width = 250)
          subzone(name = "S2", stableId = StableId("S1"), x = 0, width = 250)
        }
      }

      val (edited, existing) = runScenario(initial, desired)

      assertEquals(
          mapOf("S1" to StableId("S2"), "S2" to StableId("S1")),
          edited.strata.single().substrata.associate { it.name to it.stableId },
          "Stable IDs for subzone names after name swap",
      )
      assertEquals(
          existing.strata.single().substrata.associate { it.stableId to it.id },
          edited.strata.single().substrata.associate { it.stableId to it.id },
          "Planting subzone IDs by stable ID after name swap",
      )
    }

    @Test
    fun `moves existing subzones between zones`() {
      val initial = newSite {
        zone(name = "A", numPermanent = 2) {
          subzone(name = "Subzone 1", width = 250) { permanent() }
          subzone(name = "Subzone 2", width = 250) { permanent() }
        }
      }

      val desired = newSite {
        zone(name = "A", numPermanent = 1, width = 250) { subzone(name = "Subzone 1") }
        zone(name = "B", numPermanent = 1, width = 250) {
          subzone(name = "Subzone 2", stableId = StableId("A-Subzone 2"))
        }
      }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingSubzone2 = existing.strata[0].substrata[1]
      val editedSubzone2 = edited.strata[1].substrata[0]

      assertEquals(existingSubzone2.id, editedSubzone2.id, "ID of moved subzone")
      assertEquals(
          existingSubzone2.monitoringPlots[0].copy(permanentIndex = 1),
          editedSubzone2.monitoringPlots[0],
          "Monitoring plot in moved subzone",
      )
    }

    @Test
    fun `moves subzone from deleted zone to new one, perhaps because the zone was renamed`() {
      val initial = newSite {
        zone(name = "A", numPermanent = 1) { subzone(name = "Subzone") { permanent() } }
      }

      val desired = newSite {
        zone(name = "B", numPermanent = 1) {
          subzone(name = "Subzone", stableId = StableId("A-Subzone"))
        }
      }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingSubzone = existing.strata[0].substrata[0]
      val editedSubzone = edited.strata[0].substrata[0]

      assertEquals(existingSubzone.id, editedSubzone.id, "ID of moved subzone")
      assertEquals(
          existingSubzone.monitoringPlots[0].copy(permanentIndex = 1),
          editedSubzone.monitoringPlots[0],
          "Monitoring plot in moved subzone",
      )
    }

    private fun createSite(initial: NewPlantingSiteModel): ExistingPlantingSiteModel {
      clock.instant = Instant.EPOCH

      // createPlantingSite doesn't create monitoring plots since they are expected to be created
      // on demand later on, so we need to create them ourselves.
      val existingWithoutPlots =
          store.createPlantingSite(initial.copy(organizationId = organizationId))

      val maxPlotNumber =
          initial.strata.maxOfOrNull { initialZone ->
            val existingZone = existingWithoutPlots.strata.single { it.name == initialZone.name }

            initialZone.substrata.maxOfOrNull { initialSubzone ->
              val existingSubzone = existingZone.substrata.single { it.name == initialSubzone.name }

              initialSubzone.monitoringPlots.maxOfOrNull { initialPlot ->
                insertMonitoringPlot(
                    boundary = initialPlot.boundary,
                    isAvailable = initialPlot.isAvailable,
                    permanentIndex = initialPlot.permanentIndex,
                    plantingSiteId = existingWithoutPlots.id,
                    plantingSubzoneId = existingSubzone.id,
                    plotNumber = initialPlot.plotNumber,
                )

                initialPlot.plotNumber
              } ?: 0L
            } ?: 0L
          } ?: 0L

      // Applying the edit may cause new plots to be created, and we don't want their plot numbers
      // to collide with the plots we've just inserted.
      do {
        val nextPlotNumber =
            identifierGenerator.generateNumericIdentifier(
                organizationId,
                NumericIdentifierType.PlotNumber,
            )
      } while (nextPlotNumber <= maxPlotNumber)

      val existing = store.fetchSiteById(existingWithoutPlots.id, PlantingSiteDepth.Plot)
      return existing
    }

    /**
     * Runs a scenario and asserts that the results are correct.
     * 1. Creates a new site with an initial configuration
     * 2. Calculates the edit to turn it into a desired configuration
     * 3. Applies the edit
     * 4. Compares the result to an expected configuration
     *
     * Since monitoring plots can be created at random locations, we can't compare them to a fixed
     * list of expected values. Instead, we assert that there are the expected number of plots in
     * specific regions.
     */
    private fun runScenario(
        initial: NewPlantingSiteModel,
        desired: NewPlantingSiteModel,
        expected: NewPlantingSiteModel = desired,
        expectedPlotCounts: List<Pair<Geometry, Int>>? = null,
        getSubzonesToMarkIncomplete: (ExistingPlantingSiteModel) -> Set<SubstratumId> = {
          emptySet()
        },
    ): ScenarioResults {
      val existing = createSite(initial)

      val plantingSiteEdit = calculateSiteEdit(existing, desired)

      clock.instant = editTime
      val subzonesToMarkIncomplete = getSubzonesToMarkIncomplete(existing)
      val edited = store.applyPlantingSiteEdit(plantingSiteEdit, subzonesToMarkIncomplete)

      fun NewPlantingSiteModel.withoutMonitoringPlots() =
          copy(
              strata =
                  strata.map { zone ->
                    zone.copy(
                        substrata =
                            zone.substrata.map { subzone ->
                              subzone.copy(monitoringPlots = emptyList())
                            }
                    )
                  }
          )

      val expectedWithoutMonitoringPlots = expected.withoutMonitoringPlots()
      val editedWithoutMonitoringPlots = edited.toNew().withoutMonitoringPlots()
      if (!expectedWithoutMonitoringPlots.equals(editedWithoutMonitoringPlots, 0.00001)) {
        assertEquals(
            expectedWithoutMonitoringPlots,
            editedWithoutMonitoringPlots,
            "Planting site after edit",
        )
      }

      val editedMonitoringPlots =
          edited.strata.flatMap { zone ->
            zone.substrata.flatMap { subzone -> subzone.monitoringPlots }
          }

      if (expectedPlotCounts != null) {
        val actualPlotCounts =
            expectedPlotCounts.map { (boundary, _) ->
              boundary to editedMonitoringPlots.count { it.boundary.nearlyCoveredBy(boundary) }
            }

        assertEquals(
            expectedPlotCounts,
            actualPlotCounts,
            "Number of monitoring plots in regions of edited site",
        )
      }

      if (plantingSiteEdit.stratumEdits.isNotEmpty()) {
        assertHistories(existing, edited)
      }

      return ScenarioResults(edited, existing, plantingSiteEdit)
    }

    private fun calculateSiteEdit(
        existing: ExistingPlantingSiteModel,
        desired: AnyPlantingSiteModel,
    ): PlantingSiteEdit {
      val calculator = PlantingSiteEditCalculator(existing, desired)
      return calculator.calculateSiteEdit()
    }

    private fun assertHistories(
        existing: ExistingPlantingSiteModel,
        edited: ExistingPlantingSiteModel,
    ) {
      val siteHistories = plantingSiteHistoriesDao.fetchByPlantingSiteId(existing.id)
      val existingSiteHistory =
          siteHistories.firstOrNull { it.createdTime == Instant.EPOCH }
              ?: fail("No site history for initial creation")
      val editedSiteHistory =
          siteHistories.firstOrNull { it.createdTime == editTime }
              ?: fail("No site history for edit")

      assertEquals(2, siteHistories.size, "Number of site history entries")
      assertEquals(
          PlantingSiteHistoriesRow(
              areaHa = existing.areaHa,
              boundary = existing.boundary,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              exclusion = existing.exclusion,
              gridOrigin = existing.gridOrigin,
              id = existingSiteHistory.id,
              plantingSiteId = existing.id,
          ),
          existingSiteHistory,
          "Existing site history",
      )
      assertEquals(
          PlantingSiteHistoriesRow(
              areaHa = edited.areaHa,
              boundary = edited.boundary,
              createdBy = user.userId,
              createdTime = editTime,
              exclusion = edited.exclusion,
              gridOrigin = existing.gridOrigin,
              id = editedSiteHistory.id,
              plantingSiteId = existing.id,
          ),
          editedSiteHistory,
          "Edited site history",
      )

      val editedZoneHistories =
          plantingZoneHistoriesDao.fetchByPlantingSiteHistoryId(editedSiteHistory.id!!)

      assertEquals(
          edited.strata.size,
          editedZoneHistories.size,
          "Number of planting zone histories from edit",
      )
      assertEquals(
          edited.strata
              .map { zone ->
                StratumHistoriesRow(
                    areaHa = zone.areaHa,
                    boundary = zone.boundary,
                    name = zone.name,
                    plantingSiteHistoryId = editedSiteHistory.id,
                    stratumId = zone.id,
                    stableId = zone.stableId,
                )
              }
              .toSet(),
          editedZoneHistories.map { it.copy(id = null) }.toSet(),
          "Planting zone histories from edit",
      )

      val editedSubzoneHistories =
          plantingSubzoneHistoriesDao.fetchByStratumHistoryId(
              *editedZoneHistories.map { it.id!! }.toTypedArray()
          )

      assertTableEquals(
          edited.strata.flatMap { zone ->
            val plantingZoneHistoryId = editedZoneHistories.first { it.stratumId == zone.id }.id
            zone.substrata.map { subzone ->
              SubstratumHistoriesRecord(
                  areaHa = subzone.areaHa,
                  boundary = subzone.boundary,
                  fullName = subzone.fullName,
                  name = subzone.name,
                  substratumId = subzone.id,
                  stratumHistoryId = plantingZoneHistoryId,
                  stableId = subzone.stableId,
              )
            }
          },
          "Planting subzone histories from edit",
          where =
              SUBSTRATUM_HISTORIES.stratumHistories.PLANTING_SITE_HISTORY_ID.eq(
                  editedSiteHistory.id
              ),
      )

      val subzonePlotIds =
          edited.strata
              .flatMap { zone ->
                zone.substrata.flatMap { subzone -> subzone.monitoringPlots.map { it.id } }
              }
              .toSet()

      val ejectedPlotHistories =
          existing.strata.flatMap { zone ->
            zone.substrata.flatMap { subzone ->
              subzone.monitoringPlots
                  .filter { it.id !in subzonePlotIds }
                  .map { plot ->
                    MonitoringPlotHistoriesRecord(
                        createdBy = user.userId,
                        createdTime = editTime,
                        monitoringPlotId = plot.id,
                        plantingSiteHistoryId = editedSiteHistory.id,
                        plantingSiteId = edited.id,
                    )
                  }
            }
          }

      val subzonePlotHistories =
          edited.strata.flatMap { zone ->
            zone.substrata.flatMap { subzone ->
              val plantingSubzoneHistoryId =
                  editedSubzoneHistories.first { it.substratumId == subzone.id }.id
              subzone.monitoringPlots.map { plot ->
                MonitoringPlotHistoriesRecord(
                    createdBy = user.userId,
                    createdTime = editTime,
                    monitoringPlotId = plot.id,
                    plantingSiteHistoryId = editedSiteHistory.id,
                    plantingSiteId = edited.id,
                    substratumHistoryId = plantingSubzoneHistoryId,
                    substratumId = subzone.id,
                )
              }
            }
          }

      val expectedPlotHistories = ejectedPlotHistories + subzonePlotHistories

      if (expectedPlotHistories.isNotEmpty()) {
        assertTableEquals(
            expectedPlotHistories,
            "Monitoring plot histories from edit",
            where = MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(editedSiteHistory.id),
        )
      }
    }
  }

  /**
   * Asserts that each permanent plot is located somewhere in a particular region. This is used to
   * test random plot placement.
   *
   * @param expected Map of permanent indexes to expected regions.
   */
  private fun assertPermanentPlotRegions(
      edited: ExistingPlantingSiteModel,
      expected: Map<Int, Geometry>,
  ) {
    val editedMonitoringPlots =
        edited.strata.flatMap { zone ->
          zone.substrata.flatMap { subzone -> subzone.monitoringPlots }
        }
    val editedPermanentIndexes = editedMonitoringPlots.mapNotNull { it.permanentIndex }.sorted()
    val expectedPermanentIndexes = expected.keys.sorted()
    assertEquals(expectedPermanentIndexes, editedPermanentIndexes, "Permanent indexes after edit")

    val plotsNotInExpectedRegions =
        editedMonitoringPlots
            .filter { plot ->
              plot.permanentIndex != null &&
                  !plot.boundary.nearlyCoveredBy(expected[plot.permanentIndex]!!)
            }
            .map { it.permanentIndex!! }
            .sorted()

    assertEquals(emptyList<Int>(), plotsNotInExpectedRegions, "Plots not in expected regions")
  }

  /**
   * Asserts that each subzone contains the expected list of permanent indexes and non-permanent
   * plots. Requires that subzone names be unique across zones.
   *
   * @param expected Map of subzone names to sorted lists of expected permanent indexes. Use nulls
   *   to expect non-permanent plots.
   */
  private fun assertSubzonePermanentIndexes(
      site: AnyPlantingSiteModel,
      expected: Map<String, List<Int?>>,
  ) {
    val actual =
        site.strata
            .flatMap { zone ->
              zone.substrata.map { subzone ->
                subzone.name to
                    subzone.monitoringPlots
                        .map { it.permanentIndex }
                        .sortedBy { it ?: Int.MAX_VALUE }
              }
            }
            .toMap()

    assertEquals(expected, actual, "Permanent indexes in each subzone")
  }

  /**
   * Asserts that each subzone contains the expected list of plot numbers. Requires that subzone
   * names be unique across zones.
   *
   * @param expected Map of subzone names to sorted lists of expected plot numbers.
   */
  private fun assertSubzonePlotNumbers(
      site: AnyPlantingSiteModel,
      expected: Map<String, List<Long>>,
  ) {
    val actual =
        site.strata
            .flatMap { zone ->
              zone.substrata.map { subzone ->
                subzone.name to subzone.monitoringPlots.map { it.plotNumber }.sorted()
              }
            }
            .toMap()

    assertEquals(expected, actual, "Plot numbers in each subzone")
  }

  private data class ScenarioResults(
      val edited: ExistingPlantingSiteModel,
      val existing: ExistingPlantingSiteModel,
      val plantingSiteEdit: PlantingSiteEdit,
  )
}
