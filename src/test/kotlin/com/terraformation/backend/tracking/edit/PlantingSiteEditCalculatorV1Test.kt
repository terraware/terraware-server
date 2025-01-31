package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import com.terraformation.backend.tracking.model.PlantingZoneModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlantingSiteEditCalculatorV1Test {
  @Test
  fun `returns create edits for newly added zone and subzone`() {
    val existing = existingSite(width = 500)
    val desired =
        newSite(width = 750) {
          zone(width = 500)
          zone(width = 250)
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("12.5"),
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Create(
                        desiredModel = desired.plantingZones[1],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    desiredModel =
                                        desired.plantingZones[1].plantingSubzones[0]))))),
        existing,
        desired)
  }

  @Test
  fun `returns zone update and subzone create for expansion of zone with new subzone`() {
    val newSubzoneBoundary = rectangle(x = 500, width = 250, height = 500)

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
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = newSubzoneBoundary,
                        areaHaDifference = BigDecimal("12.5"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            List(4) { MonitoringPlotEdit.Create(newSubzoneBoundary, null) },
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    desiredModel = desired.plantingZones[0].plantingSubzones[1])),
                        removedRegion = rectangle(0)))),
        existing,
        desired)
  }

  @Test
  fun `returns updates with both added and removed regions if boundary change was not a simple expansion`() {
    val existing = existingSite(x = 0, width = 500, height = 500)
    val desired = newSite(x = 100, width = 600, height = 500)
    val addedRegion = rectangle(x = 500, width = 200, height = 500)

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("5.0"),
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = addedRegion,
                        areaHaDifference = BigDecimal("5.0"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            List(3) { MonitoringPlotEdit.Create(addedRegion, null) },
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 500, width = 200, height = 500),
                                    areaHaDifference = BigDecimal("5.0"),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    removedRegion = rectangle(x = 0, width = 100, height = 500),
                                )),
                        removedRegion = rectangle(x = 0, width = 100, height = 500)))),
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
    val addedRegion = rectangle(x = 100, width = 200, height = 500)

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("10.0"),
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = addedRegion,
                        areaHaDifference = BigDecimal("10.0"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            List(2) { MonitoringPlotEdit.Create(addedRegion, null) },
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = addedRegion,
                                    areaHaDifference = BigDecimal("10.0"),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    removedRegion = rectangle(0),
                                )),
                        removedRegion = rectangle(0)))),
        existing,
        desired)
  }

  @Test
  fun `returns deletion of zone that no longer exists`() {
    val existing =
        existingSite(width = 1000) {
          zone(width = 750)
          zone(width = 250) { subzone { plot() } }
        }
    val desired = newSite(width = 750)

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("-12.5"),
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Delete(
                        existingModel = existing.plantingZones[1],
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    existingModel = existing.plantingZones[1].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(MonitoringPlotEdit.Eject(MonitoringPlotId(1)))))))),
        existing,
        desired)
  }

  @Test
  fun `returns deletion of subzone that no longer exists`() {
    val existing =
        existingSite(width = 1000) {
          zone(width = 500)
          zone(width = 500) {
            subzone(width = 250) { plot() }
            subzone(width = 250) { plot() }
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
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal("-12.5"),
                        desiredModel = desired.plantingZones[1],
                        existingModel = existing.plantingZones[1],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    existingModel = existing.plantingZones[1].plantingSubzones[1],
                                    monitoringPlotEdits =
                                        listOf(MonitoringPlotEdit.Eject(MonitoringPlotId(2))))),
                        removedRegion = rectangle(x = 750, width = 250, height = 500)))),
        existing,
        desired)
  }

  @Test
  fun `returns ejection of monitoring plot if subzone no longer covers it`() {
    val existing = existingSite {
      zone {
        subzone {
          plot(x = 0)
          plot(x = 400)
        }
      }
    }
    val desired = newSite(width = 250)

    val removedRegion = rectangle(x = 250, width = 250, height = 500)
    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("-12.5"),
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal("-12.5"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal("-12.5"),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(MonitoringPlotEdit.Eject(MonitoringPlotId(2))),
                                    removedRegion = removedRegion)),
                        removedRegion = removedRegion))),
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
        calculateSiteEdit(existing, doubleSize).plantingZoneEdits.first().monitoringPlotEdits.size,
        "Number of permanent clusters to add when doubling zone size")
    assertEquals(
        PlantingZoneModel.DEFAULT_NUM_PERMANENT_CLUSTERS / 2,
        calculateSiteEdit(existing, halfAgainSize)
            .plantingZoneEdits
            .first()
            .monitoringPlotEdits
            .size,
        "Number of permanent clusters to add when increasing zone size by half")
    assertEquals(
        1,
        calculateSiteEdit(existing, slightlyLargerSize)
            .plantingZoneEdits
            .first()
            .monitoringPlotEdits
            .size,
        "Number of permanent clusters to add when increasing zone size slightly")
  }

  @Test
  fun `does not add permanent cluster if there is not enough room for it in added area`() {
    val existing = existingSite(width = 500)
    val desired = newSite(width = 510)

    assertEquals(
        0,
        calculateSiteEdit(existing, desired).plantingZoneEdits.first().monitoringPlotEdits.size,
        "Number of permanent clusters to add")
  }

  @Test
  fun `does not add permanent cluster if total usable area of zone has gone down`() {
    val existing = existingSite(width = 10000, height = 500)
    val desired =
        newSite(width = 11000, height = 500) { exclusion = rectangle(width = 2000, height = 500) }

    assertEquals(
        0,
        calculateSiteEdit(existing, desired).plantingZoneEdits.first().monitoringPlotEdits.size,
        "Number of permanent clusters to add")
  }

  @Test
  fun `returns empty list of edits if nothing changed`() {
    val existing = existingSite()
    val desired = existing.toNew()

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("0.0"),
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits = emptyList()),
        existing,
        desired)
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
            areaHaDifference = BigDecimal("2.5"),
            behavior = PlantingSiteEditBehavior.Restricted,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Delete(
                        existingModel = existing.plantingZones[0],
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    existing.plantingZones[0].plantingSubzones[0])),
                    ),
                    PlantingZoneEdit.Delete(
                        existingModel = existing.plantingZones[1],
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    existing.plantingZones[1].plantingSubzones[0])),
                    ),
                    PlantingZoneEdit.Create(
                        desiredModel = desired.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    desired.plantingZones[0].plantingSubzones[0]))),
                    PlantingZoneEdit.Create(
                        desiredModel = desired.plantingZones[1],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    desired.plantingZones[1].plantingSubzones[0]))),
                    PlantingZoneEdit.Create(
                        desiredModel = desired.plantingZones[2],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    desired.plantingZones[2].plantingSubzones[0]))),
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
          PlantingSiteValidationFailure.zoneBoundaryChanged(
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
          PlantingSiteValidationFailure.subzoneBoundaryChanged(
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
          PlantingSiteValidationFailure.cannotSplitZone(
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
          PlantingSiteValidationFailure.cannotSplitSubzone(
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
          PlantingSiteValidationFailure.cannotRemovePlantedSubzone(
              subzoneName = "S1", zoneName = "Z1"),
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
          PlantingSiteValidationFailure.cannotRemovePlantedSubzone(
              subzoneName = "S1", zoneName = "Z1"),
          existing,
          desired,
          setOf(existing.plantingZones[0].plantingSubzones[0].id))
    }

    @Test
    fun `throws exception if desired site has no boundary`() {
      assertThrows<IllegalArgumentException> {
        PlantingSiteEditCalculatorV1(
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
      PlantingSiteEditCalculatorV1(existing, desired, plantedSubzoneIds).calculateSiteEdit()

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

  private fun assertHasProblem(problem: PlantingSiteValidationFailure, edit: PlantingSiteEdit) {
    if (edit.problems.none { it == problem }) {
      assertEquals(listOf(problem), edit.problems, "Expected problem not found")
    }
  }

  private fun assertHasProblem(
      problem: PlantingSiteValidationFailure,
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
      plantedSubzoneIds: Set<PlantingSubzoneId> = setOf(PlantingSubzoneId(1)),
  ) {
    assertHasProblem(problem, calculateSiteEdit(existing, desired, plantedSubzoneIds))
  }
}
