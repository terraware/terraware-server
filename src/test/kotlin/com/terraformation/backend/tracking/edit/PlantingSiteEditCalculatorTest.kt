package com.terraformation.backend.tracking.edit

import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.newSite
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows

class PlantingSiteEditCalculatorTest {
  @Test
  fun `returns create edits for newly added zone and subzone`() {
    val existing = existingSite(width = 500) { zone(numPermanent = 1) { subzone { permanent() } } }
    val desired =
        newSite(width = 750) {
          zone(width = 500, numPermanent = 1)
          zone(width = 250, numPermanent = 1)
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("12.5"),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Create(
                        desiredModel = desired.plantingZones[1],
                        monitoringPlotEdits =
                            listOf(MonitoringPlotEdit.Create(desired.plantingZones[1].boundary, 1)),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    desiredModel = desired.plantingZones[1].plantingSubzones[0]
                                )
                            ),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `returns zone update and subzone create for expansion of zone with new subzone`() {
    val newSubzoneBoundary = rectangle(x = 500, width = 250, height = 500)

    val existing =
        existingSite(width = 500) {
          zone {
            subzone {
              permanent()
              permanent()
              plot(x = 600, isAdHoc = true)
            }
          }
        }
    val existingBoundary = existing.boundary!!
    val desired =
        newSite(width = 750) {
          zone(numPermanent = 6) {
            subzone(width = 500)
            subzone()
          }
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("12.5"),
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
                            listOf(
                                // Index 1 is already in the existing subzone
                                MonitoringPlotEdit.Create(newSubzoneBoundary, 2),
                                // Index 3 will be the old index 2 from the existing subzone
                                MonitoringPlotEdit.Create(existingBoundary, 4),
                                MonitoringPlotEdit.Create(newSubzoneBoundary, 5),
                                MonitoringPlotEdit.Create(existingBoundary, 6),
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Create(
                                    desiredModel = desired.plantingZones[0].plantingSubzones[1],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Adopt(MonitoringPlotId(3)),
                                        ),
                                ),
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Adopt(MonitoringPlotId(2), 3),
                                        ),
                                    removedRegion = rectangle(0),
                                ),
                            ),
                        removedRegion = rectangle(0),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `returns zone update for settings change`() {
    val newErrorMargin = BigDecimal(101)
    val newStudentsT = BigDecimal("1.646")
    val newTargetPlantingDensity = BigDecimal(1100)
    val newVariance = BigDecimal(40001)

    val existing = existingSite { zone { subzone { repeat(8) { permanent() } } } }
    val desired = newSite {
      zone {
        errorMargin = newErrorMargin
        studentsT = newStudentsT
        targetPlantingDensity = newTargetPlantingDensity
        variance = newVariance
      }
    }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal.ZERO,
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits = emptyList(),
                        removedRegion = rectangle(0),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `returns updates with both added and removed regions if boundary change was not a simple expansion`() {
    val existing = existingSite(x = 0, width = 500, height = 500)
    val desired = newSite(x = 100, width = 600, height = 500) { zone(numPermanent = 1) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("5.0"),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 500, width = 200, height = 500),
                        areaHaDifference = BigDecimal("5.0"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            listOf(
                                MonitoringPlotEdit.Create(
                                    rectangle(x = 100, width = 400, height = 500),
                                    1,
                                )
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 500, width = 200, height = 500),
                                    areaHaDifference = BigDecimal("5.0"),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    removedRegion = rectangle(x = 0, width = 100, height = 500),
                                )
                            ),
                        removedRegion = rectangle(x = 0, width = 100, height = 500),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `matches existing and new zones based on names, not locations`() {
    val existing =
        existingSite(width = 1000, height = 500) {
          zone(name = "A", width = 800) { subzone(name = "A-1") { permanent() } }
          zone(name = "B", width = 200) { subzone(name = "B-1") { permanent() } }
        }
    val desired =
        newSite(width = 1000, height = 500) {
          zone(name = "B", width = 500, numPermanent = 1) { subzone(name = "B-1") }
          zone(name = "A", width = 500, numPermanent = 1) { subzone(name = "A-1") }
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    // Zone B moves to west edge of site and grows from 200m to 500m wide
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 0, width = 500, height = 500),
                        areaHaDifference = BigDecimal(15),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[1],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 0, width = 500, height = 500),
                                    areaHaDifference = BigDecimal(15),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[1].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Adopt(MonitoringPlotId(1), 1),
                                        ),
                                    removedRegion = rectangle(x = 800, width = 200, height = 500),
                                )
                            ),
                        removedRegion = rectangle(x = 800, width = 200, height = 500),
                    ),
                    // Zone A moves to east edge of site and shrinks from 800m to 500m wide
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 800, width = 200, height = 500),
                        areaHaDifference = BigDecimal(-15),
                        desiredModel = desired.plantingZones[1],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            listOf(
                                // The permanent plot should be created in the part of the zone that
                                // overlaps with its old boundary.
                                MonitoringPlotEdit.Create(
                                    rectangle(x = 500, width = 300, height = 500),
                                    1,
                                ),
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 800, width = 200, height = 500),
                                    areaHaDifference = BigDecimal(-15),
                                    desiredModel = desired.plantingZones[1].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            // Plot 2 was in the area that overlapped with the old
                                            // area of the zone, which is smaller than the newly
                                            // added area, so it is removed from the permanent
                                            // plot list.
                                            MonitoringPlotEdit.Adopt(MonitoringPlotId(2), null),
                                        ),
                                    removedRegion = rectangle(x = 0, width = 500, height = 500),
                                )
                            ),
                        removedRegion = rectangle(x = 0, width = 500, height = 500),
                    ),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `moves existing subzones to new zones`() {
    val existing = existingSite {
      zone(name = "A", numPermanent = 2) {
        subzone(name = "Subzone 1", width = 250) { permanent() }
        subzone(name = "Subzone 2", width = 250) { permanent() }
      }
    }

    val desired = newSite {
      zone(name = "A", numPermanent = 1, width = 250) { subzone(name = "Subzone 1") }
      zone(name = "B", numPermanent = 2, width = 250) {
        subzone(name = "Subzone 2", stableId = StableId("A-Subzone 2"))
      }
    }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Create(
                        desiredModel = desired.plantingZones[1],
                        monitoringPlotEdits =
                            listOf(
                                MonitoringPlotEdit.Create(
                                    rectangle(x = 250, width = 250, height = 500),
                                    2,
                                )
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[1].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[1],
                                    monitoringPlotEdits =
                                        listOf(MonitoringPlotEdit.Adopt(MonitoringPlotId(2), 1)),
                                    removedRegion = rectangle(0),
                                )
                            ),
                    ),
                    // Zone A shrinks
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal("-12.5"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits = emptyList(),
                        removedRegion = rectangle(x = 250, width = 250, height = 500),
                    ),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `moves existing subzones between existing zones`() {
    val existing =
        existingSite(width = 750) {
          zone(name = "A", width = 500, numPermanent = 3) {
            subzone(name = "Subzone 1", width = 250) { permanent() }
            subzone(name = "Subzone 2", width = 250) {
              permanent()
              permanent()
            }
          }
          zone(name = "B", width = 250, numPermanent = 1) {
            subzone(name = "Subzone 3", width = 250) { permanent() }
          }
        }

    val desired =
        newSite(width = 750) {
          zone(name = "A", numPermanent = 1, width = 250) {
            subzone(name = "Subzone 1", width = 250)
          }
          zone(name = "B", numPermanent = 2, width = 500) {
            subzone(name = "Subzone 2", stableId = StableId("A-Subzone 2"), width = 250)
            subzone(name = "Subzone 3", width = 250)
          }
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    // Zone A shrinks
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal("-12.5"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits = emptyList(),
                        removedRegion = rectangle(x = 250, width = 250, height = 500),
                    ),
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 250, width = 250, height = 500),
                        areaHaDifference = BigDecimal("12.5"),
                        desiredModel = desired.plantingZones[1],
                        existingModel = existing.plantingZones[1],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[1].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[1],
                                    monitoringPlotEdits =
                                        listOf(MonitoringPlotEdit.Adopt(MonitoringPlotId(3), null)),
                                    removedRegion = rectangle(0),
                                ),
                            ),
                        removedRegion = rectangle(0),
                    ),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `moves monitoring plots to new subzones`() {
    val existing =
        existingSite(width = 1000, height = 500) {
          zone(name = "A") {
            subzone(name = "A-1") {
              plot(x = 600, plotNumber = 1, permanentIndex = 1)
              plot(x = 630, plotNumber = 2)
              plot(x = 660, plotNumber = 3, isAdHoc = true)
            }
          }
        }
    val desired =
        newSite(width = 1000, height = 500) {
          zone(name = "A", width = 500, numPermanent = 1) { subzone(name = "A-1") }
          zone(name = "B", width = 500, numPermanent = 1) { subzone(name = "B-1") }
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
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
                                    desiredModel = desired.plantingZones[1].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Adopt(
                                                monitoringPlotId = MonitoringPlotId(1),
                                                permanentIndex = 1,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                monitoringPlotId = MonitoringPlotId(2)
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                monitoringPlotId = MonitoringPlotId(3)
                                            ),
                                        ),
                                )
                            ),
                    ),
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal(-25),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            listOf(
                                MonitoringPlotEdit.Create(
                                    rectangle(x = 0, width = 500, height = 500),
                                    1,
                                )
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal(-25),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits = emptyList(),
                                    removedRegion = rectangle(x = 500, width = 500, height = 500),
                                )
                            ),
                        removedRegion = rectangle(x = 500, width = 500, height = 500),
                    ),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `adopts existing permanent plots proportionally when combining zones`() {
    val existing =
        existingSite(x = 0, width = 1000) {
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
        newSite(x = 280, width = 500) { zone(name = "A", numPermanent = 11, numTemporary = 3) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("-25.0"),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 500, width = 280, height = 500),
                        areaHaDifference = BigDecimal.ZERO,
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            listOf(
                                MonitoringPlotEdit.Create(
                                    region = rectangle(x = 280, width = 220, height = 500),
                                    permanentIndex = 11,
                                ),
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 500, width = 280, height = 500),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(16),
                                                permanentIndex = 1,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(1),
                                                permanentIndex = 2,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(18),
                                                permanentIndex = 3,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(2),
                                                permanentIndex = 4,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(19),
                                                permanentIndex = 5,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(4),
                                                permanentIndex = 6,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(23),
                                                permanentIndex = 7,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(25),
                                                permanentIndex = 8,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(7),
                                                permanentIndex = 9,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(26),
                                                permanentIndex = 10,
                                            ),
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(27),
                                                permanentIndex = null,
                                            ),
                                        ),
                                    removedRegion = rectangle(x = 0, width = 280, height = 500),
                                ),
                            ),
                        removedRegion = rectangle(x = 0, width = 280, height = 500),
                    ),
                    PlantingZoneEdit.Delete(
                        existingModel = existing.plantingZones[1],
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    existingModel = existing.plantingZones[1].plantingSubzones[0],
                                    monitoringPlotEdits = emptyList(),
                                )
                            ),
                    ),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `adopts existing exterior plots when site expands to cover them`() {
    val existing = existingSite(width = 100) { exteriorPlot(x = 500) }
    val desired = newSite(width = 600) { zone(numPermanent = 1) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal(25),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 100, width = 500, height = 500),
                        areaHaDifference = BigDecimal(25),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 100, width = 500, height = 500),
                                    areaHaDifference = BigDecimal(25),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Adopt(
                                                MonitoringPlotId(1),
                                                permanentIndex = 1,
                                            )
                                        ),
                                    removedRegion = rectangle(0),
                                )
                            ),
                        removedRegion = rectangle(0),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `does not adopt 25-meter, ad-hoc, or unavailable plots as new permanent plots`() {
    val existing =
        existingSite(width = 400) {
          zone {
            subzone {
              plot(size = 25)
              plot(isAdHoc = true)
              plot(isAvailable = false)
            }
          }
        }
    val desired = newSite(width = 500) { zone(numPermanent = 1) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal(5),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        areaHaDifference = BigDecimal(5),
                        addedRegion = rectangle(x = 400, width = 100, height = 500),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            listOf(
                                MonitoringPlotEdit.Create(existing.boundary!!, 1),
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 400, width = 100, height = 500),
                                    areaHaDifference = BigDecimal(5),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    // The unqualified plots are already in the correct subzone and
                                    // already have null permanent indexes, so no need to update any
                                    // of them.
                                    monitoringPlotEdits = emptyList(),
                                    removedRegion = rectangle(0),
                                ),
                            ),
                        removedRegion = rectangle(0),
                    ),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `treats removal of exclusion area as an expansion of the site`() {
    val existing =
        existingSite(x = 0, width = 1000, height = 500) {
          exclusion = rectangle(width = 300, height = 500)
        }
    val desired =
        newSite(width = 1000, height = 500) {
          exclusion = rectangle(width = 100, height = 500)
          zone(numPermanent = 10)
        }
    val addedRegion = rectangle(x = 100, width = 200, height = 500)
    val existingRegion = rectangle(x = 300, width = 700, height = 500)

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("10.0"),
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
                            listOf(
                                // Added region is 200 meters wide; existing region is 700 meters
                                // wide. So 22.2% of the permanent plots should go in the added
                                // region and 77.7% in the existing region.
                                MonitoringPlotEdit.Create(existingRegion, 1),
                                MonitoringPlotEdit.Create(existingRegion, 2),
                                MonitoringPlotEdit.Create(existingRegion, 3),
                                MonitoringPlotEdit.Create(addedRegion, 4),
                                MonitoringPlotEdit.Create(existingRegion, 5),
                                MonitoringPlotEdit.Create(existingRegion, 6),
                                MonitoringPlotEdit.Create(existingRegion, 7),
                                MonitoringPlotEdit.Create(addedRegion, 8),
                                MonitoringPlotEdit.Create(existingRegion, 9),
                                MonitoringPlotEdit.Create(existingRegion, 10),
                            ),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = addedRegion,
                                    areaHaDifference = BigDecimal("10.0"),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits = emptyList(),
                                    removedRegion = rectangle(0),
                                )
                            ),
                        removedRegion = rectangle(0),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `increases number of permanent plots even if geometry stayed the same`() {
    val existing = existingSite {
      zone(numPermanent = 2) {
        subzone {
          permanent()
          permanent()
        }
      }
    }
    val desired = newSite { zone(numPermanent = 3) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal.ZERO,
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            listOf(MonitoringPlotEdit.Create(existing.boundary!!, 3)),
                        plantingSubzoneEdits = emptyList(),
                        removedRegion = rectangle(0),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `reduces number of permanent plots even if geometry stayed the same`() {
    val existing = existingSite {
      zone(numPermanent = 3) {
        subzone {
          permanent()
          permanent()
          permanent()
        }
      }
    }
    val desired = newSite { zone(numPermanent = 2) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal.ZERO,
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(MonitoringPlotEdit.Adopt(MonitoringPlotId(3), null)),
                                    removedRegion = rectangle(0),
                                )
                            ),
                        removedRegion = rectangle(0),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `returns deletion of zone that no longer exists`() {
    val existing =
        existingSite(width = 1000) {
          zone(width = 750, numPermanent = 1) { subzone { permanent() } }
          zone(width = 250, numPermanent = 1) { subzone { permanent() } }
        }
    val desired = newSite(width = 750) { zone(numPermanent = 1) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("-12.5"),
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
                                        listOf(MonitoringPlotEdit.Eject(MonitoringPlotId(2))),
                                )
                            ),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `returns deletion of subzone that no longer exists`() {
    val existing =
        existingSite(width = 1000) {
          zone {
            subzone(width = 500)
            subzone(width = 500)
          }
        }
    val desired = newSite(width = 500) { zone(numPermanent = 1) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal(-25),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal(-25),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits =
                            listOf(MonitoringPlotEdit.Create(desired.boundary!!, 1)),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Delete(
                                    existingModel = existing.plantingZones[0].plantingSubzones[1],
                                )
                            ),
                        removedRegion = rectangle(x = 500, width = 500, height = 500),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `detects renames of zones and subzones`() {
    val existing =
        existingSite(width = 1000) {
          zone(name = "A", width = 500, numPermanent = 1) { subzone(name = "1") { permanent() } }
          zone(name = "B", width = 500, numPermanent = 1) { subzone(name = "2") { permanent() } }
        }

    // Swap the names; the subzones should stay in their original zones.
    val desired =
        newSite(width = 1000) {
          zone(name = "B", stableId = StableId("A"), width = 500, numPermanent = 1) {
            subzone(name = "2", stableId = StableId("A-1"))
          }
          zone(name = "A", stableId = StableId("B"), width = 500, numPermanent = 1) {
            subzone(name = "1", stableId = StableId("B-2"))
          }
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal.ZERO,
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        removedRegion = rectangle(0),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits = emptyList(),
                                    removedRegion = rectangle(0),
                                )
                            ),
                    ),
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal.ZERO,
                        desiredModel = desired.plantingZones[1],
                        existingModel = existing.plantingZones[1],
                        monitoringPlotEdits = emptyList(),
                        removedRegion = rectangle(0),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[1].plantingSubzones[0],
                                    existingModel = existing.plantingZones[1].plantingSubzones[0],
                                    monitoringPlotEdits = emptyList(),
                                    removedRegion = rectangle(0),
                                )
                            ),
                    ),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `updates existing subzone if original zone is deleted and new zone has a subzone with its stable ID`() {
    val existing =
        existingSite(width = 1000) {
          zone(name = "A", width = 500, numPermanent = 1) { subzone(name = "1") { permanent() } }
          zone(name = "B", width = 500, numPermanent = 1) { subzone(name = "2") { permanent() } }
        }
    val desired =
        newSite(width = 1000) {
          zone(name = "C", width = 500, numPermanent = 1) {
            subzone(name = "1", stableId = StableId("A-1"))
          }
          zone(name = "B", width = 500, numPermanent = 1) { subzone(name = "2") }
        }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Create(
                        desiredModel = desired.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits = emptyList(),
                                    removedRegion = rectangle(0),
                                )
                            ),
                    ),
                    PlantingZoneEdit.Delete(existing.plantingZones[0], emptyList()),
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `ejects monitoring plots that overlap with added exclusion area`() {
    val existing = existingSite {
      zone {
        subzone {
          plot(permanentIndex = 2)
          plot(permanentIndex = 1)
        }
      }
    }
    val desired = newSite {
      exclusion = rectangle(25)
      zone(numPermanent = 1)
    }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("-0.1"),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(0),
                        areaHaDifference = BigDecimal("-0.1"),
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(0),
                                    areaHaDifference = BigDecimal("-0.1"),
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Eject(MonitoringPlotId(1)),
                                            // Plot ID 2 is already in the correct subzone with the
                                            // correct permanent index.
                                        ),
                                    removedRegion = rectangle(25),
                                ),
                            ),
                        removedRegion = rectangle(25),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `ejects monitoring plots that no longer lie in site`() {
    val existing = existingSite {
      zone(numPermanent = 1) {
        subzone {
          plot(permanentIndex = 1)
          plot(permanentIndex = 2)
        }
      }
    }
    val desired = newSite(x = 10) { zone(numPermanent = 1) }

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal.ZERO,
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits =
                listOf(
                    PlantingZoneEdit.Update(
                        addedRegion = rectangle(x = 500, width = 10, height = 500),
                        areaHaDifference = BigDecimal.ZERO,
                        desiredModel = desired.plantingZones[0],
                        existingModel = existing.plantingZones[0],
                        monitoringPlotEdits = emptyList(),
                        plantingSubzoneEdits =
                            listOf(
                                PlantingSubzoneEdit.Update(
                                    addedRegion = rectangle(x = 500, width = 10, height = 500),
                                    areaHaDifference = BigDecimal.ZERO,
                                    desiredModel = desired.plantingZones[0].plantingSubzones[0],
                                    existingModel = existing.plantingZones[0].plantingSubzones[0],
                                    monitoringPlotEdits =
                                        listOf(
                                            MonitoringPlotEdit.Eject(MonitoringPlotId(1)),
                                            MonitoringPlotEdit.Adopt(MonitoringPlotId(2), 1),
                                        ),
                                    removedRegion = rectangle(width = 10, height = 500),
                                ),
                            ),
                        removedRegion = rectangle(width = 10, height = 500),
                    )
                ),
        ),
        existing,
        desired,
    )
  }

  @Test
  fun `returns empty list of edits if nothing changed`() {
    val existing = existingSite { zone(numPermanent = 1) { subzone { permanent() } } }
    val desired = existing.toNew()

    assertEditResult(
        PlantingSiteEdit(
            areaHaDifference = BigDecimal("0.0"),
            desiredModel = desired,
            existingModel = existing,
            plantingZoneEdits = emptyList(),
        ),
        existing,
        desired,
    )
  }

  @Nested
  inner class Validation {
    @Test
    fun `allows completely moving existing site`() {
      val existing = existingSite(x = 0, width = 1000)
      val desired = newSite(x = 5000, width = 100)

      assertDoesNotThrow { calculateSiteEdit(existing, desired) }
    }

    @Test
    fun `throws exception if desired site has no boundary`() {
      assertThrows<IllegalArgumentException> {
        PlantingSiteEditCalculator(
                existingSite(),
                newSite().copy(boundary = null),
            )
            .calculateSiteEdit()
      }
    }
  }

  private fun calculateSiteEdit(
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
  ): PlantingSiteEdit = PlantingSiteEditCalculator(existing, desired).calculateSiteEdit()

  private fun assertEditResult(
      expected: PlantingSiteEdit,
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
  ) {
    val actual = calculateSiteEdit(existing, desired)

    if (!actual.equalsExact(expected)) {
      // Assert equality on both the native and JSON forms of the edit so we get two assertion
      // failure messages. The native form is more precise and includes type information; the JSON
      // form (since it's pretty-printed) produces diffs that are much easier to read.
      assertAll(
          { assertJsonEquals(expected, actual) },
          { assertEquals(expected, actual) },
      )
    }
  }
}
