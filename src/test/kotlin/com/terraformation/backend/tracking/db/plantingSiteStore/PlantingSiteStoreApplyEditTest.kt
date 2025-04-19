package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.NumericIdentifierType
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZoneHistoriesRow
import com.terraformation.backend.db.tracking.tables.records.MonitoringPlotHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSubzoneHistoriesRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_HISTORIES
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculatorTest
import com.terraformation.backend.tracking.edit.PlantingZoneEdit
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

      fun ExistingPlantingSiteModel.allZoneIds(): Set<PlantingZoneId> =
          plantingZones.map { it.id }.toSet()

      fun ExistingPlantingSiteModel.allSubzoneIds(): Set<PlantingSubzoneId> =
          plantingZones.flatMap { zone -> zone.plantingSubzones.map { it.id } }.toSet()

      assertNull(
          existing.countryCode, "Initial boundary spans 2 countries so country code should be null")
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
                  })

      assertEquals(newErrorMargin, edited.plantingZones[0].errorMargin, "Error margin")
      assertEquals(newStudentsT, edited.plantingZones[0].studentsT, "Student's t")
      assertEquals(
          newTargetPlantingDensity,
          edited.plantingZones[0].targetPlantingDensity,
          "Target planting density")
      assertEquals(newVariance, edited.plantingZones[0].variance, "Variance")
    }

    @Test
    fun `creates new zones`() {
      runScenario(
          newSite(width = 500),
          newSite(width = 750) {
            zone(width = 500)
            zone()
          })
    }

    @Test
    fun `deletes existing zones`() {
      runScenario(
          initial =
              newSite(width = 750) {
                zone(width = 500)
                zone()
              },
          desired = newSite(width = 500))
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
          desired = newSite(width = 500))
    }

    @Test
    fun `updates plantable area totals when exclusion geometry changes`() {
      val (edited, existing) =
          runScenario(
              newSite { exclusion = rectangle(width = 100, height = 500) },
              newSite { exclusion = rectangle(width = 200, height = 500) })

      assertEquals(BigDecimal("20.0"), existing.areaHa, "Site area before edit")
      assertEquals(BigDecimal("15.0"), edited.areaHa, "Site area after edit")
      assertEquals(BigDecimal("20.0"), existing.plantingZones[0].areaHa, "Zone area before edit")
      assertEquals(BigDecimal("15.0"), edited.plantingZones[0].areaHa, "Zone area after edit")
      assertEquals(
          BigDecimal("20.0"),
          existing.plantingZones[0].plantingSubzones[0].areaHa,
          "Subzone area before edit")
      assertEquals(
          BigDecimal("15.0"),
          edited.plantingZones[0].plantingSubzones[0].areaHa,
          "Subzone area after edit")
    }

    @Test
    fun `optionally marks expanded subzones as not complete`() {
      runScenario(
          initial =
              newSite {
                zone(numPermanent = 1) {
                  subzone(width = 250) {
                    plantingCompletedTime = Instant.EPOCH
                    cluster()
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
            existing.plantingZones[0]
                .plantingSubzones
                .filter { it.name == "S2" }
                .map { it.id }
                .toSet()
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
          Instant.EPOCH, zonesRows["Z1"]?.boundaryModifiedTime, "Zone with no boundary change")
      assertEquals(editTime, zonesRows["Z2"]?.boundaryModifiedTime, "Zone with boundary change")
    }

    @Test
    fun `publishes event if site was edited`() {
      val results = runScenario(newSite(), newSite(width = 600))

      val monitoringPlotIds =
          results.edited.plantingZones[0].plantingSubzones[0].monitoringPlots.map { it.id }.toSet()

      eventPublisher.assertEventPublished(
          PlantingSiteMapEditedEvent(
              results.edited,
              results.plantingSiteEdit,
              ReplacementResult(monitoringPlotIds, emptySet())))
    }

    @Test
    fun `does not publish event if edit was a no-op`() {
      runScenario(
          initial = newSite { zone(numPermanent = 1) { subzone { cluster() } } },
          desired = newSite { zone(numPermanent = 1) })

      eventPublisher.assertEventNotPublished<PlantingSiteMapEditedEvent>()
    }

    @Test
    fun `throws exception if edit results in duplicate permanent cluster numbers`() {
      val existing = createSite(newSite())

      assertThrows<IllegalStateException> {
        store.applyPlantingSiteEdit(
            PlantingSiteEdit(
                areaHaDifference = BigDecimal.ZERO,
                desiredModel = existing,
                existingModel = existing,
                plantingZoneEdits =
                    listOf(
                        PlantingZoneEdit.Update(
                            addedRegion = rectangle(0),
                            areaHaDifference = BigDecimal.ZERO,
                            desiredModel = existing.plantingZones[0],
                            existingModel = existing.plantingZones[0],
                            monitoringPlotEdits =
                                listOf(
                                    MonitoringPlotEdit.Create(existing.boundary!!, 1),
                                    MonitoringPlotEdit.Create(existing.boundary!!, 1),
                                    MonitoringPlotEdit.Create(existing.boundary!!, 2),
                                    MonitoringPlotEdit.Create(existing.boundary!!, 2),
                                ),
                            plantingSubzoneEdits = emptyList(),
                            removedRegion = rectangle(0),
                        )),
            ))
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
              expectedPlotCounts = listOf(existingArea to 2, newArea to 2))

      assertClusterRegions(
          edited,
          mapOf(
              1 to existingArea,
              2 to newArea,
              3 to existingArea,
              4 to newArea,
          ))
    }

    @Test
    fun `moves existing monitoring plots between subzones`() {
      val initial = newSite {
        zone {
          subzone {
            cluster(x = 0)
            cluster(x = 300)
            cluster(x = 30)
            cluster(x = 330)
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

      assertSubzoneClusters(edited, mapOf("S1" to listOf(1, 3), "S2" to listOf(2, 4)))
    }

    @Test
    fun `creates and adopts monitoring plots with permanent cluster renumbering`() {
      // This scenario is from the PRD and the edit calculation is covered in
      // PlantingSiteEditCalculatorV2Test.
      val initial =
          newSite(x = 0, width = 1000) {
            zone(name = "A", x = 0, width = 500) {
              subzone {
                cluster(plotNumber = 1, x = 300, y = 0)
                cluster(plotNumber = 2, x = 300, y = 30)
                cluster(plotNumber = 4, x = 300, y = 60)
                cluster(plotNumber = 7, x = 300, y = 90)
              }
            }
            zone(name = "B", x = 500, width = 500) {
              subzone {
                cluster(plotNumber = 16, x = 600, y = 0)
                cluster(plotNumber = 18, x = 600, y = 30)
                cluster(plotNumber = 19, x = 600, y = 60)
                cluster(plotNumber = 23, x = 600, y = 90)
                cluster(plotNumber = 25, x = 600, y = 120)
                cluster(plotNumber = 26, x = 600, y = 150)
                cluster(plotNumber = 27, x = 600, y = 180)
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
          monitoringPlotsDao.findAll().associate { it.plotNumber to it.permanentCluster },
          "Permanent cluster numbers for each plot number")
    }

    @Test
    fun `moves existing monitoring plots between planting zones`() {
      val initial = newSite {
        zone(name = "A") {
          subzone {
            cluster(plotNumber = 1, x = 100)
            cluster(plotNumber = 2, x = 400)
          }
        }
      }

      val desired = newSite {
        zone(width = 300, name = "A", numPermanent = 1)
        zone(name = "B", numPermanent = 1)
      }

      val (edited) = runScenario(initial = initial, desired = desired)

      assertSubzoneClusters(edited, mapOf("S1" to listOf(1), "S2" to listOf(1)))
      assertSubzonePlotNumbers(edited, mapOf("S1" to listOf(1L), "S2" to listOf(2L)))
    }

    @Test
    fun `moves existing subzones between zones`() {
      val initial = newSite {
        zone(name = "A", numPermanent = 2) {
          subzone(name = "Subzone 1", width = 250) { cluster() }
          subzone(name = "Subzone 2", width = 250) { cluster() }
        }
      }

      val desired = newSite {
        zone(name = "A", numPermanent = 1, width = 250) { subzone(name = "Subzone 1") }
        zone(name = "B", numPermanent = 1, width = 250) { subzone(name = "Subzone 2") }
      }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingSubzone2 = existing.plantingZones[0].plantingSubzones[1]
      val editedSubzone2 = edited.plantingZones[1].plantingSubzones[0]

      assertEquals(existingSubzone2.id, editedSubzone2.id, "ID of moved subzone")
      assertEquals(
          existingSubzone2.monitoringPlots[0].copy(permanentCluster = 1),
          editedSubzone2.monitoringPlots[0],
          "Monitoring plot in moved subzone")
    }

    @Test
    fun `moves subzone from deleted zone to new one, perhaps because the zone was renamed`() {
      val initial = newSite {
        zone(name = "A", numPermanent = 1) { subzone(name = "Subzone") { cluster() } }
      }

      val desired = newSite { zone(name = "B", numPermanent = 1) { subzone(name = "Subzone") } }

      val (edited, existing) = runScenario(initial = initial, desired = desired)

      val existingSubzone = existing.plantingZones[0].plantingSubzones[0]
      val editedSubzone = edited.plantingZones[0].plantingSubzones[0]

      assertEquals(existingSubzone.id, editedSubzone.id, "ID of moved subzone")
      assertEquals(
          existingSubzone.monitoringPlots[0].copy(permanentCluster = 1),
          editedSubzone.monitoringPlots[0],
          "Monitoring plot in moved subzone")
    }

    private fun createSite(initial: NewPlantingSiteModel): ExistingPlantingSiteModel {
      clock.instant = Instant.EPOCH

      // createPlantingSite doesn't create monitoring plots since they are expected to be created
      // on demand later on, so we need to create them ourselves.
      val existingWithoutPlots =
          store.createPlantingSite(initial.copy(organizationId = organizationId))

      val maxPlotNumber =
          initial.plantingZones.maxOfOrNull { initialZone ->
            val existingZone =
                existingWithoutPlots.plantingZones.single { it.name == initialZone.name }

            initialZone.plantingSubzones.maxOfOrNull { initialSubzone ->
              val existingSubzone =
                  existingZone.plantingSubzones.single { it.name == initialSubzone.name }

              initialSubzone.monitoringPlots.maxOfOrNull { initialPlot ->
                insertMonitoringPlot(
                    boundary = initialPlot.boundary,
                    isAvailable = initialPlot.isAvailable,
                    permanentCluster = initialPlot.permanentCluster,
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
                organizationId, NumericIdentifierType.PlotNumber)
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
        getSubzonesToMarkIncomplete: (ExistingPlantingSiteModel) -> Set<PlantingSubzoneId> = {
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
              plantingZones =
                  plantingZones.map { zone ->
                    zone.copy(
                        plantingSubzones =
                            zone.plantingSubzones.map { subzone ->
                              subzone.copy(monitoringPlots = emptyList())
                            })
                  })

      val expectedWithoutMonitoringPlots = expected.withoutMonitoringPlots()
      val editedWithoutMonitoringPlots = edited.toNew().withoutMonitoringPlots()
      if (!expectedWithoutMonitoringPlots.equals(editedWithoutMonitoringPlots, 0.00001)) {
        assertEquals(
            expectedWithoutMonitoringPlots,
            editedWithoutMonitoringPlots,
            "Planting site after edit")
      }

      val editedMonitoringPlots =
          edited.plantingZones.flatMap { zone ->
            zone.plantingSubzones.flatMap { subzone -> subzone.monitoringPlots }
          }

      if (expectedPlotCounts != null) {
        val actualPlotCounts =
            expectedPlotCounts.map { (boundary, _) ->
              boundary to editedMonitoringPlots.count { it.boundary.nearlyCoveredBy(boundary) }
            }

        assertEquals(
            expectedPlotCounts,
            actualPlotCounts,
            "Number of monitoring plots in regions of edited site")
      }

      if (plantingSiteEdit.plantingZoneEdits.isNotEmpty()) {
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
          "Existing site history")
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
          "Edited site history")

      val editedZoneHistories =
          plantingZoneHistoriesDao.fetchByPlantingSiteHistoryId(editedSiteHistory.id!!)

      assertEquals(
          edited.plantingZones.size,
          editedZoneHistories.size,
          "Number of planting zone histories from edit")
      assertEquals(
          edited.plantingZones
              .map { zone ->
                PlantingZoneHistoriesRow(
                    areaHa = zone.areaHa,
                    boundary = zone.boundary,
                    name = zone.name,
                    plantingSiteHistoryId = editedSiteHistory.id,
                    plantingZoneId = zone.id,
                )
              }
              .toSet(),
          editedZoneHistories.map { it.copy(id = null) }.toSet(),
          "Planting zone histories from edit")

      val editedSubzoneHistories =
          plantingSubzoneHistoriesDao.fetchByPlantingZoneHistoryId(
              *editedZoneHistories.map { it.id!! }.toTypedArray())

      assertTableEquals(
          edited.plantingZones.flatMap { zone ->
            val plantingZoneHistoryId =
                editedZoneHistories.first { it.plantingZoneId == zone.id }.id
            zone.plantingSubzones.map { subzone ->
              PlantingSubzoneHistoriesRecord(
                  areaHa = subzone.areaHa,
                  boundary = subzone.boundary,
                  fullName = subzone.fullName,
                  name = subzone.name,
                  plantingSubzoneId = subzone.id,
                  plantingZoneHistoryId = plantingZoneHistoryId,
              )
            }
          },
          "Planting subzone histories from edit",
          where =
              PLANTING_SUBZONE_HISTORIES.plantingZoneHistories.PLANTING_SITE_HISTORY_ID.eq(
                  editedSiteHistory.id))

      val subzonePlotIds =
          edited.plantingZones
              .flatMap { zone ->
                zone.plantingSubzones.flatMap { subzone -> subzone.monitoringPlots.map { it.id } }
              }
              .toSet()

      val ejectedPlotHistories =
          existing.plantingZones.flatMap { zone ->
            zone.plantingSubzones.flatMap { subzone ->
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
          edited.plantingZones.flatMap { zone ->
            zone.plantingSubzones.flatMap { subzone ->
              val plantingSubzoneHistoryId =
                  editedSubzoneHistories.first { it.plantingSubzoneId == subzone.id }.id
              subzone.monitoringPlots.map { plot ->
                MonitoringPlotHistoriesRecord(
                    createdBy = user.userId,
                    createdTime = editTime,
                    monitoringPlotId = plot.id,
                    plantingSiteHistoryId = editedSiteHistory.id,
                    plantingSiteId = edited.id,
                    plantingSubzoneHistoryId = plantingSubzoneHistoryId,
                    plantingSubzoneId = subzone.id,
                )
              }
            }
          }

      val expectedPlotHistories = ejectedPlotHistories + subzonePlotHistories

      if (expectedPlotHistories.isNotEmpty()) {
        assertTableEquals(
            expectedPlotHistories,
            "Monitoring plot histories from edit",
            where = MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(editedSiteHistory.id))
      }
    }
  }

  /**
   * Asserts that each permanent cluster is located somewhere in a particular region. This is used
   * to test random cluster placement.
   *
   * @param expected Map of permanent cluster numbers to expected regions.
   */
  private fun assertClusterRegions(
      edited: ExistingPlantingSiteModel,
      expected: Map<Int, Geometry>
  ) {
    val editedMonitoringPlots =
        edited.plantingZones.flatMap { zone ->
          zone.plantingSubzones.flatMap { subzone -> subzone.monitoringPlots }
        }
    val editedClusterNumbers = editedMonitoringPlots.mapNotNull { it.permanentCluster }.sorted()
    val expectedClusterNumbers = expected.keys.sorted()
    assertEquals(
        expectedClusterNumbers, editedClusterNumbers, "Permanent cluster numbers after edit")

    val clustersNotInExpectedRegions =
        editedMonitoringPlots
            .filter { plot ->
              plot.permanentCluster != null &&
                  !plot.boundary.nearlyCoveredBy(expected[plot.permanentCluster]!!)
            }
            .map { it.permanentCluster!! }
            .sorted()

    assertEquals(emptyList<Int>(), clustersNotInExpectedRegions, "Clusters not in expected regions")
  }

  /**
   * Asserts that each subzone contains the expected list of permanent cluster numbers and
   * non-permanent plots. Requires that subzone names be unique across zones.
   *
   * @param expected Map of subzone names to sorted lists of expected permanent cluster numbers. Use
   *   nulls to expect non-permanent plots.
   */
  private fun assertSubzoneClusters(site: AnyPlantingSiteModel, expected: Map<String, List<Int?>>) {
    val actual =
        site.plantingZones
            .flatMap { zone ->
              zone.plantingSubzones.map { subzone ->
                subzone.name to
                    subzone.monitoringPlots
                        .map { it.permanentCluster }
                        .sortedBy { it ?: Int.MAX_VALUE }
              }
            }
            .toMap()

    assertEquals(expected, actual, "Cluster numbers in each subzone")
  }

  /**
   * Asserts that each subzone contains the expected list of plot numbers. Requires that subzone
   * names be unique across zones.
   *
   * @param expected Map of subzone names to sorted lists of expected plot numbers.
   */
  private fun assertSubzonePlotNumbers(
      site: AnyPlantingSiteModel,
      expected: Map<String, List<Long>>
  ) {
    val actual =
        site.plantingZones
            .flatMap { zone ->
              zone.plantingSubzones.map { subzone ->
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
