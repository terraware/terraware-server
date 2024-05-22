package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzoneHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZoneHistoriesRow
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.PlantingSiteEditInvalidException
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculatorTest
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.NewPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.util.nearlyCoveredBy
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Disabled
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
internal class PlantingSiteStoreApplyEditTest : PlantingSiteStoreTest() {
  private val editTime = Instant.ofEpochSecond(10000)

  @Nested
  inner class ApplyPlantingSiteEdit {
    @Test
    fun `updates existing boundaries`() {
      val (existing, edited) =
          runScenario(
              initial =
                  newSite {
                    exclusion = rectangle(10)
                    gridOrigin = point(1)
                  },
              desired =
                  newSite(x = 10) {
                    exclusion = rectangle(20)
                    gridOrigin = point(10, 0)
                  },
              expected =
                  newSite(x = 10) {
                    exclusion = rectangle(20)
                    gridOrigin = point(1)
                  })

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
          initial = newSite(width = 500) { zone { numPermanentClusters = 7 } },
          desired =
              newSite(width = 750) {
                zone {
                  subzone(width = 500)
                  subzone()
                }
              },
          expected =
              newSite(width = 750) {
                zone {
                  extraPermanentClusters = 3
                  numPermanentClusters = 10
                  subzone(width = 500)
                  subzone()
                }
              })
    }

    @Test
    fun `deletes existing zones`() {
      runScenario(
          newSite(width = 750) {
            zone(width = 500)
            zone()
          },
          newSite(width = 500))
    }

    @Test
    fun `deletes existing subzones`() {
      runScenario(
          newSite(width = 750) {
            zone(width = 750) {
              subzone(width = 500)
              subzone()
            }
          },
          newSite(width = 500))
    }

    @Test
    fun `creates new permanent clusters in added area`() {
      runScenario(
          initial =
              newSite(width = 500) {
                zone {
                  numPermanentClusters = 7
                  subzone { repeat(7) { cluster() } }
                }
              },
          desired = newSite(width = 750),
          expected =
              newSite(width = 750) {
                zone {
                  extraPermanentClusters = 3
                  numPermanentClusters = 10
                  subzone()
                }
              },
          expectedPlotCounts =
              listOf(
                  rectangle(x = 0, width = 500, height = 500) to 7 * 4,
                  rectangle(x = 500, width = 250, height = 500) to 3 * 4,
              ))
    }

    @Test
    fun `uses random cluster numbers for newly added clusters, renumbering existing clusters if needed`() {
      val existingClusterNumbers = mutableSetOf<Int>()
      val newClusterNumbers = mutableSetOf<Int>()
      val maxAttempts = 100
      var currentAttempt = 0
      val initialArea = rectangle(x = 0, width = 500, height = 500)
      val addedArea = rectangle(x = 500, width = 500, height = 500)

      while (++currentAttempt < maxAttempts &&
          (newClusterNumbers != setOf(1, 2, 3, 4) || existingClusterNumbers != setOf(1, 2, 3, 4))) {
        val (_, edited) =
            runScenario(
                initial =
                    newSite(width = 500) {
                      name = "Site $currentAttempt"
                      zone {
                        numPermanentClusters = 2
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
                      zone {
                        extraPermanentClusters = 2
                        numPermanentClusters = 4
                        subzone()
                      }
                    },
                expectedPlotCounts =
                    listOf(
                        initialArea to 8,
                        addedArea to 8,
                    ))

        val monitoringPlots = edited.plantingZones[0].plantingSubzones[0].monitoringPlots

        monitoringPlots.forEach { plot ->
          plot.permanentCluster?.let { permanentCluster ->
            if (plot.boundary.nearlyCoveredBy(addedArea)) {
              newClusterNumbers.add(permanentCluster)
            } else {
              existingClusterNumbers.add(permanentCluster)
            }
          }
        }

        val plotCountsByClusterNumber =
            monitoringPlots.groupBy { it.permanentCluster }.mapValues { (_, plots) -> plots.size }

        assertEquals(
            mapOf(1 to 4, 2 to 4, 3 to 4, 4 to 4),
            plotCountsByClusterNumber,
            "Number of plots with each cluster number after edit")
      }

      assertEquals(
          setOf(1, 2, 3, 4),
          newClusterNumbers,
          "Cluster numbers assigned to newly-added clusters across all runs")
      assertEquals(
          setOf(1, 2, 3, 4),
          existingClusterNumbers,
          "Cluster numbers assigned to existing clusters after edit across all runs")
    }

    @Disabled
    @Test
    fun `replaces temporary plots with new ones if observation is active`() {
      // This might end up being implemented by publishing an event for plot deletion that then
      // gets picked up by an observation-related handler elsewhere, or maybe this will happen
      // in a service class.
      TODO()
    }

    @Disabled
    @Test
    fun `removes all plots in cluster if one plot is no longer in plantable area`() {
      TODO()
    }

    @Test
    fun `updates plantable area totals when exclusion geometry changes`() {
      val (existing, edited) =
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

    @Disabled
    @Test
    fun `optionally marks expanded subzones as not complete`() {
      // This might be done in the calling code.
      TODO()
    }

    @Disabled
    @Test
    fun `does not mark non-expanded subzones as not complete`() {
      // This might be done in the calling code.
      TODO()
    }

    @Test
    fun `throws exception if edit has problems`() {
      assertThrows<PlantingSiteEditInvalidException> {
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
      val existing = store.createPlantingSite(newSite())

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
    ): ScenarioResults {
      clock.instant = Instant.EPOCH

      // createPlantingSite doesn't create monitoring plots since they are expected to be created
      // on demand later on, so we need to create them ourselves.
      val existingWithoutPlots = store.createPlantingSite(initial)

      initial.plantingZones.forEach { initialZone ->
        val existingZone = existingWithoutPlots.plantingZones.single { it.name == initialZone.name }

        initialZone.plantingSubzones.forEach { initialSubzone ->
          val existingSubzone =
              existingZone.plantingSubzones.single { it.name == initialSubzone.name }

          initialSubzone.monitoringPlots.forEach { initialPlot ->
            insertMonitoringPlot(
                boundary = initialPlot.boundary,
                fullName = initialPlot.fullName,
                isAvailable = initialPlot.isAvailable,
                name = initialPlot.name,
                permanentCluster = initialPlot.permanentCluster,
                permanentClusterSubplot = initialPlot.permanentClusterSubplot,
                plantingSubzoneId = existingSubzone.id,
            )
          }
        }
      }

      val existing = store.fetchSiteById(existingWithoutPlots.id, PlantingSiteDepth.Plot)
      val edited = editSite(existing, desired)

      if (!expected
          .withoutMonitoringPlots()
          .equals(edited.toNew().withoutMonitoringPlots(), 0.00001)) {
        assertEquals(expected, edited.toNew(), "Planting site after edit")
      }
      assertHistories(existing, edited)

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

      return ScenarioResults(existing, edited)
    }

    private fun editSite(
        existing: ExistingPlantingSiteModel,
        desired: AnyPlantingSiteModel,
        plantedSubzoneIds: Set<PlantingSubzoneId> = existing.firstSubzoneId(),
    ): ExistingPlantingSiteModel {
      clock.instant = editTime
      return store.applyPlantingSiteEdit(calculateSiteEdit(existing, desired, plantedSubzoneIds))
    }

    private fun calculateSiteEdit(
        existing: ExistingPlantingSiteModel,
        desired: AnyPlantingSiteModel,
        plantedSubzoneIds: Set<PlantingSubzoneId> = existing.firstSubzoneId(),
    ): PlantingSiteEdit =
        PlantingSiteEditCalculator(existing, desired, plantedSubzoneIds).calculateSiteEdit()

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

      assertEquals(
          edited.allSubzoneIds().size,
          editedSubzoneHistories.size,
          "Number of planting subzone histories from edit")
      assertEquals(
          edited.plantingZones
              .flatMap { zone ->
                zone.plantingSubzones.map { subzone ->
                  PlantingSubzoneHistoriesRow(
                      plantingZoneHistoryId =
                          editedZoneHistories.first { it.plantingZoneId == zone.id }.id,
                      plantingSubzoneId = subzone.id,
                      name = subzone.name,
                      fullName = subzone.fullName,
                      boundary = subzone.boundary,
                  )
                }
              }
              .toSet(),
          editedSubzoneHistories.map { it.copy(id = null) }.toSet(),
          "Planting subzone histories from edit")
    }
  }

  private fun ExistingPlantingSiteModel.allZoneIds(): Set<PlantingZoneId> =
      plantingZones.map { it.id }.toSet()

  private fun ExistingPlantingSiteModel.allSubzoneIds(): Set<PlantingSubzoneId> =
      plantingZones.flatMap { zone -> zone.plantingSubzones.map { it.id } }.toSet()

  private fun ExistingPlantingSiteModel.firstSubzoneId(): Set<PlantingSubzoneId> =
      plantingZones.flatMap { zone -> zone.plantingSubzones.map { it.id } }.take(1).toSet()

  private fun NewPlantingSiteModel.withoutMonitoringPlots() =
      copy(
          plantingZones =
              plantingZones.map { zone ->
                zone.copy(
                    plantingSubzones =
                        zone.plantingSubzones.map { subzone ->
                          subzone.copy(monitoringPlots = emptyList())
                        })
              })

  private data class ScenarioResults(
      val existing: ExistingPlantingSiteModel,
      val edited: ExistingPlantingSiteModel
  )
}
