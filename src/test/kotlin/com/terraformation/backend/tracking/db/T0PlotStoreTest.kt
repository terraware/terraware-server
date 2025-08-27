package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.records.PlotT0DensityRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0ObservationsRecord
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.tracking.event.T0ObservationAssignedEvent
import com.terraformation.backend.tracking.event.T0SpeciesDensityAssignedEvent
import java.math.BigDecimal
import kotlin.lazy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class T0PlotStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val eventPublisher = TestEventPublisher()
  private val store: T0PlotStore by lazy { T0PlotStore(dslContext, eventPublisher) }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var plantingSiteHistoryId: PlantingSiteHistoryId
  private lateinit var plantingZoneId: PlantingZoneId
  private lateinit var plantingSubzoneId: PlantingSubzoneId
  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var observationId: ObservationId
  private lateinit var speciesId1: SpeciesId
  private lateinit var speciesId2: SpeciesId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    val gridOrigin = point(1)
    val siteBoundary = multiPolygon(200)
    plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
    plantingSiteHistoryId = insertPlantingSiteHistory()
    plantingZoneId = insertPlantingZone()
    plantingSubzoneId = insertPlantingSubzone()
    monitoringPlotId = insertMonitoringPlot()
    observationId = insertObservation()
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
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
      store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertTableEquals(
          listOf(
              PlotT0ObservationsRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
              )
          ),
          "Should connect plot to observation",
      )
      assertTableEquals(
          listOf(
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = BigDecimal.valueOf(3),
              ),
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId2,
                  plotDensity = BigDecimal.valueOf(7),
              ),
          ),
          "Should insert species densities",
      )

      eventPublisher.assertEventPublished(
          T0ObservationAssignedEvent(
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
          )
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot already exists`() {
      insertObservedPlotSpeciesTotals(totalLive = 1, totalDead = 1)
      val secondObservationId = insertObservation(plantingSiteId = plantingSiteId)
      insertObservedPlotSpeciesTotals(totalLive = 2, totalDead = 2)

      store.assignT0PlotObservation(monitoringPlotId, observationId)
      store.assignT0PlotObservation(monitoringPlotId, secondObservationId)

      assertTableEquals(
          listOf(
              PlotT0ObservationsRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = secondObservationId,
              )
          ),
          "Should have updated existing observation",
      )
      assertTableEquals(
          listOf(
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId2,
                  plotDensity = BigDecimal.valueOf(4),
              ),
          ),
          "Should use final species density",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0ObservationAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
              ),
              T0ObservationAssignedEvent(
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
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId2,
                  plotDensity = BigDecimal.TWO,
              ),
          ),
          "Should use observation for density",
      )

      assertTableEquals(
          listOf(
              PlotT0ObservationsRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
              ),
          ),
          "Should have connected plot to observation",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0ObservationAssignedEvent(
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
          listOf(
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = BigDecimal.TWO,
              )
          ),
          "Should have deleted density for species not in observation",
      )
    }
  }

  @Nested
  inner class AssignT0PlotSpeciesDensity {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, BigDecimal.TEN)
      }
    }

    @Test
    fun `inserts new T0 plot record with species and density`() {
      val density = BigDecimal.valueOf(12)

      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, density)

      assertTableEquals(
          listOf(
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = density,
              )
          ),
          "Should have inserted density",
      )

      eventPublisher.assertEventPublished(
          T0SpeciesDensityAssignedEvent(
              monitoringPlotId = monitoringPlotId,
              speciesId = speciesId1,
              plotDensity = density,
          )
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot and species already exist`() {
      val initialDensity = BigDecimal.TEN
      val updatedDensity = BigDecimal.valueOf(15)

      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, initialDensity)
      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, updatedDensity)

      assertTableEquals(
          listOf(
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = updatedDensity,
              )
          ),
          "Should have updated density on conflict",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0SpeciesDensityAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = initialDensity,
              ),
              T0SpeciesDensityAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = updatedDensity,
              ),
          )
      )
    }

    @Test
    fun `allows multiple species for same monitoring plot`() {
      val density1 = BigDecimal.TEN
      val density2 = BigDecimal.valueOf(20)

      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, density1)
      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId2, density2)

      assertTableEquals(
          listOf(
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = density1,
              ),
              PlotT0DensityRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId2,
                  plotDensity = density2,
              ),
          ),
          "Should have inserted two densities for monitoring plot",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0SpeciesDensityAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  plotDensity = density1,
              ),
              T0SpeciesDensityAssignedEvent(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId2,
                  plotDensity = density2,
              ),
          )
      )
    }

    @Test
    fun `throws exception for zero density values`() {
      assertThrows<IllegalArgumentException> {
        store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, BigDecimal.ZERO)
      }
    }

    @Test
    fun `throws exception for negative density values`() {
      assertThrows<IllegalArgumentException> {
        store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, BigDecimal.valueOf(-1))
      }
    }

    @Test
    fun `does not allow species density to be set after observation is assigned to plot`() {
      insertPlotT0Observation()
      assertThrows<IllegalStateException> {
        store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, BigDecimal.valueOf(200))
      }
    }
  }
}
