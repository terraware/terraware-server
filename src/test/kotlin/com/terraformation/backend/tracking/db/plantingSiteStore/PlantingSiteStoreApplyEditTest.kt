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
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculatorV1
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculatorV1Test
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
 * construct [PlantingSiteEdit] objects, but instead use [PlantingSiteEditCalculatorV1] and rely on
 * the coverage in [PlantingSiteEditCalculatorV1Test] to verify that the calculated edits would be
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
    fun `creates new zones`() {
      runScenario(
          newSite(width = 500),
          newSite(width = 750) {
            zone(width = 500)
            zone()
          })
    }

    @Test
    fun `creates new subzones in existing zones`() {
      runScenario(
          initial = newSite(width = 500) { zone(numPermanent = 7) },
          desired =
              newSite(width = 750) {
                zone {
                  subzone(width = 500)
                  subzone()
                }
              },
          expected =
              newSite(width = 750) {
                zone(numPermanent = 10, extraPermanent = 3) {
                  subzone(width = 500)
                  subzone()
                }
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
          desired = newSite(width = 500),
          getPlantedSubzoneIds = { existing ->
            setOf(existing.plantingZones[0].plantingSubzones[0].id)
          })
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
          getPlantedSubzoneIds = { existing ->
            setOf(existing.plantingZones[0].plantingSubzones[0].id)
          })
    }

    @Test
    fun `creates new permanent clusters in added area`() {
      runScenario(
          initial =
              newSite(width = 500) {
                zone(numPermanent = 7) { subzone { repeat(7) { cluster() } } }
              },
          desired = newSite(width = 750),
          expected = newSite(width = 750) { zone(numPermanent = 10, extraPermanent = 3) },
          expectedPlotCounts =
              listOf(
                  rectangle(x = 0, width = 500, height = 500) to 7,
                  rectangle(x = 500, width = 250, height = 500) to 3,
              ))
    }

    @Test
    fun `uses random cluster numbers for newly added clusters, renumbering existing clusters if needed`() {
      val clusterNumbersFoundInInitialArea = mutableSetOf<Int>()
      val clusterNumbersFoundInAddedArea = mutableSetOf<Int>()
      val maxAttempts = 100
      var currentAttempt = 0
      val initialArea = rectangle(x = 0, width = 500, height = 500)
      val addedArea = rectangle(x = 500, width = 500, height = 500)

      // Initially, the site has an existing area with two clusters numbered 1 and 2.
      //
      // We're editing it to double its size, which means there should be two new clusters created.
      // The numbers of those clusters should be random, and the numbers of the initial clusters
      // should be updated to make room for them. For example, if the newly-added clusters end up
      // with numbers 1 and 3 randomly, we would end up with a result of
      //
      // Cluster 1: first new cluster in added area
      // Cluster 2: the cluster formerly known as 1, in the initial area, renumbered because the
      //            first new cluster in the added area was selected to take its position in the
      //            list.
      // Cluster 3: second new cluster in added area
      // Cluster 4: the cluster formerly known as 2, in the initial area, renumbered because there
      //            were two newly-created clusters with cluster numbers less than or equal to its
      //            former cluster number.
      //
      // If the newly-added clusters end up with numbers 2 and 4 randomly, we'd get
      //
      // Cluster 1: the original cluster 1 in the initial area
      // Cluster 2: first new cluster in added area
      // Cluster 3: the cluster formerly known as 2, in the initial area, renumbered because we
      //            needed to insert a new cluster at position 2
      // Cluster 4: second new cluster in added area
      //
      // Each of the two new clusters should be able to appear in any of the four positions. To test
      // that that's the case, we run the edit a bunch of times until we see that the initial two
      // clusters have, on various runs, occupied all four possible cluster numbers, and that the
      // newly-added clusters have done the same. This verifies that (a) the selection isn't the
      // same each time, and (b) the existing clusters are correctly renumbered as needed.

      while (++currentAttempt < maxAttempts &&
          (clusterNumbersFoundInAddedArea != setOf(1, 2, 3, 4) ||
              clusterNumbersFoundInInitialArea != setOf(1, 2, 3, 4))) {
        val (edited) =
            runScenario(
                initial =
                    newSite(width = 500) {
                      name = "Site $currentAttempt"
                      nextPlotNumber =
                          identifierGenerator.generateNumericIdentifier(
                              inserted.organizationId, NumericIdentifierType.PlotNumber)
                      zone(numPermanent = 2) {
                        subzone {
                          cluster()
                          cluster()
                        }
                      }
                    },
                desired = newSite(width = 1000),
                expected =
                    newSite(width = 1000) {
                      name = "Site $currentAttempt"
                      zone(numPermanent = 4, extraPermanent = 2)
                    },
                expectedPlotCounts =
                    listOf(
                        initialArea to 2,
                        addedArea to 2,
                    ))

        val monitoringPlots = edited.plantingZones[0].plantingSubzones[0].monitoringPlots

        monitoringPlots.forEach { plot ->
          plot.permanentCluster?.let { permanentCluster ->
            if (plot.boundary.nearlyCoveredBy(addedArea)) {
              clusterNumbersFoundInAddedArea.add(permanentCluster)
            } else {
              clusterNumbersFoundInInitialArea.add(permanentCluster)
            }
          }
        }

        val plotCountsByClusterNumber =
            monitoringPlots.groupBy { it.permanentCluster }.mapValues { (_, plots) -> plots.size }

        assertEquals(
            mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 1),
            plotCountsByClusterNumber,
            "Number of plots with each cluster number after edit")
      }

      assertEquals(
          setOf(1, 2, 3, 4),
          clusterNumbersFoundInAddedArea,
          "Cluster numbers assigned to newly-added clusters across all runs")
      assertEquals(
          setOf(1, 2, 3, 4),
          clusterNumbersFoundInInitialArea,
          "Cluster numbers assigned to existing clusters after edit across all runs")
    }

    @Test
    fun `marks existing temporary plots as unavailable if they are outside the new usable area`() {
      val exclusionAreaPlotNumber = 1L
      val plantableAreaPlotNumber = 2L
      val (edited) =
          runScenario(
              newSite {
                zone {
                  subzone {
                    plot(plotNumber = exclusionAreaPlotNumber)
                    plot(plotNumber = plantableAreaPlotNumber)
                  }
                }
              },
              newSite {
                exclusion = rectangle(10)
                zone()
              })

      assertEquals(
          mapOf(exclusionAreaPlotNumber to false, plantableAreaPlotNumber to true),
          edited.plantingZones[0].plantingSubzones[0].monitoringPlots.associate {
            it.plotNumber to it.isAvailable
          },
          "isAvailable flags")
    }

    @Test
    fun `destroys permanent plot if no longer in plantable area`() {
      val (edited) =
          runScenario(
              newSite {
                zone {
                  subzone {
                    cluster()
                    cluster()
                  }
                }
              },
              newSite {
                exclusion = rectangle(10)
                zone()
              })

      assertEquals(
          mapOf(1L to null, 2L to 2),
          edited.plantingZones[0].plantingSubzones[0].monitoringPlots.associate {
            it.plotNumber to it.permanentCluster
          },
          "Cluster numbers of monitoring plots")
      assertEquals(
          mapOf(1L to false, 2L to true),
          edited.plantingZones[0].plantingSubzones[0].monitoringPlots.associate {
            it.plotNumber to it.isAvailable
          },
          "isAvailable flags")
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
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                }
              },
          desired =
              newSite(height = 600) {
                zone {
                  subzone(width = 250)
                  subzone(width = 250)
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
          expected =
              newSite(height = 600) {
                zone(numPermanent = 2, extraPermanent = 1) {
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                  subzone(width = 250) { plantingCompletedTime = null }
                }
              },
      )
    }

    @Test
    fun `does not mark non-expanded subzones as not complete`() {
      runScenario(
          initial =
              newSite {
                zone(numPermanent = 1) {
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                }
              },
          desired =
              newSite(width = 600) {
                zone {
                  subzone(width = 250)
                  subzone(width = 350)
                }
              },
          getSubzonesToMarkIncomplete = { existing ->
            // Ask it to mark all the subzones as incomplete; only the one that grew should actually
            // be marked.
            existing.plantingZones[0].plantingSubzones.map { it.id }.toSet()
          },
          expected =
              newSite(width = 600) {
                zone(numPermanent = 2, extraPermanent = 1) {
                  subzone(width = 250) { plantingCompletedTime = Instant.EPOCH }
                  subzone(width = 350) { plantingCompletedTime = null }
                }
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
          expected =
              newSite(width = 1100) {
                zone(width = 500)
                zone(width = 600, numPermanent = 9, extraPermanent = 1)
              },
      )

      val zonesRows = plantingZonesDao.findAll().associateBy { it.name }
      assertEquals(
          Instant.EPOCH, zonesRows["Z1"]?.boundaryModifiedTime, "Zone with no boundary change")
      assertEquals(editTime, zonesRows["Z2"]?.boundaryModifiedTime, "Zone with boundary change")
    }

    @Test
    fun `publishes event if site was edited`() {
      val results =
          runScenario(
              newSite(),
              newSite(width = 600),
              newSite(width = 600) { zone(numPermanent = 9, extraPermanent = 1) })

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
      runScenario(newSite(), newSite())

      eventPublisher.assertEventNotPublished<PlantingSiteMapEditedEvent>()
    }

    @Test
    fun `throws exception if edit has problems`() {
      assertThrows<PlantingSiteMapInvalidException> {
        runScenario(
            newSite(),
            newSite {
              zone(width = 250)
              zone()
            })
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

    /**
     * Runs a scenario and asserts that the results are correct.
     * 1. Creates a new site with an initial configuration
     * 2. Calculates the edit to turn it into a desired configuration
     * 3. Applies the edit
     * 4. Compares the result to an expected configuration
     *
     * Since monitoring plots can be created at random locations, we can't compare them to a fix
     * list of expected values. Instead, we assert that there are the expected number of plots in
     * specific regions.
     */
    private fun runScenario(
        initial: NewPlantingSiteModel,
        desired: NewPlantingSiteModel,
        expected: NewPlantingSiteModel = desired,
        expectedPlotCounts: List<Pair<Geometry, Int>>? = null,
        getPlantedSubzoneIds: (ExistingPlantingSiteModel) -> Set<PlantingSubzoneId> = { existing ->
          existing.plantingZones.flatMap { zone -> zone.plantingSubzones.map { it.id } }.toSet()
        },
        getSubzonesToMarkIncomplete: (ExistingPlantingSiteModel) -> Set<PlantingSubzoneId> = {
          emptySet()
        },
    ): ScenarioResults {
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
                    permanentClusterSubplot = initialPlot.permanentClusterSubplot,
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
      val subzonesToMarkIncomplete = getSubzonesToMarkIncomplete(existing)

      clock.instant = editTime
      val plantingSiteEdit = calculateSiteEdit(existing, desired, getPlantedSubzoneIds(existing))
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

      if (!expected
          .withoutMonitoringPlots()
          .equals(edited.toNew().withoutMonitoringPlots(), 0.00001)) {
        assertEquals(expected, edited.toNew(), "Planting site after edit")
      }

      if (plantingSiteEdit.plantingZoneEdits.isNotEmpty()) {
        assertHistories(existing, edited)
      }

      if (expectedPlotCounts != null) {
        val actualPlotCounts =
            expectedPlotCounts.map { (boundary, _) ->
              boundary to
                  edited.plantingZones.sumOf { zone ->
                    zone.plantingSubzones.sumOf { subzone ->
                      subzone.monitoringPlots.count { plot ->
                        plot.boundary.nearlyCoveredBy(boundary)
                      }
                    }
                  }
            }

        assertEquals(
            expectedPlotCounts,
            actualPlotCounts,
            "Number of monitoring plots in regions of edited site")
      }

      return ScenarioResults(edited, existing, plantingSiteEdit)
    }

    private fun calculateSiteEdit(
        existing: ExistingPlantingSiteModel,
        desired: AnyPlantingSiteModel,
        plantedSubzoneIds: Set<PlantingSubzoneId> = emptySet(),
    ): PlantingSiteEdit =
        PlantingSiteEditCalculatorV1(existing, desired, plantedSubzoneIds).calculateSiteEdit()

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
                    plantingSiteHistoryId = editedSiteHistory.id,
                    plantingZoneId = zone.id,
                    name = zone.name,
                    boundary = zone.boundary,
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
                  plantingZoneHistoryId = plantingZoneHistoryId,
                  plantingSubzoneId = subzone.id,
                  name = subzone.name,
                  fullName = subzone.fullName,
                  boundary = subzone.boundary,
              )
            }
          },
          "Planting subzone histories from edit",
          where =
              PLANTING_SUBZONE_HISTORIES.plantingZoneHistories.PLANTING_SITE_HISTORY_ID.eq(
                  editedSiteHistory.id))

      val expectedPlotHistories =
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

      if (expectedPlotHistories.isNotEmpty()) {
        assertTableEquals(
            expectedPlotHistories,
            "Monitoring plot histories from edit",
            where = MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(editedSiteHistory.id))
      }
    }
  }

  private data class ScenarioResults(
      val edited: ExistingPlantingSiteModel,
      val existing: ExistingPlantingSiteModel,
      val plantingSiteEdit: PlantingSiteEdit,
  )
}
