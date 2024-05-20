package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import com.terraformation.backend.tracking.model.PlantingZoneModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlantingSiteEditCalculatorTest {
  @Test
  fun `returns create edits for newly added zone and subzone`() {
    val newZoneBoundary = rectangle(x = 500, width = 250, height = 500)

    val existing = existingSite(width = 500)
    val desired =
        newSite(width = 750) {
          zone(width = 500)
          zone(width = 250)
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("12.5"),
            boundary = rectangle(width = 750, height = 500),
            exclusion = null,
            plantingSiteId = existing.id,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Create(
                        addedRegion = newZoneBoundary,
                        areaHaDifference = BigDecimal("12.5"),
                        boundary = newZoneBoundary,
                        newName = "Z2",
                        numPermanentClustersToAdd =
                            PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS,
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    addedRegion = newZoneBoundary,
                                    boundary = newZoneBoundary,
                                    areaHaDifference = BigDecimal("12.5"),
                                    newName = "S2"))))),
        existing,
        desired)
  }

  @Test
  fun `returns zone update and subzone create for expansion of zone with new subzone`() {
    val newSubzoneBoundary = rectangle(x = 500, width = 250, height = 500)
    val newSiteBoundary = rectangle(width = 750, height = 500)

    val existing = existingSite(width = 500)
    val desired =
        newSite(width = 750) {
          zone {
            subzone(width = 500)
            subzone()
          }
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("12.5"),
            boundary = newSiteBoundary,
            exclusion = null,
            plantingSiteId = existing.id,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = newSubzoneBoundary,
                        boundary = newSiteBoundary,
                        areaHaDifference = BigDecimal("12.5"),
                        monitoringPlotsRemoved = emptySet(),
                        newName = "Z1",
                        numPermanentClustersToAdd =
                            PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS * (750 - 500) / 500,
                        oldName = "Z1",
                        plantingZoneId = PlantingZoneId(1),
                        removedRegion = rectangle(0),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    addedRegion = newSubzoneBoundary,
                                    boundary = newSubzoneBoundary,
                                    areaHaDifference = BigDecimal("12.5"),
                                    newName = "S2"))))),
        existing,
        desired)
  }

  @Test
  fun `returns updates with both added and removed regions if boundary change was not a simple expansion`() {
    val existing = existingSite(x = 0, width = 500, height = 500)
    val desired = newSite(x = 100, width = 600, height = 500)

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("5.0"),
            boundary = desired.boundary!!,
            exclusion = null,
            plantingSiteId = existing.id,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 500, width = 200, height = 500),
                        areaHaDifference = BigDecimal("5.0"),
                        boundary = desired.boundary!!,
                        newName = "Z1",
                        numPermanentClustersToAdd =
                            PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS * 200 / 500,
                        oldName = "Z1",
                        removedRegion = rectangle(x = 0, width = 100, height = 500),
                        monitoringPlotsRemoved = emptySet(),
                        plantingZoneId = PlantingZoneId(1),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 500, width = 200, height = 500),
                                    areaHaDifference = BigDecimal("5.0"),
                                    boundary = desired.boundary!!,
                                    newName = "S1",
                                    oldName = "S1",
                                    plantingSubzoneId = PlantingSubzoneId(1),
                                    removedRegion = rectangle(x = 0, width = 100, height = 500),
                                ))))),
        existing,
        desired)
  }

  @Test
  fun `treats removal of exclusion area as an expansion of the site`() {
    val existing =
        existingSite(x = 0, width = 1000, height = 500) {
          exclusion = rectangle(width = 300, height = 500)
        }
    val desired =
        newSite(width = 1000, height = 500) { exclusion = rectangle(width = 100, height = 500) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("10.0"),
            boundary = desired.boundary!!,
            exclusion = rectangle(width = 100, height = 500),
            plantingSiteId = existing.id,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 100, width = 200, height = 500),
                        areaHaDifference = BigDecimal("10.0"),
                        boundary = desired.boundary!!,
                        newName = "Z1",
                        numPermanentClustersToAdd =
                            PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS * 200 / 500,
                        oldName = "Z1",
                        removedRegion = rectangle(0),
                        monitoringPlotsRemoved = emptySet(),
                        plantingZoneId = PlantingZoneId(1),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 100, width = 200, height = 500),
                                    areaHaDifference = BigDecimal("10.0"),
                                    boundary = desired.boundary!!,
                                    newName = "S1",
                                    oldName = "S1",
                                    plantingSubzoneId = PlantingSubzoneId(1),
                                    removedRegion = rectangle(0),
                                ))))),
        existing,
        desired)
  }

  @Test
  fun `returns deletion of zone that no longer exists`() {
    val existing =
        existingSite(width = 1000) {
          zone(width = 750)
          zone(width = 250)
        }
    val desired = newSite(width = 750)

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("-12.5"),
            boundary = desired.boundary!!,
            exclusion = null,
            plantingSiteId = existing.id,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Delete(
                        areaHaDifference = BigDecimal("-12.5"),
                        oldName = "Z2",
                        removedRegion = rectangle(x = 750, width = 250, height = 500),
                        monitoringPlotsRemoved = emptySet(),
                        plantingZoneId = PlantingZoneId(2),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    areaHaDifference = BigDecimal("-12.5"),
                                    oldName = "S2",
                                    plantingSubzoneId = PlantingSubzoneId(2),
                                    removedRegion = rectangle(x = 750, width = 250, height = 500),
                                ))))),
        existing,
        desired)
  }

  @Test
  fun `returns deletion of subzone that no longer exists`() {
    val existing =
        existingSite(width = 1000) {
          zone(width = 500)
          zone(width = 500) {
            subzone(width = 250)
            subzone(width = 250)
          }
        }
    val desired =
        newSite(width = 750) {
          zone(width = 500)
          zone(width = 250)
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("-12.5"),
            boundary = desired.boundary!!,
            exclusion = null,
            plantingSiteId = existing.id,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal("-12.5"),
                        boundary = desired.plantingZones[1].boundary,
                        newName = "Z2",
                        numPermanentClustersToAdd = 0,
                        oldName = "Z2",
                        removedRegion = rectangle(x = 750, width = 250, height = 500),
                        monitoringPlotsRemoved = emptySet(),
                        plantingZoneId = PlantingZoneId(2),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    areaHaDifference = BigDecimal("-12.5"),
                                    oldName = "S3",
                                    plantingSubzoneId = PlantingSubzoneId(3),
                                    removedRegion = rectangle(x = 750, width = 250, height = 500),
                                ))))),
        existing,
        desired)
  }

  @Test
  fun `adds permanent clusters in proportion to size of added area`() {
    val existing = existingSite(width = 10000)
    val doubleSize = newSite(width = 20000)
    val halfAgainSize = newSite(width = 15000)
    val slightlyLargerSize = newSite(width = 10051)

    assertEquals(
        PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS,
        calculateSiteEdit(existing, doubleSize).plantingZoneEdits.first().numPermanentClustersToAdd,
        "Number of permanent clusters to add when doubling zone size")
    assertEquals(
        PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS / 2,
        calculateSiteEdit(existing, halfAgainSize)
            .plantingZoneEdits
            .first()
            .numPermanentClustersToAdd,
        "Number of permanent clusters to add when increasing zone size by half")
    assertEquals(
        1,
        calculateSiteEdit(existing, slightlyLargerSize)
            .plantingZoneEdits
            .first()
            .numPermanentClustersToAdd,
        "Number of permanent clusters to add when increasing zone size slightly")
  }

  @Test
  fun `does not add permanent cluster if there is not enough room for it in added area`() {
    val existing = existingSite(width = 500)
    val desired = newSite(width = 510)

    assertEquals(
        0,
        calculateSiteEdit(existing, desired).plantingZoneEdits.first().numPermanentClustersToAdd,
        "Number of permanent clusters to add")
  }

  @Test
  fun `does not add permanent cluster if total usable area of zone has gone down`() {
    val existing = existingSite(width = 10000, height = 500)
    val desired =
        newSite(width = 11000, height = 500) { exclusion = rectangle(width = 2000, height = 500) }

    assertEquals(
        0,
        calculateSiteEdit(existing, desired).plantingZoneEdits.first().numPermanentClustersToAdd,
        "Number of permanent clusters to add")
  }

  @Test
  fun `removes monitoring plots that overlap with added exclusion area`() {
    val existing = existingSite {
      zone {
        subzone {
          plot()
          plot()
        }
      }
    }
    val desired = newSite { exclusion = rectangle(1) }

    assertEquals(
        setOf(MonitoringPlotId(1)),
        calculateSiteEdit(existing, desired)
            .plantingZoneEdits
            .firstOrNull()
            ?.monitoringPlotsRemoved,
        "Monitoring plots removed")
  }

  @Test
  fun `removes monitoring plots that no longer lie in site`() {
    val existing = existingSite {
      zone {
        subzone {
          plot()
          plot()
        }
      }
    }
    val desired = newSite(x = 1)

    assertEquals(
        setOf(MonitoringPlotId(1)),
        calculateSiteEdit(existing, desired)
            .plantingZoneEdits
            .firstOrNull()
            ?.monitoringPlotsRemoved,
        "Monitoring plots removed")
  }

  @Test
  fun `returns empty list of edits if nothing changed`() {
    val site = existingSite()

    assertEditResult(
        PlantingSiteEdit(
            BigDecimal("0.0"), site.boundary!!, null, PlantingSiteId(1), emptyList(), emptyList()),
        site,
        site.toNew())
  }

  @Test
  fun `returns deletion and creation of all zones if site has no plants`() {
    val existing = existingSite {
      zone(width = 400)
      zone()
    }

    // This would be an invalid edit (change of boundaries + splitting) on a site with plants, but
    // it should be allowed on an unplanted site.
    val desired =
        newSite(width = 550) {
          zone(width = 500)
          zone(width = 250)
          zone()
        }

    assertEditResult(
        PlantingSiteEdit(
            BigDecimal("2.5"),
            desired.boundary!!,
            null,
            PlantingSiteId(1),
            listOf(
                PlantingZoneEdit.Delete(
                    areaHaDifference = existing.plantingZones[0].areaHa.negate(),
                    monitoringPlotsRemoved = emptySet(),
                    oldName = "Z1",
                    plantingZoneId = PlantingZoneId(1),
                    plantingSubzoneEdits =
                        listOf(
                            PlantingSubzoneEdit.Delete(
                                areaHaDifference = existing.plantingZones[0].areaHa.negate(),
                                oldName = "S1",
                                plantingSubzoneId = PlantingSubzoneId(1),
                                removedRegion = existing.plantingZones[0].boundary)),
                    removedRegion = existing.plantingZones[0].boundary,
                ),
                PlantingZoneEdit.Delete(
                    areaHaDifference = existing.plantingZones[1].areaHa.negate(),
                    monitoringPlotsRemoved = emptySet(),
                    oldName = "Z2",
                    plantingZoneId = PlantingZoneId(2),
                    plantingSubzoneEdits =
                        listOf(
                            PlantingSubzoneEdit.Delete(
                                areaHaDifference = existing.plantingZones[1].areaHa.negate(),
                                oldName = "S2",
                                plantingSubzoneId = PlantingSubzoneId(2),
                                removedRegion = existing.plantingZones[1].boundary)),
                    removedRegion = existing.plantingZones[1].boundary,
                ),
                PlantingZoneEdit.Create(
                    addedRegion = desired.plantingZones[0].boundary,
                    areaHaDifference = desired.plantingZones[0].areaHa,
                    boundary = desired.plantingZones[0].boundary,
                    newName = "Z1",
                    numPermanentClustersToAdd = 0,
                    plantingSubzoneEdits =
                        listOf(
                            PlantingSubzoneEdit.Create(
                                addedRegion = desired.plantingZones[0].boundary,
                                boundary = desired.plantingZones[0].boundary,
                                areaHaDifference = desired.plantingZones[0].areaHa,
                                newName = "S1"))),
                PlantingZoneEdit.Create(
                    addedRegion = desired.plantingZones[1].boundary,
                    areaHaDifference = desired.plantingZones[1].areaHa,
                    boundary = desired.plantingZones[1].boundary,
                    newName = "Z2",
                    numPermanentClustersToAdd = 0,
                    plantingSubzoneEdits =
                        listOf(
                            PlantingSubzoneEdit.Create(
                                addedRegion = desired.plantingZones[1].boundary,
                                boundary = desired.plantingZones[1].boundary,
                                areaHaDifference = desired.plantingZones[1].areaHa,
                                newName = "S2"))),
                PlantingZoneEdit.Create(
                    addedRegion = desired.plantingZones[2].boundary,
                    areaHaDifference = desired.plantingZones[2].areaHa,
                    boundary = desired.plantingZones[2].boundary,
                    newName = "Z3",
                    numPermanentClustersToAdd = 0,
                    plantingSubzoneEdits =
                        listOf(
                            PlantingSubzoneEdit.Create(
                                addedRegion = desired.plantingZones[2].boundary,
                                boundary = desired.plantingZones[2].boundary,
                                areaHaDifference = desired.plantingZones[2].areaHa,
                                newName = "S3"))),
            ),
        ),
        existing,
        desired,
        emptySet())
  }

  @Nested
  inner class Validation {
    @Test
    fun `detects change to boundary between existing zones`() {
      val existing =
          existingSite(width = 1000) {
            zone(width = 500)
            zone(width = 500)
          }
      val desired =
          newSite(width = 1000) {
            zone(width = 600)
            zone(width = 400)
          }

      assertHasProblem(
          PlantingSiteEditProblem.ZoneBoundaryChanged(
              conflictsWith = setOf("Z1", "Z2"), zoneName = "Z1"),
          existing,
          desired)
    }

    @Test
    fun `detects change to boundary between existing subzones`() {
      val existing =
          existingSite(width = 1000) {
            zone {
              subzone(width = 500)
              subzone(width = 500)
            }
          }
      val desired =
          newSite(width = 1000) {
            zone {
              subzone(width = 600)
              subzone(width = 400)
            }
          }

      assertHasProblem(
          PlantingSiteEditProblem.SubzoneBoundaryChanged(
              conflictsWith = setOf("S1", "S2"), subzoneName = "S1", zoneName = "Z1"),
          existing,
          desired)
    }

    @Test
    fun `detects attempt to split a zone`() {
      val existing = existingSite(width = 1000)
      val desired =
          newSite(width = 1000) {
            zone(width = 500, name = "N1")
            zone(width = 500, name = "N2")
          }

      assertHasProblem(
          PlantingSiteEditProblem.CannotSplitZone(
              conflictsWith = setOf("N1", "N2"), zoneName = "Z1"),
          existing,
          desired)
    }

    @Test
    fun `detects attempt to split a subzone`() {
      val existing = existingSite(width = 1000)
      val desired =
          newSite(width = 1000) {
            zone {
              subzone(width = 500, name = "N1")
              subzone(width = 500, name = "N2")
            }
          }

      assertHasProblem(
          PlantingSiteEditProblem.CannotSplitSubzone(
              conflictsWith = setOf("N1", "N2"), subzoneName = "S1", zoneName = "Z1"),
          existing,
          desired)
    }

    @Test
    fun `detects attempt to remove planted subzone`() {
      val existing = existingSite {
        zone {
          subzone(width = 100)
          subzone()
        }
      }
      val desired = newSite(x = 150)

      assertHasProblem(
          PlantingSiteEditProblem.CannotRemovePlantedSubzone(subzoneName = "S1", zoneName = "Z1"),
          existing,
          desired,
          setOf(existing.plantingZones[0].plantingSubzones[0].id))
    }

    @Test
    fun `detects attempt to remove zone with planted subzone`() {
      val existing = existingSite {
        zone(width = 100)
        zone()
      }
      val desired = newSite(x = 150)

      assertHasProblem(
          PlantingSiteEditProblem.CannotRemovePlantedSubzone(subzoneName = "S1", zoneName = "Z1"),
          existing,
          desired,
          setOf(existing.plantingZones[0].plantingSubzones[0].id))
    }

    @Test
    fun `throws exception if desired site has no boundary`() {
      assertThrows<IllegalArgumentException> {
        PlantingSiteEditCalculator(
                existingSite(),
                newSite().copy(boundary = null),
                emptySet(),
            )
            .calculateSiteEdit()
      }
    }
  }

  private fun calculateSiteEdit(
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
      plantedSubzoneIds: Set<PlantingSubzoneId> = setOf(PlantingSubzoneId(1)),
  ): PlantingSiteEdit =
      PlantingSiteEditCalculator(existing, desired, plantedSubzoneIds).calculateSiteEdit()

  private fun assertEditResult(
      expected: PlantingSiteEdit,
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
      plantedSubzoneIds: Set<PlantingSubzoneId> = setOf(PlantingSubzoneId(1)),
  ) {
    val actual = calculateSiteEdit(existing, desired, plantedSubzoneIds)

    if (!actual.equalsExact(expected)) {
      assertEquals(expected, actual)
    }
  }

  private fun assertHasProblem(problem: PlantingSiteEditProblem, edit: PlantingSiteEdit) {
    if (edit.problems.none { it == problem }) {
      assertEquals(listOf(problem), edit.problems, "Expected problem not found")
    }
  }

  private fun assertHasProblem(
      problem: PlantingSiteEditProblem,
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
      plantedSubzoneIds: Set<PlantingSubzoneId> = setOf(PlantingSubzoneId(1)),
  ) {
    assertHasProblem(problem, calculateSiteEdit(existing, desired, plantedSubzoneIds))
  }
}
