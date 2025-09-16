package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.records.PlotT0DensityRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0ObservationsRecord
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import java.math.BigDecimal
import kotlin.lazy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class T0PlotStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: T0PlotStore by lazy { T0PlotStore(clock, dslContext, eventPublisher) }

  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var observationId: ObservationId
  private lateinit var speciesId1: SpeciesId
  private lateinit var speciesId2: SpeciesId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    val gridOrigin = point(1)
    val siteBoundary = multiPolygon(200)
    plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
    insertPlantingSiteHistory()
    insertPlantingZone()
    insertPlantingSubzone()
    monitoringPlotId = insertMonitoringPlot(plotNumber = 2)
    observationId = insertObservation()
    insertObservationPlot()
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
  }

  @Nested
  inner class FetchT0SiteData {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()

      assertThrows<PlantingSiteNotFoundException> { store.fetchT0SiteData(plantingSiteId) }
    }

    @Test
    fun `returns expected data`() {
      insertPlotT0Observation()
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(3))
      insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(7))
      val plot2 = insertMonitoringPlot(plotNumber = 1)
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(11))
      // data from monitoringPlot in other site not returned
      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot(plotNumber = 3)
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(20))

      val expected =
          listOf(
              PlotT0DataModel(
                  monitoringPlotId = plot2,
                  densityData =
                      listOf(
                          SpeciesDensityModel(
                              speciesId = speciesId1,
                              plotDensity = BigDecimal.valueOf(11),
                          )
                      ),
              ),
              PlotT0DataModel(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  densityData =
                      listOf(
                          SpeciesDensityModel(
                              speciesId = speciesId1,
                              plotDensity = BigDecimal.valueOf(3),
                          ),
                          SpeciesDensityModel(
                              speciesId = speciesId2,
                              plotDensity = BigDecimal.valueOf(7),
                          ),
                      ),
              ),
          )

      assertEquals(expected, store.fetchT0SiteData(plantingSiteId))
    }
  }

  @Nested
  inner class AssignT0PlotObservation {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.assignT0PlotObservation(monitoringPlotId, observationId)
      }
    }

    @Test
    fun `stores observation and all species densities`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 2)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 3, totalDead = 4)
      insertObservedPlotSpeciesTotals(
          certainty = RecordedSpeciesCertainty.Other,
          speciesName = "Something else",
          totalLive = 5,
          totalDead = 6,
      )
      insertObservedPlotSpeciesTotals(
          certainty = RecordedSpeciesCertainty.Unknown,
          totalLive = 7,
          totalDead = 8,
      )
      store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertTableEquals(
          listOf(
              PlotT0ObservationsRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedTime = clock.instant(),
              )
          ),
          "Should connect plot to observation",
      )
      assertTableEquals(
          listOf(
              densityRecord(monitoringPlotId, speciesId1, BigDecimal.valueOf(3)),
              densityRecord(monitoringPlotId, speciesId2, BigDecimal.valueOf(7)),
          ),
          "Should insert species densities",
      )

      eventPublisher.assertEventPublished(
          T0PlotDataAssignedEvent(
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
          )
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot already exists`() {
      insertObservedPlotSpeciesTotals(totalLive = 1, totalDead = 1)
      val secondObservationId = insertObservation(plantingSiteId = plantingSiteId)
      insertObservationPlot()
      insertObservedPlotSpeciesTotals(totalLive = 2, totalDead = 2)

      store.assignT0PlotObservation(monitoringPlotId, observationId)
      store.assignT0PlotObservation(monitoringPlotId, secondObservationId)

      assertTableEquals(
          listOf(
              PlotT0ObservationsRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = secondObservationId,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedTime = clock.instant(),
              )
          ),
          "Should have updated existing observation",
      )
      assertTableEquals(
          listOf(
              densityRecord(monitoringPlotId, speciesId2, BigDecimal.valueOf(4)),
          ),
          "Should use final species density",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0PlotDataAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
              ),
              T0PlotDataAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  observationId = secondObservationId,
              ),
          )
      )
    }

    @Test
    fun `observation overrides previous density`() {
      insertObservedPlotSpeciesTotals(totalLive = 1, totalDead = 1)
      insertPlotT0Density(plotDensity = BigDecimal.ONE)
      store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertTableEquals(
          listOf(
              densityRecord(monitoringPlotId, speciesId2, BigDecimal.TWO),
          ),
          "Should use observation for density",
      )

      assertTableEquals(
          listOf(
              PlotT0ObservationsRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedTime = clock.instant(),
              ),
          ),
          "Should have connected plot to observation",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0PlotDataAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
              ),
          )
      )
    }

    @Test
    fun `removes species densities not in observation`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 1)
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.TEN)
      insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(20))
      store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertTableEquals(
          listOf(densityRecord(monitoringPlotId, speciesId1, BigDecimal.TWO)),
          "Should have deleted density for species not in observation",
      )
    }
  }

  @Nested
  inner class AssignT0PlotSpeciesDensities {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.assignT0PlotSpeciesDensities(
            monitoringPlotId,
            listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
        )
      }
    }

    @Test
    fun `inserts new T0 plot record with species and density`() {
      val density = BigDecimal.valueOf(12)

      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(SpeciesDensityModel(speciesId1, density)),
      )

      assertTableEquals(
          listOf(densityRecord(monitoringPlotId, speciesId1, density)),
          "Should have inserted density",
      )

      eventPublisher.assertEventPublished(
          T0PlotDataAssignedEvent(monitoringPlotId = monitoringPlotId)
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot and species already exist`() {
      val initialDensity = BigDecimal.TEN
      val updatedDensity = BigDecimal.valueOf(15)

      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(SpeciesDensityModel(speciesId1, initialDensity)),
      )
      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(SpeciesDensityModel(speciesId1, updatedDensity)),
      )

      assertTableEquals(
          listOf(densityRecord(monitoringPlotId, speciesId1, updatedDensity)),
          "Should have updated density on conflict",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0PlotDataAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
              ),
              T0PlotDataAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
              ),
          )
      )
    }

    @Test
    fun `deletes existing densities in the plot`() {
      val density1 = BigDecimal.TEN
      val density2 = BigDecimal.valueOf(15)

      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(SpeciesDensityModel(speciesId1, density1)),
      )
      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(SpeciesDensityModel(speciesId2, density2)),
      )

      assertTableEquals(
          listOf(densityRecord(monitoringPlotId, speciesId2, density2)),
          "Should have deleted existing density in plot",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0PlotDataAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
              ),
              T0PlotDataAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
              ),
          )
      )
    }

    @Test
    fun `allows multiple species for same monitoring plot`() {
      val density1 = BigDecimal.TEN
      val density2 = BigDecimal.valueOf(20)

      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(
              SpeciesDensityModel(speciesId1, density1),
              SpeciesDensityModel(speciesId2, density2),
          ),
      )

      assertTableEquals(
          listOf(
              densityRecord(monitoringPlotId, speciesId1, density1),
              densityRecord(monitoringPlotId, speciesId2, density2),
          ),
          "Should have inserted two densities for monitoring plot",
      )

      eventPublisher.assertEventsPublished(
          listOf(T0PlotDataAssignedEvent(monitoringPlotId = monitoringPlotId))
      )
    }

    @Test
    fun `deletes plot t0 observation if exists, replaces with new species data`() {
      insertPlotT0Observation()
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(2))
      insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(4))

      val density1 = BigDecimal.TEN
      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(SpeciesDensityModel(speciesId1, density1)),
      )

      assertTableEmpty(PLOT_T0_OBSERVATIONS)

      assertTableEquals(
          listOf(
              densityRecord(monitoringPlotId, speciesId1, density1),
          ),
          "Should have updated density",
      )

      eventPublisher.assertEventsPublished(
          listOf(T0PlotDataAssignedEvent(monitoringPlotId = monitoringPlotId))
      )
    }

    @Test
    fun `allows for zero density values`() {
      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(SpeciesDensityModel(speciesId1, BigDecimal.ZERO)),
      )
      assertTableEquals(listOf(densityRecord(monitoringPlotId, speciesId1, BigDecimal.ZERO)))
    }

    @Test
    fun `throws exception for negative density values`() {
      assertThrows<IllegalArgumentException> {
        store.assignT0PlotSpeciesDensities(
            monitoringPlotId,
            listOf(SpeciesDensityModel(speciesId1, BigDecimal.valueOf(-1))),
        )
      }
    }
  }

  private fun densityRecord(plotId: MonitoringPlotId, speciesId: SpeciesId, density: BigDecimal) =
      PlotT0DensityRecord(
          monitoringPlotId = plotId,
          speciesId = speciesId,
          plotDensity = density,
          createdBy = user.userId,
          modifiedBy = user.userId,
          createdTime = clock.instant(),
          modifiedTime = clock.instant(),
      )
}
