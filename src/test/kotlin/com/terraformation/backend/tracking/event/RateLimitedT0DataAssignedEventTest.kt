package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedEventModel
import com.terraformation.backend.tracking.model.ZoneT0DensityChangedEventModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RateLimitedT0DataAssignedEventTest {
  private val organizationId = OrganizationId(1)
  private val plantingSiteId = PlantingSiteId(10)

  @Nested
  inner class CombinePlots {
    @Test
    fun `combine for different monitoring plots`() {
      val existingEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(listOf(plotChangeModel(2, listOf(speciesChangeModel(1, 1, 2)))))

      assertEquals(
          event(
              listOf(
                  plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2))),
                  plotChangeModel(2, listOf(speciesChangeModel(1, 1, 2))),
              ),
          ),
          newEvent.combine(existingEvent),
          "Should add different plot",
      )
    }

    @Test
    fun `combine for same monitoring plot, diff species`() {
      val existingEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(2, 3, 4)))))

      assertEquals(
          event(
              listOf(
                  plotChangeModel(
                      1,
                      listOf(speciesChangeModel(1, 1, 2), speciesChangeModel(2, 3, 4)),
                  ),
              ),
          ),
          newEvent.combine(existingEvent),
          "Should add different species",
      )
    }

    @Test
    fun `combine for same monitoring plot, same species`() {
      val existingEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 3, 4)))))

      assertEquals(
          event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 4))))),
          newEvent.combine(existingEvent),
          "Should modify newDensity on species",
      )
    }

    @Test
    fun `combine with changes that are reverted`() {
      val existingEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 2, 1)))))

      assertEquals(
          event(listOf(plotChangeModel(1, emptyList()))),
          newEvent.combine(existingEvent),
          "Should leave densities as empty list because change was reverted",
      )
    }

    @Test
    fun `combine with species that were removed`() {
      val existingEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 2, null)))))

      assertEquals(
          event(listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, null))))),
          newEvent.combine(existingEvent),
          "Should show species as removed",
      )
    }

    @Test
    fun `combine with lots of data`() {
      val existingEvent =
          event(
              listOf(
                  plotChangeModel(1, listOf(speciesChangeModel(1, 10, 20))),
                  plotChangeModel(
                      2,
                      listOf(speciesChangeModel(1, 1, 2), speciesChangeModel(2, 3, 4)),
                  ),
              )
          )

      val newEvent =
          event(
              listOf(
                  plotChangeModel(
                      2,
                      listOf(speciesChangeModel(2, 4, 5), speciesChangeModel(3, 6, 7)),
                  ),
                  plotChangeModel(
                      3,
                      listOf(speciesChangeModel(1, 8, 9), speciesChangeModel(4, 11, 12)),
                  ),
              )
          )

      assertEquals(
          event(
              listOf(
                  plotChangeModel(1, listOf(speciesChangeModel(1, 10, 20))),
                  plotChangeModel(
                      2,
                      listOf(
                          speciesChangeModel(1, 1, 2),
                          speciesChangeModel(2, 3, 5),
                          speciesChangeModel(3, 6, 7),
                      ),
                  ),
                  plotChangeModel(
                      3,
                      listOf(speciesChangeModel(1, 8, 9), speciesChangeModel(4, 11, 12)),
                  ),
              ),
          ),
          newEvent.combine(existingEvent),
          "Combine works with lots of data",
      )
    }
  }

  @Nested
  inner class CombineZones {
    @Test
    fun `combine for different planting zones`() {
      val existingEvent =
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(zones = listOf(zoneChangeModel(2, listOf(speciesChangeModel(1, 1, 2)))))

      assertEquals(
          event(
              zones =
                  listOf(
                      zoneChangeModel(1, listOf(speciesChangeModel(1, 1, 2))),
                      zoneChangeModel(2, listOf(speciesChangeModel(1, 1, 2))),
                  )
          ),
          newEvent.combine(existingEvent),
          "Should add different zone",
      )
    }

    @Test
    fun `combine for same zone, diff species`() {
      val existingEvent =
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(2, 3, 4)))))

      assertEquals(
          event(
              zones =
                  listOf(
                      zoneChangeModel(
                          1,
                          listOf(speciesChangeModel(1, 1, 2), speciesChangeModel(2, 3, 4)),
                      ),
                  ),
          ),
          newEvent.combine(existingEvent),
          "Should add different species",
      )
    }

    @Test
    fun `combine for same zone, same species`() {
      val existingEvent =
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 3, 4)))))

      assertEquals(
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 1, 4))))),
          newEvent.combine(existingEvent),
          "Should modify newDensity on species",
      )
    }

    @Test
    fun `combine with changes that are reverted`() {
      val existingEvent =
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent = event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 2, 1)))))

      assertEquals(
          event(zones = listOf(zoneChangeModel(1, emptyList()))),
          newEvent.combine(existingEvent),
          "Should leave densities as empty list because change was reverted",
      )
    }

    @Test
    fun `combine with species that were removed`() {
      val existingEvent =
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

      val newEvent =
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 2, null)))))

      assertEquals(
          event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 1, null))))),
          newEvent.combine(existingEvent),
          "Should show species as removed",
      )
    }

    @Test
    fun `combine with lots of data`() {
      val existingEvent =
          event(
              zones =
                  listOf(
                      zoneChangeModel(1, listOf(speciesChangeModel(1, 10, 20))),
                      zoneChangeModel(
                          2,
                          listOf(speciesChangeModel(1, 1, 2), speciesChangeModel(2, 3, 4)),
                      ),
                  )
          )

      val newEvent =
          event(
              zones =
                  listOf(
                      zoneChangeModel(
                          2,
                          listOf(speciesChangeModel(2, 4, 5), speciesChangeModel(3, 6, 7)),
                      ),
                      zoneChangeModel(
                          3,
                          listOf(speciesChangeModel(1, 8, 9), speciesChangeModel(4, 11, 12)),
                      ),
                  )
          )

      assertEquals(
          event(
              zones =
                  listOf(
                      zoneChangeModel(1, listOf(speciesChangeModel(1, 10, 20))),
                      zoneChangeModel(
                          2,
                          listOf(
                              speciesChangeModel(1, 1, 2),
                              speciesChangeModel(2, 3, 5),
                              speciesChangeModel(3, 6, 7),
                          ),
                      ),
                      zoneChangeModel(
                          3,
                          listOf(speciesChangeModel(1, 8, 9), speciesChangeModel(4, 11, 12)),
                      ),
                  ),
          ),
          newEvent.combine(existingEvent),
          "Combine works with lots of data",
      )
    }
  }

  @Test
  fun `combines both plots and zones`() {
    val existingEvent =
        event(plots = listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))))

    val newEvent = event(zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 3, 4)))))

    assertEquals(
        event(
            plots = listOf(plotChangeModel(1, listOf(speciesChangeModel(1, 1, 2)))),
            zones = listOf(zoneChangeModel(1, listOf(speciesChangeModel(1, 3, 4)))),
        ),
        newEvent.combine(existingEvent),
        "Should include both plots and zones after combine",
    )
  }

  private fun event(
      plots: List<PlotT0DensityChangedEventModel> = emptyList(),
      zones: List<ZoneT0DensityChangedEventModel> = emptyList(),
  ): RateLimitedT0DataAssignedEvent =
      RateLimitedT0DataAssignedEvent(
          organizationId = organizationId,
          plantingSiteId = plantingSiteId,
          monitoringPlots = plots,
          plantingZones = zones,
      )

  private fun plotChangeModel(plotId: Int, densities: List<SpeciesDensityChangedEventModel>) =
      PlotT0DensityChangedEventModel(
          monitoringPlotId = MonitoringPlotId(plotId.toLong()),
          monitoringPlotNumber = plotId.toLong(),
          speciesDensityChanges = densities,
      )

  private fun zoneChangeModel(zoneId: Int, densities: List<SpeciesDensityChangedEventModel>) =
      ZoneT0DensityChangedEventModel(
          plantingZoneId = PlantingZoneId(zoneId.toLong()),
          zoneName = "Z$zoneId",
          speciesDensityChanges = densities,
      )

  private fun speciesChangeModel(
      speciesId: Int,
      prevDensity: Int?,
      newDensity: Int?,
  ): SpeciesDensityChangedEventModel =
      SpeciesDensityChangedEventModel(
          speciesId = SpeciesId(speciesId.toLong()),
          speciesScientificName = "Species $speciesId",
          previousPlotDensity = prevDensity?.let { BigDecimal(it) },
          newPlotDensity = newDensity?.let { BigDecimal(it) },
      )
}
