package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.records.PlotT0DensityRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0ObservationsRecord
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.T0PlotStore
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class T0PlotServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val eventPublisher = TestEventPublisher()
  private val t0PlotStore: T0PlotStore by lazy { T0PlotStore(dslContext, eventPublisher) }
  private val service: T0PlotService by lazy { T0PlotService(dslContext, t0PlotStore) }

  private lateinit var monitoringPlotId1: MonitoringPlotId
  private lateinit var monitoringPlotId2: MonitoringPlotId
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
    insertPlantingZone()
    insertPlantingSubzone()
    monitoringPlotId1 = insertMonitoringPlot()
    observationId = insertObservation()
    insertObservationPlot()
    monitoringPlotId2 = insertMonitoringPlot()
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
    speciesId3 = insertSpecies()
  }

  @Nested
  inner class AssignT0PlotsData {
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
                          SpeciesDensityModel(speciesId1, BigDecimal.TEN),
                          SpeciesDensityModel(speciesId2, BigDecimal.valueOf(20)),
                      ),
              ),
          )
      )

      assertTableEquals(
          listOf(PlotT0ObservationsRecord(monitoringPlotId1, observationId)),
          "Should have inserted one observation",
      )
      assertTableEquals(
          listOf(
              PlotT0DensityRecord(monitoringPlotId1, speciesId1, BigDecimal.valueOf(2)),
              PlotT0DensityRecord(monitoringPlotId1, speciesId2, BigDecimal.valueOf(7)),
              PlotT0DensityRecord(monitoringPlotId1, speciesId3, BigDecimal.valueOf(11)),
              PlotT0DensityRecord(monitoringPlotId2, speciesId1, BigDecimal.valueOf(10)),
              PlotT0DensityRecord(monitoringPlotId2, speciesId2, BigDecimal.valueOf(20)),
          ),
          "Should have inserted species densities",
      )
    }
  }
}
