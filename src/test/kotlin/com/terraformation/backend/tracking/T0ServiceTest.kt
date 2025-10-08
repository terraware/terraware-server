package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.records.PlantingZoneT0TempDensitiesRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0DensitiesRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0ObservationsRecord
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.T0Store
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedEventModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import com.terraformation.backend.tracking.model.ZoneT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.ZoneT0TempDataModel
import com.terraformation.backend.util.toPlantsPerHectare
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class T0ServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val rateLimitedEventPublisher = TestEventPublisher()
  private val t0Store: T0Store by lazy { T0Store(clock, dslContext, eventPublisher) }
  private val service: T0Service by lazy {
    T0Service(dslContext, rateLimitedEventPublisher, t0Store)
  }

  private lateinit var monitoringPlotId1: MonitoringPlotId
  private lateinit var monitoringPlotId2: MonitoringPlotId
  private lateinit var plantingZoneId1: PlantingZoneId
  private lateinit var plantingZoneId2: PlantingZoneId
  private lateinit var observationId: ObservationId
  private lateinit var speciesId1: SpeciesId
  private lateinit var speciesId2: SpeciesId
  private lateinit var speciesId3: SpeciesId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    val gridOrigin = point(1)
    val siteBoundary = multiPolygon(200)
    insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
    insertPlantingSiteHistory()
    plantingZoneId1 = insertPlantingZone()
    insertPlantingSubzone()
    monitoringPlotId1 = insertMonitoringPlot(permanentIndex = 1)
    observationId = insertObservation()
    insertObservationPlot()
    monitoringPlotId2 = insertMonitoringPlot(permanentIndex = 2)
    plantingZoneId2 = insertPlantingZone()
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
    speciesId3 = insertSpecies()
  }

  @Nested
  inner class AssignT0PlotsData {
    @Test
    fun `throws exception if from multiple orgs`() {
      insertOrganization()
      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      val otherSitePlotId = insertMonitoringPlot(permanentIndex = 3)

      assertThrows<IllegalArgumentException> {
        service.assignT0PlotsData(
            listOf(
                PlotT0DataModel(monitoringPlotId1, observationId = observationId),
                PlotT0DataModel(
                    otherSitePlotId,
                    densityData = listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
                ),
            )
        )
      }
    }

    @Test
    fun `throws exception if from multiple sites`() {
      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      val otherSitePlotId = insertMonitoringPlot(permanentIndex = 4)

      assertThrows<IllegalArgumentException> {
        service.assignT0PlotsData(
            listOf(
                PlotT0DataModel(monitoringPlotId1, observationId = observationId),
                PlotT0DataModel(
                    otherSitePlotId,
                    densityData = listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
                ),
            )
        )
      }
    }

    @Test
    fun `assigns both observations and species densities in list`() {
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = monitoringPlotId1,
          speciesId = speciesId1,
          totalLive = 1,
          totalDead = 1,
      )
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = monitoringPlotId1,
          speciesId = speciesId2,
          totalLive = 3,
          totalDead = 4,
      )
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = monitoringPlotId1,
          speciesId = speciesId3,
          totalLive = 5,
          totalDead = 6,
      )

      service.assignT0PlotsData(
          listOf(
              PlotT0DataModel(monitoringPlotId1, observationId = observationId),
              PlotT0DataModel(
                  monitoringPlotId2,
                  densityData =
                      listOf(
                          SpeciesDensityModel(speciesId1, BigDecimal.valueOf(100)),
                          SpeciesDensityModel(speciesId2, BigDecimal.valueOf(200)),
                      ),
              ),
          )
      )

      assertTableEquals(
          listOf(
              PlotT0ObservationsRecord(
                  monitoringPlotId1,
                  observationId,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedTime = clock.instant(),
              )
          ),
          "Should have inserted one observation",
      )
      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId1, speciesId1, plotDensityToHectare(2)),
              plotDensityRecord(monitoringPlotId1, speciesId2, plotDensityToHectare(7)),
              plotDensityRecord(monitoringPlotId1, speciesId3, plotDensityToHectare(11)),
              plotDensityRecord(monitoringPlotId2, speciesId1, BigDecimal.valueOf(100)),
              plotDensityRecord(monitoringPlotId2, speciesId2, BigDecimal.valueOf(200)),
          ),
          "Should have inserted species densities",
      )

      rateLimitedEventPublisher.assertEventPublished(
          RateLimitedT0DataAssignedEvent(
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              monitoringPlots =
                  listOf(
                      PlotT0DensityChangedEventModel(
                          monitoringPlotId1,
                          1L,
                          listOf(
                              SpeciesDensityChangedEventModel(
                                  speciesId1,
                                  "Species 1",
                                  newDensity = plotDensityToHectare(2),
                              ),
                              SpeciesDensityChangedEventModel(
                                  speciesId2,
                                  "Species 2",
                                  newDensity = plotDensityToHectare(7),
                              ),
                              SpeciesDensityChangedEventModel(
                                  speciesId3,
                                  "Species 3",
                                  newDensity = plotDensityToHectare(11),
                              ),
                          ),
                      ),
                      PlotT0DensityChangedEventModel(
                          monitoringPlotId2,
                          2L,
                          listOf(
                              SpeciesDensityChangedEventModel(
                                  speciesId1,
                                  "Species 1",
                                  newDensity = BigDecimal.valueOf(100),
                              ),
                              SpeciesDensityChangedEventModel(
                                  speciesId2,
                                  "Species 2",
                                  newDensity = BigDecimal.valueOf(200),
                              ),
                          ),
                      ),
                  ),
          )
      )
    }
  }

  @Nested
  inner class AssignT0TempZoneData {
    @Test
    fun `throws exception if from multiple orgs`() {
      insertOrganization()
      insertPlantingSite()
      val otherPlantingZoneId = insertPlantingZone()

      assertThrows<IllegalArgumentException> {
        service.assignT0TempZoneData(
            listOf(
                ZoneT0TempDataModel(
                    plantingZoneId1,
                    densityData = listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
                ),
                ZoneT0TempDataModel(
                    otherPlantingZoneId,
                    densityData = listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
                ),
            )
        )
      }
    }

    @Test
    fun `throws exception if from multiple sites`() {
      insertPlantingSite()
      val otherPlantingZoneId = insertPlantingZone()

      assertThrows<IllegalArgumentException> {
        service.assignT0TempZoneData(
            listOf(
                ZoneT0TempDataModel(
                    plantingZoneId1,
                    densityData = listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
                ),
                ZoneT0TempDataModel(
                    otherPlantingZoneId,
                    densityData = listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
                ),
            )
        )
      }
    }

    @Test
    fun `assigns all zones in list`() {
      service.assignT0TempZoneData(
          listOf(
              ZoneT0TempDataModel(
                  plantingZoneId1,
                  listOf(
                      SpeciesDensityModel(speciesId1, BigDecimal.valueOf(100)),
                      SpeciesDensityModel(speciesId2, BigDecimal.valueOf(200)),
                  ),
              ),
              ZoneT0TempDataModel(
                  plantingZoneId2,
                  densityData =
                      listOf(
                          SpeciesDensityModel(speciesId1, BigDecimal.valueOf(300)),
                          SpeciesDensityModel(speciesId2, BigDecimal.valueOf(400)),
                      ),
              ),
          )
      )

      assertTableEquals(
          listOf(
              zoneDensityRecord(plantingZoneId1, speciesId1, BigDecimal.valueOf(100)),
              zoneDensityRecord(plantingZoneId1, speciesId2, BigDecimal.valueOf(200)),
              zoneDensityRecord(plantingZoneId2, speciesId1, BigDecimal.valueOf(300)),
              zoneDensityRecord(plantingZoneId2, speciesId2, BigDecimal.valueOf(400)),
          ),
          "Should have inserted species densities",
      )

      rateLimitedEventPublisher.assertEventPublished(
          RateLimitedT0DataAssignedEvent(
              organizationId = inserted.organizationId,
              plantingSiteId = inserted.plantingSiteId,
              plantingZones =
                  listOf(
                      ZoneT0DensityChangedEventModel(
                          plantingZoneId1,
                          "Z1",
                          listOf(
                              SpeciesDensityChangedEventModel(
                                  speciesId1,
                                  "Species 1",
                                  newDensity = BigDecimal.valueOf(100),
                              ),
                              SpeciesDensityChangedEventModel(
                                  speciesId2,
                                  "Species 2",
                                  newDensity = BigDecimal.valueOf(200),
                              ),
                          ),
                      ),
                      ZoneT0DensityChangedEventModel(
                          plantingZoneId2,
                          "Z2",
                          listOf(
                              SpeciesDensityChangedEventModel(
                                  speciesId1,
                                  "Species 1",
                                  newDensity = BigDecimal.valueOf(300),
                              ),
                              SpeciesDensityChangedEventModel(
                                  speciesId2,
                                  "Species 2",
                                  newDensity = BigDecimal.valueOf(400),
                              ),
                          ),
                      ),
                  ),
          )
      )
    }
  }

  private fun plotDensityRecord(
      plotId: MonitoringPlotId,
      speciesId: SpeciesId,
      density: BigDecimal,
  ) =
      PlotT0DensitiesRecord(
          monitoringPlotId = plotId,
          speciesId = speciesId,
          plotDensity = density,
          createdBy = user.userId,
          modifiedBy = user.userId,
          createdTime = clock.instant(),
          modifiedTime = clock.instant(),
      )

  private fun zoneDensityRecord(
      zoneId: PlantingZoneId,
      speciesId: SpeciesId,
      density: BigDecimal,
  ) =
      PlantingZoneT0TempDensitiesRecord(
          plantingZoneId = zoneId,
          speciesId = speciesId,
          zoneDensity = density,
          createdBy = user.userId,
          modifiedBy = user.userId,
          createdTime = clock.instant(),
          modifiedTime = clock.instant(),
      )

  private fun plotDensityToHectare(density: Int): BigDecimal =
      density.toBigDecimal().toPlantsPerHectare()
}
