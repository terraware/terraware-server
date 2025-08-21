package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
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
import com.terraformation.backend.db.tracking.tables.records.T0PlotRecord
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import java.math.BigDecimal
import kotlin.lazy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class T0PlotStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  //  override val user: TerrawareUser = mockUser()

  private val store: T0PlotStore by lazy { T0PlotStore(dslContext) }

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
    fun `inserts new T0 plot record`() {
      store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertTableEquals(
          listOf(
              T0PlotRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
              )
          ),
          "Should have inserted new record",
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot already exists`() {
      val secondObservationId = insertObservation(plantingSiteId = plantingSiteId)

      store.assignT0PlotObservation(monitoringPlotId, observationId)
      store.assignT0PlotObservation(monitoringPlotId, secondObservationId)

      assertTableEquals(
          listOf(
              T0PlotRecord(
                  monitoringPlotId = monitoringPlotId,
                  observationId = secondObservationId,
              )
          ),
          "Should have updated existing record",
      )
    }
  }

  @Nested
  inner class AssignT0PlotSpeciesDensity {
    @BeforeEach
    fun setUp() {
      speciesId1 = insertSpecies()
      speciesId2 = insertSpecies()
    }

    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, BigDecimal("100.00"))
      }
    }

    @Test
    fun `inserts new T0 plot record with species and density`() {
      val density = BigDecimal("125.75")

      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, density)

      assertTableEquals(
          listOf(
              T0PlotRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  estimatedPlantingDensity = density,
              )
          ),
          "Should have inserted species and density",
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot and species already exist`() {
      val initialDensity = BigDecimal("100.00")
      val updatedDensity = BigDecimal("150.25")

      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, initialDensity)
      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, updatedDensity)

      assertTableEquals(
          listOf(
              T0PlotRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  estimatedPlantingDensity = updatedDensity,
              )
          ),
          "Should have updated density on conflict",
      )
    }

    @Test
    fun `allows multiple species for same monitoring plot`() {
      val density1 = BigDecimal("100.00")
      val density2 = BigDecimal("200.00")

      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, density1)
      store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId2, density2)

      assertTableEquals(
          listOf(
              T0PlotRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId1,
                  estimatedPlantingDensity = density1,
              ),
              T0PlotRecord(
                  monitoringPlotId = monitoringPlotId,
                  speciesId = speciesId2,
                  estimatedPlantingDensity = density2,
              ),
          ),
          "Should have inserted two rows",
      )
    }

    @Test
    fun `throws exception for zero density values`() {
      assertThrows<IllegalArgumentException> {
        store.assignT0PlotSpeciesDensity(monitoringPlotId, speciesId1, BigDecimal.ZERO)
      }
    }
  }
}
