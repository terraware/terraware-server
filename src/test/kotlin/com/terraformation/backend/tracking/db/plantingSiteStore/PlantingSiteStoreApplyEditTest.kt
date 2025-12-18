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

      fun ExistingPlantingSiteModel.allStratumIds(): Set<StratumId> = strata.map { it.id }.toSet()

      fun ExistingPlantingSiteModel.allSubstratumIds(): Set<SubstratumId> =
          strata.flatMap { stratum -> stratum.substrata.map { it.id } }.toSet()

      assertNull(
          existing.countryCode,
          "Initial boundary spans 2 countries so country code should be null",
      )
      assertEquals(existing.allStratumIds(), edited.allStratumIds(), "Stratum IDs")
      assertEquals(existing.allSubstratumIds(), edited.allSubstratumIds(), "Substratum IDs")
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
                    stratum {
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
    fun `creates new strata`() {
      runScenario(
          newSite(width = 500),
          newSite(width = 750) {
            stratum(width = 500)
            stratum()
          },
      )
    }

    @Test
    fun `deletes existing strata`() {
      runScenario(
          initial =
              newSite(width = 750) {
                stratum(width = 500)
                stratum()
              },
          desired = newSite(width = 500),
      )
    }

    @Test
    fun `deletes existing substrata`() {
      runScenario(
          initial =
              newSite(width = 750) {
                stratum(width = 750) {
                  substratum(width = 500)
                  substratum()
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
      assertEquals(BigDecimal("20.0"), existing.strata[0].areaHa, "Stratum area before edit")
      assertEquals(BigDecimal("15.0"), edited.strata[0].areaHa, "Stratum area after edit")
      assertEquals(
          BigDecimal("20.0"),
          existing.strata[0].substrata[0].areaHa,
          "Substratum area before edit",
      )
      assertEquals(
          BigDecimal("15.0"),
          edited.strata[0].substrata[0].areaHa,
          "Substratum area after edit",
      )
    }

    @Test
    fun `optionally marks expanded substrata as not complete`() {
      runScenario(
          initial =
              newSite {
                stratum(numPermanent = 1) {
                  substratum(width = 250) {
                    plantingCompletedTime = Instant.EPOCH
                    permanent()
                  }
                  substratum(width = 250) { plantingCompletedTime = Instant.EPOCH }
                }
              },
          desired =
              newSite(height = 600) {
                stratum(numPermanent = 1) {
                  substratum(width = 250)
                  substratum(width = 250)
                }
              },
          expected =
              newSite(height = 600) {
                stratum(numPermanent = 1) {
                  substratum(width = 250) { plantingCompletedTime = Instant.EPOCH }
                  substratum(width = 250) { plantingCompletedTime = null }
                }
              },
          getSubstrataToMarkIncomplete = { existing ->
            // Ask it to mark just one of the two expanded substrata as incomplete.
            existing.strata[0].substrata.filter { it.name == "S2" }.map { it.id }.toSet()
          },
      )
    }

    @Test
    fun `updates stratum boundary modified time if geometry changed`() {
      runScenario(
          initial =
              newSite(width = 1000) {
                stratum(width = 500)
                stratum(width = 500)
              },
          desired =
              newSite(width = 1100) {
                stratum(width = 500)
                stratum(width = 600)
              },
      )

      val strataRows = strataDao.findAll().associateBy { it.name }
      assertEquals(
          Instant.EPOCH,
          strataRows["Z1"]?.boundaryModifiedTime,
          "Stratum with no boundary change",
      )
      assertEquals(editTime, strataRows["Z2"]?.boundaryModifiedTime, "Stratum with boundary change")
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
          initial = newSite { stratum(numPermanent = 1) { substratum { permanent() } } },
          desired = newSite { stratum(numPermanent = 1) },
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
      val expected = newSite(width = 1000) { stratum(numPermanent = 4) }
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
    fun `moves existing monitoring plots between substrata`() {
      val initial = newSite {
        stratum {
          substratum {
            permanent(x = 0)
            permanent(x = 300)
            permanent(x = 30)
            permanent(x = 330)
          }
        }
      }
      val expected = newSite {
        stratum(numPermanent = 4) {
          substratum(width = 250)
          substratum(width = 250)
        }
      }

      val (edited) = runScenario(initial = initial, desired = expected)

      assertSubstratumPermanentIndexes(edited, mapOf("S1" to listOf(1, 3), "S2" to listOf(2, 4)))
    }

    @Test
    fun `creates and adopts monitoring plots with permanent index renumbering`() {
      // This scenario is from the PRD and the edit calculation is covered in
      // PlantingSiteEditCalculatorV2Test.
      val initial =
          newSite(x = 0, width = 1000) {
            stratum(name = "A", x = 0, width = 500) {
              substratum {
                permanent(plotNumber = 1, x = 300, y = 0)
                permanent(plotNumber = 2, x = 300, y = 30)
                permanent(plotNumber = 4, x = 300, y = 60)
                permanent(plotNumber = 7, x = 300, y = 90)
              }
            }
            stratum(name = "B", x = 500, width = 500) {
              substratum {
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

      // 44% overlaps with old stratum A, 56% doesn't.
      val desired =
          newSite(x = 280, width = 500) {
            gridOrigin = point(1)
            stratum(name = "A", numPermanent = 11, numTemporary = 3) {}
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
    fun `moves existing monitoring plots between strata`() {
      val initial = newSite {
        stratum(name = "A") {
          substratum {
            permanent(plotNumber = 1, x = 100)
            permanent(plotNumber = 2, x = 400)
          }
        }
      }

      val desired = newSite {
        stratum(width = 300, name = "A", numPermanent = 1)
        stratum(name = "B", numPermanent = 1)
      }

      val (edited) = runScenario(initial = initial, desired = desired)

      assertSubstratumPermanentIndexes(edited, mapOf("S1" to listOf(1), "S2" to listOf(1)))
      assertSubstratumPlotNumbers(edited, mapOf("S1" to listOf(1L), "S2" to listOf(2L)))
    }

    @Test
    fun `retains stratum and substratum IDs on rename`() {
      val initial = newSite {
        stratum(name = "A", numPermanent = 1, width = 250) { substratum(name = "Substratum") }
        stratum(name = "B", numPermanent = 1, width = 250) { substratum(name = "Substratum") }
      }

      val desired = newSite {
        stratum(name = "C", stableId = StableId("A"), numPermanent = 1, width = 250) {
          substratum(name = "Substratum", stableId = StableId("A-Substratum"))
        }
        stratum(name = "D", stableId = StableId("B"), numPermanent = 1, width = 250) {
          substratum(name = "Substratum", stableId = StableId("B-Substratum"))
        }
      }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingStrata = existing.strata.associateBy { it.name }

      assertEquals(
          mapOf("C" to existingStrata["A"]!!.id, "D" to existingStrata["B"]!!.id),
          edited.strata.associate { it.name to it.id },
          "Stratum IDs",
      )
      assertEquals(
          mapOf(
              "C-Substratum" to existingStrata["A"]!!.substrata.first().id,
              "D-Substratum" to existingStrata["B"]!!.substrata.first().id,
          ),
          edited.strata.flatMap { it.substrata }.associate { it.fullName to it.id },
          "Substratum IDs",
      )
    }

    @Test
    fun `can swap stratum names`() {
      val initial = newSite {
        stratum(name = "A", width = 250)
        stratum(name = "B")
      }

      val desired = newSite {
        stratum(name = "A", stableId = StableId("B"), x = 250, width = 250) {
          substratum(stableId = StableId("B-S2"))
        }
        stratum(name = "B", stableId = StableId("A"), x = 0, width = 250) {
          substratum(stableId = StableId("A-S1"))
        }
      }

      val (edited, existing) = runScenario(initial, desired)

      assertEquals(
          mapOf("A" to StableId("B"), "B" to StableId("A")),
          edited.strata.associate { it.name to it.stableId },
          "Stable IDs for stratum names after name swap",
      )
      assertEquals(
          existing.strata.associate { it.stableId to it.id },
          edited.strata.associate { it.stableId to it.id },
          "Stratum IDs by stable ID after name swap",
      )
    }

    @Test
    fun `can swap substratum names`() {
      val initial = newSite {
        stratum {
          substratum(name = "S1", stableId = StableId("S1"), width = 250)
          substratum(name = "S2", stableId = StableId("S2"))
        }
      }

      val desired = newSite {
        stratum {
          substratum(name = "S1", stableId = StableId("S2"), x = 250, width = 250)
          substratum(name = "S2", stableId = StableId("S1"), x = 0, width = 250)
        }
      }

      val (edited, existing) = runScenario(initial, desired)

      assertEquals(
          mapOf("S1" to StableId("S2"), "S2" to StableId("S1")),
          edited.strata.single().substrata.associate { it.name to it.stableId },
          "Stable IDs for substratum names after name swap",
      )
      assertEquals(
          existing.strata.single().substrata.associate { it.stableId to it.id },
          edited.strata.single().substrata.associate { it.stableId to it.id },
          "Substratum IDs by stable ID after name swap",
      )
    }

    @Test
    fun `moves existing substrata between strata`() {
      val initial = newSite {
        stratum(name = "A", numPermanent = 2) {
          substratum(name = "Substratum 1", width = 250) { permanent() }
          substratum(name = "Substratum 2", width = 250) { permanent() }
        }
      }

      val desired = newSite {
        stratum(name = "A", numPermanent = 1, width = 250) { substratum(name = "Substratum 1") }
        stratum(name = "B", numPermanent = 1, width = 250) {
          substratum(name = "Substratum 2", stableId = StableId("A-Substratum 2"))
        }
      }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingSubstratum2 = existing.strata[0].substrata[1]
      val editedSubstratum2 = edited.strata[1].substrata[0]

      assertEquals(existingSubstratum2.id, editedSubstratum2.id, "ID of moved substratum")
      assertEquals(
          existingSubstratum2.monitoringPlots[0].copy(permanentIndex = 1),
          editedSubstratum2.monitoringPlots[0],
          "Monitoring plot in moved substratum",
      )
    }

    @Test
    fun `moves substratum from deleted stratum to new one, perhaps because the stratum was renamed`() {
      val initial = newSite {
        stratum(name = "A", numPermanent = 1) { substratum(name = "Substratum") { permanent() } }
      }

      val desired = newSite {
        stratum(name = "B", numPermanent = 1) {
          substratum(name = "Substratum", stableId = StableId("A-Substratum"))
        }
      }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingSubstratum = existing.strata[0].substrata[0]
      val editedSubstratum = edited.strata[0].substrata[0]

      assertEquals(existingSubstratum.id, editedSubstratum.id, "ID of moved substratum")
      assertEquals(
          existingSubstratum.monitoringPlots[0].copy(permanentIndex = 1),
          editedSubstratum.monitoringPlots[0],
          "Monitoring plot in moved substratum",
      )
    }

    private fun createSite(initial: NewPlantingSiteModel): ExistingPlantingSiteModel {
      clock.instant = Instant.EPOCH

      // createPlantingSite doesn't create monitoring plots since they are expected to be created
      // on demand later on, so we need to create them ourselves.
      val existingWithoutPlots =
          store.createPlantingSite(initial.copy(organizationId = organizationId))

      val maxPlotNumber =
          initial.strata.maxOfOrNull { initialStratum ->
            val existingStratum =
                existingWithoutPlots.strata.single { it.name == initialStratum.name }

            initialStratum.substrata.maxOfOrNull { initialSubstratum ->
              val existingSubstratum =
                  existingStratum.substrata.single { it.name == initialSubstratum.name }

              initialSubstratum.monitoringPlots.maxOfOrNull { initialPlot ->
                insertMonitoringPlot(
                    boundary = initialPlot.boundary,
                    isAvailable = initialPlot.isAvailable,
                    permanentIndex = initialPlot.permanentIndex,
                    plantingSiteId = existingWithoutPlots.id,
                    substratumId = existingSubstratum.id,
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
        getSubstrataToMarkIncomplete: (ExistingPlantingSiteModel) -> Set<SubstratumId> = {
          emptySet()
        },
    ): ScenarioResults {
      val existing = createSite(initial)

      val plantingSiteEdit = calculateSiteEdit(existing, desired)

      clock.instant = editTime
      val substrataToMarkIncomplete = getSubstrataToMarkIncomplete(existing)
      val edited = store.applyPlantingSiteEdit(plantingSiteEdit, substrataToMarkIncomplete)

      fun NewPlantingSiteModel.withoutMonitoringPlots() =
          copy(
              strata =
                  strata.map { stratum ->
                    stratum.copy(
                        substrata =
                            stratum.substrata.map { substratum ->
                              substratum.copy(monitoringPlots = emptyList())
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
          edited.strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum -> substratum.monitoringPlots }
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

      val editedStratumHistories =
          stratumHistoriesDao.fetchByPlantingSiteHistoryId(editedSiteHistory.id!!)

      assertEquals(
          edited.strata.size,
          editedStratumHistories.size,
          "Number of stratum histories from edit",
      )
      assertEquals(
          edited.strata
              .map { stratum ->
                StratumHistoriesRow(
                    areaHa = stratum.areaHa,
                    boundary = stratum.boundary,
                    name = stratum.name,
                    plantingSiteHistoryId = editedSiteHistory.id,
                    stratumId = stratum.id,
                    stableId = stratum.stableId,
                )
              }
              .toSet(),
          editedStratumHistories.map { it.copy(id = null) }.toSet(),
          "Stratum histories from edit",
      )

      val editedSubstratumHistories =
          substratumHistoriesDao.fetchByStratumHistoryId(
              *editedStratumHistories.map { it.id!! }.toTypedArray()
          )

      assertTableEquals(
          edited.strata.flatMap { stratum ->
            val stratumHistoryId = editedStratumHistories.first { it.stratumId == stratum.id }.id
            stratum.substrata.map { substratum ->
              SubstratumHistoriesRecord(
                  areaHa = substratum.areaHa,
                  boundary = substratum.boundary,
                  fullName = substratum.fullName,
                  name = substratum.name,
                  substratumId = substratum.id,
                  stratumHistoryId = stratumHistoryId,
                  stableId = substratum.stableId,
              )
            }
          },
          "Substratum histories from edit",
          where =
              SUBSTRATUM_HISTORIES.stratumHistories.PLANTING_SITE_HISTORY_ID.eq(
                  editedSiteHistory.id
              ),
      )

      val substratumPlotIds =
          edited.strata
              .flatMap { stratum ->
                stratum.substrata.flatMap { substratum -> substratum.monitoringPlots.map { it.id } }
              }
              .toSet()

      val ejectedPlotHistories =
          existing.strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum ->
              substratum.monitoringPlots
                  .filter { it.id !in substratumPlotIds }
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

      val substratumPlotHistories =
          edited.strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum ->
              val substratumHistoryId =
                  editedSubstratumHistories.first { it.substratumId == substratum.id }.id
              substratum.monitoringPlots.map { plot ->
                MonitoringPlotHistoriesRecord(
                    createdBy = user.userId,
                    createdTime = editTime,
                    monitoringPlotId = plot.id,
                    plantingSiteHistoryId = editedSiteHistory.id,
                    plantingSiteId = edited.id,
                    substratumHistoryId = substratumHistoryId,
                    substratumId = substratum.id,
                )
              }
            }
          }

      val expectedPlotHistories = ejectedPlotHistories + substratumPlotHistories

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
        edited.strata.flatMap { stratum ->
          stratum.substrata.flatMap { substratum -> substratum.monitoringPlots }
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
   * Asserts that each substratum contains the expected list of permanent indexes and non-permanent
   * plots. Requires that substratum names be unique across strata.
   *
   * @param expected Map of substratum names to sorted lists of expected permanent indexes. Use
   *   nulls to expect non-permanent plots.
   */
  private fun assertSubstratumPermanentIndexes(
      site: AnyPlantingSiteModel,
      expected: Map<String, List<Int?>>,
  ) {
    val actual =
        site.strata
            .flatMap { stratum ->
              stratum.substrata.map { substratum ->
                substratum.name to
                    substratum.monitoringPlots
                        .map { it.permanentIndex }
                        .sortedBy { it ?: Int.MAX_VALUE }
              }
            }
            .toMap()

    assertEquals(expected, actual, "Permanent indexes in each substratum")
  }

  /**
   * Asserts that each substratum contains the expected list of plot numbers. Requires that
   * substratum names be unique across strata.
   *
   * @param expected Map of substratum names to sorted lists of expected plot numbers.
   */
  private fun assertSubstratumPlotNumbers(
      site: AnyPlantingSiteModel,
      expected: Map<String, List<Long>>,
  ) {
    val actual =
        site.strata
            .flatMap { stratum ->
              stratum.substrata.map { substratum ->
                substratum.name to substratum.monitoringPlots.map { it.plotNumber }.sorted()
              }
            }
            .toMap()

    assertEquals(expected, actual, "Plot numbers in each substratum")
  }

  private data class ScenarioResults(
      val edited: ExistingPlantingSiteModel,
      val existing: ExistingPlantingSiteModel,
      val plantingSiteEdit: PlantingSiteEdit,
  )
}
