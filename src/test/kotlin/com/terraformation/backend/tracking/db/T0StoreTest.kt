package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.records.PlantingZoneT0TempDensitiesRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0DensitiesRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0ObservationsRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0ZoneDataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedModel
import com.terraformation.backend.tracking.model.SiteT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import com.terraformation.backend.tracking.model.ZoneT0TempDataModel
import com.terraformation.backend.tracking.model.ZoneT0TempDensityChangedModel
import com.terraformation.backend.util.toPlantsPerHectare
import java.math.BigDecimal
import kotlin.IllegalArgumentException
import kotlin.lazy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class T0StoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: T0Store by lazy { T0Store(clock, dslContext, eventPublisher) }

  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var plantingZoneId: PlantingZoneId
  private lateinit var plantingSubzoneId: PlantingSubzoneId
  private lateinit var monitoringPlotId: MonitoringPlotId
  private lateinit var tempPlotId: MonitoringPlotId
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
    plantingZoneId = insertPlantingZone(name = "Zone 2")
    plantingSubzoneId = insertPlantingSubzone()
    observationId = insertObservation(completedTime = clock.instant())

    tempPlotId = insertMonitoringPlot(plotNumber = 10)
    insertObservationPlot(
        claimedTime = clock.instant(),
        claimedBy = user.userId,
        completedTime = clock.instant(),
        completedBy = user.userId,
        isPermanent = false,
    )

    monitoringPlotId = insertMonitoringPlot(plotNumber = 2, permanentIndex = 2)
    insertObservationPlot(
        claimedTime = clock.instant(),
        claimedBy = user.userId,
        completedTime = clock.instant(),
        completedBy = user.userId,
        isPermanent = true,
    )
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
      insertPlantingZoneT0TempDensity(speciesId = speciesId1, zoneDensity = BigDecimal.valueOf(101))
      insertPlantingZoneT0TempDensity(speciesId = speciesId2, zoneDensity = BigDecimal.valueOf(102))
      val zone2 = insertPlantingZone(name = "Zone 1")
      insertPlantingZoneT0TempDensity(speciesId = speciesId1, zoneDensity = BigDecimal.valueOf(201))
      // data from other site not returned
      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot(plotNumber = 3)
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(20))
      insertPlantingZoneT0TempDensity(speciesId = speciesId1, zoneDensity = BigDecimal.valueOf(30))

      val expected =
          SiteT0DataModel(
              plantingSiteId = plantingSiteId,
              survivalRateIncludesTempPlots = false,
              plots =
                  listOf(
                      PlotT0DataModel(
                          monitoringPlotId = plot2,
                          densityData =
                              listOf(
                                  SpeciesDensityModel(
                                      speciesId = speciesId1,
                                      density = BigDecimal.valueOf(11),
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
                                      density = BigDecimal.valueOf(3),
                                  ),
                                  SpeciesDensityModel(
                                      speciesId = speciesId2,
                                      density = BigDecimal.valueOf(7),
                                  ),
                              ),
                      ),
                  ),
              zones =
                  listOf(
                      ZoneT0TempDataModel(
                          plantingZoneId = zone2,
                          densityData =
                              listOf(
                                  SpeciesDensityModel(
                                      speciesId = speciesId1,
                                      density = BigDecimal.valueOf(201),
                                  ),
                              ),
                      ),
                      ZoneT0TempDataModel(
                          plantingZoneId = plantingZoneId,
                          densityData =
                              listOf(
                                  SpeciesDensityModel(
                                      speciesId = speciesId1,
                                      density = BigDecimal.valueOf(101),
                                  ),
                                  SpeciesDensityModel(
                                      speciesId = speciesId2,
                                      density = BigDecimal.valueOf(102),
                                  ),
                              ),
                      ),
                  ),
          )

      assertEquals(expected, store.fetchT0SiteData(plantingSiteId))
    }
  }

  @Nested
  inner class FetchAllT0SiteDataSet {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()

      assertThrows<PlantingSiteNotFoundException> { store.fetchAllT0SiteDataSet(plantingSiteId) }
    }

    @Test
    fun `permanent plot has no t0 density set`() {
      insertPlantingSubzonePopulation(speciesId = speciesId1)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all permanent plots to have t0 data set",
      )
    }

    @Test
    fun `temp plot has no zone t0 density set`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlantingSubzonePopulation(speciesId = speciesId1)
      insertPlotT0Density(speciesId = speciesId1)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all temp plots to have t0 data set",
      )
    }

    @Test
    fun `withdrawal data has other species than t0`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlantingZoneT0TempDensity(speciesId = speciesId2)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertPlantingSubzonePopulation(speciesId = speciesId2)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all withdrawn species to have t0 data",
      )
    }

    @Test
    fun `observations have recorded species not in withdrawals or t0 (permanent)`() {
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertPlantingSubzonePopulation(speciesId = speciesId1)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 1)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires species only in observations to have t0 data",
      )
    }

    @Test
    fun `observations have recorded species not in withdrawals or t0 (temporary)`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlantingZoneT0TempDensity(speciesId = speciesId1)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertPlantingSubzonePopulation(speciesId = speciesId1)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 1)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires species only in observations to have t0 data",
      )
    }

    @Test
    fun `no withdrawal data and no t0 densities for permanent`() {
      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all permanent plots to have t0 data even if no withdrawn data",
      )
    }

    @Test
    fun `no withdrawal data and no t0 densities for temp`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all temporary plots to have t0 data even if no withdrawn data",
      )
    }

    @Test
    fun `no withdrawal data is still all set if plots have t0 data (excludes temp)`() {
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "No withdrawn data is ok if all plots/zones have t0 data",
      )
    }

    @Test
    fun `no withdrawal data is still all set if plots have t0 data (includes temp)`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertPlantingZoneT0TempDensity(speciesId = speciesId1)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 1)
      insertPlotT0Density(speciesId = speciesId2, monitoringPlotId = monitoringPlotId)

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "No withdrawn data is ok if all plots/zones have t0 data including temp",
      )
    }

    @Test
    fun `no permanent plots and temp missing t0 data`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      deletePermanentPlots()

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "No permanent plots doesn't affect temp t0 check missing data",
      )
    }

    @Test
    fun `no permanent plots and temp has t0 data`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      deletePermanentPlots()
      insertPlantingZoneT0TempDensity(speciesId = speciesId1)

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "No permanent plots doesn't affect temp t0 check with data",
      )
    }

    @Test
    fun `correctly checks all data`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      // ad-hoc plots are excluded
      insertMonitoringPlot(isAdHoc = true, plotNumber = 100, plantingSubzoneId = null)
      insertObservationPlot(
          claimedTime = clock.instant(),
          claimedBy = user.userId,
          completedTime = clock.instant(),
          completedBy = user.userId,
          isPermanent = false,
      )

      insertObservedPlotSpeciesTotals(
          monitoringPlotId = monitoringPlotId,
          speciesId = speciesId2,
          totalLive = 1,
      )

      // plots without completed or abandoned observations are excluded
      insertObservation(state = ObservationState.InProgress)
      insertMonitoringPlot(plotNumber = 101, permanentIndex = 101)
      insertObservationPlot(isPermanent = true)
      insertMonitoringPlot(plotNumber = 102, permanentIndex = null)
      insertObservationPlot()

      // species from observations that are not complete are excluded
      val speciesId3 = insertSpecies()
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = monitoringPlotId,
          speciesId = speciesId3,
          totalLive = 1,
      )

      insertPlantingZoneT0TempDensity(speciesId = speciesId1)
      insertPlantingZoneT0TempDensity(speciesId = speciesId2)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertPlotT0Density(speciesId = speciesId2, monitoringPlotId = monitoringPlotId)
      insertPlantingSubzonePopulation(speciesId = speciesId1)

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "All site data set",
      )
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
    fun `throws exception when plot is not permanent`() {
      val tempPlot = insertMonitoringPlot(plotNumber = 1)

      assertThrows<IllegalArgumentException> {
        store.assignT0PlotObservation(tempPlot, observationId)
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
      val changedModel = store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(
                          speciesId1,
                          newDensity = plotDensityToHectare(3),
                      ),
                      SpeciesDensityChangedModel(
                          speciesId2,
                          newDensity = plotDensityToHectare(7),
                      ),
                  ),
          ),
          changedModel,
          "Changed model should contain observation data",
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
              )
          ),
          "Should connect plot to observation",
      )
      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId, speciesId1, plotDensityToHectare(3)),
              plotDensityRecord(monitoringPlotId, speciesId2, plotDensityToHectare(7)),
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
    fun `stores observation densities and adds 0 for all withdrawn species not in observation`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 2)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 3, totalDead = 4)
      val speciesId3 = insertSpecies()
      val speciesId4 = insertSpecies()
      insertPlantingSubzonePopulation(
          plantingSubzoneId = plantingSubzoneId,
          speciesId = speciesId3,
          totalPlants = 1,
      )
      // should be excluded because no plants:
      insertPlantingSubzonePopulation(
          plantingSubzoneId = plantingSubzoneId,
          speciesId = speciesId4,
          totalPlants = 0,
      )

      val changedModel = store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(speciesId1, newDensity = plotDensityToHectare(3)),
                      SpeciesDensityChangedModel(speciesId2, newDensity = plotDensityToHectare(7)),
                      SpeciesDensityChangedModel(speciesId3, newDensity = BigDecimal.ZERO),
                  ),
          ),
          changedModel,
          "Changed model should contain withdrawn species as 0",
      )
      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId, speciesId1, plotDensityToHectare(3)),
              plotDensityRecord(monitoringPlotId, speciesId2, plotDensityToHectare(7)),
              plotDensityRecord(monitoringPlotId, speciesId3, BigDecimal.ZERO),
          ),
          "Should insert species densities including 0",
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot already exists`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 1)
      insertObservedPlotSpeciesTotals(totalLive = 1, totalDead = 1)
      val secondObservationId = insertObservation(plantingSiteId = plantingSiteId)
      insertObservationPlot()
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 1)
      insertObservedPlotSpeciesTotals(totalLive = 2, totalDead = 2)

      store.assignT0PlotObservation(monitoringPlotId, observationId)
      val changedModel = store.assignT0PlotObservation(monitoringPlotId, secondObservationId)

      // speciesId1 should not be in the changed model since density was the same
      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(
                          speciesId2,
                          previousDensity = plotDensityToHectare(2),
                          newDensity = plotDensityToHectare(4),
                      ),
                  ),
          ),
          changedModel,
          "Changed model should have previous value from observation",
      )

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
              plotDensityRecord(monitoringPlotId, speciesId1, plotDensityToHectare(2)),
              plotDensityRecord(monitoringPlotId, speciesId2, plotDensityToHectare(4)),
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
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 5, totalDead = 5)
      insertObservedPlotSpeciesTotals(totalLive = 1, totalDead = 1)
      insertPlotT0Density(speciesId = speciesId1, plotDensity = plotDensityToHectare(10))
      insertPlotT0Density(plotDensity = plotDensityToHectare(1))
      val changedModel = store.assignT0PlotObservation(monitoringPlotId, observationId)

      // speciesId1 should not be in the changed model since density was the same
      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(
                          speciesId2,
                          previousDensity = plotDensityToHectare(1),
                          newDensity = plotDensityToHectare(2),
                      ),
                  ),
          ),
          changedModel,
          "Changed model should have previous value from db",
      )

      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId, speciesId1, plotDensityToHectare(10)),
              plotDensityRecord(monitoringPlotId, speciesId2, plotDensityToHectare(2)),
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
      insertPlotT0Density(
          speciesId = speciesId1,
          plotDensity = plotDensityToHectare(10),
          modifiedTime = clock.instant().minusSeconds(62),
      )
      insertPlotT0Density(speciesId = speciesId2, plotDensity = plotDensityToHectare(20))
      val changedModel = store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(
                          speciesId1,
                          previousDensity = plotDensityToHectare(10),
                          newDensity = plotDensityToHectare(2),
                      ),
                      SpeciesDensityChangedModel(
                          speciesId2,
                          previousDensity = plotDensityToHectare(20),
                      ),
                  ),
          ),
          changedModel,
          "Changed model should have previous value",
      )

      assertTableEquals(
          listOf(plotDensityRecord(monitoringPlotId, speciesId1, plotDensityToHectare(2))),
          "Should have deleted density for species not in observation",
      )
    }

    @Test
    fun `stores expected scale of plot density when using an observation's data`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 60, totalDead = 20)

      store.assignT0PlotObservation(monitoringPlotId, observationId)

      val dbDensity =
          with(PLOT_T0_DENSITIES) {
            dslContext
                .select(PLOT_DENSITY)
                .from(this)
                .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(SPECIES_ID.eq(speciesId1))
                .fetchOne(PLOT_DENSITY.asNonNullable())!!
          }

      assertAll({
        assertEquals(
            BigDecimal("888.8888888889"),
            dbDensity,
            "Should convert density to plants/ha",
        )
        assertEquals(
            10,
            dbDensity.scale(),
            "Should store plot density with a scale of 10",
        )
      })
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
    fun `throws exception when plot is not permanent`() {
      val tempPlot = insertMonitoringPlot(plotNumber = 1)

      assertThrows<IllegalArgumentException> {
        store.assignT0PlotSpeciesDensities(
            tempPlot,
            listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
        )
      }
    }

    @Test
    fun `inserts new T0 plot record with species and density`() {
      val density = BigDecimal.valueOf(12)

      val changedModel =
          store.assignT0PlotSpeciesDensities(
              monitoringPlotId,
              listOf(SpeciesDensityModel(speciesId1, density)),
          )

      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(SpeciesDensityChangedModel(speciesId1, newDensity = density)),
          ),
          changedModel,
          "Changed model should be set with species data",
      )

      assertTableEquals(
          listOf(plotDensityRecord(monitoringPlotId, speciesId1, density)),
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

      clock.instant = clock.instant().minusSeconds(41)
      store.assignT0PlotSpeciesDensities(
          monitoringPlotId,
          listOf(
              SpeciesDensityModel(speciesId1, initialDensity),
              SpeciesDensityModel(speciesId2, initialDensity),
          ),
      )
      clock.instant = clock.instant().plusSeconds(60)
      val changedModel =
          store.assignT0PlotSpeciesDensities(
              monitoringPlotId,
              listOf(
                  SpeciesDensityModel(speciesId1, updatedDensity),
                  SpeciesDensityModel(speciesId2, initialDensity),
              ),
          )

      // speciesId2 should not be in the changed model since density was the same
      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(
                          speciesId1,
                          previousDensity = initialDensity,
                          newDensity = updatedDensity,
                      ),
                  ),
          ),
          changedModel,
          "Changed model should have previous value",
      )

      assertTableEquals(
          listOf(
              PlotT0DensitiesRecord(
                  monitoringPlotId,
                  speciesId1,
                  updatedDensity,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant().minusSeconds(60),
                  modifiedTime = clock.instant(),
              ),
              PlotT0DensitiesRecord(
                  monitoringPlotId,
                  speciesId2,
                  initialDensity,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant().minusSeconds(60),
                  modifiedTime = clock.instant(),
              ),
          ),
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
      val changedModel =
          store.assignT0PlotSpeciesDensities(
              monitoringPlotId,
              listOf(SpeciesDensityModel(speciesId2, density2)),
          )

      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(speciesId2, newDensity = density2),
                      SpeciesDensityChangedModel(speciesId1, previousDensity = density1),
                  ),
          ),
          changedModel,
          "Changed model should have previous value",
      )

      assertTableEquals(
          listOf(plotDensityRecord(monitoringPlotId, speciesId2, density2)),
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
    fun `deletes plot t0 observation if exists, replaces with new species data`() {
      insertPlotT0Observation()
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(2))
      insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(4))

      val density1 = BigDecimal.TEN
      val changedModel =
          store.assignT0PlotSpeciesDensities(
              monitoringPlotId,
              listOf(SpeciesDensityModel(speciesId1, density1)),
          )

      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(
                          speciesId1,
                          previousDensity = BigDecimal.valueOf(2),
                          newDensity = density1,
                      ),
                      SpeciesDensityChangedModel(
                          speciesId2,
                          previousDensity = BigDecimal.valueOf(4),
                          newDensity = null,
                      ),
                  ),
          ),
          changedModel,
          "Changed model should have previous value",
      )

      assertTableEmpty(PLOT_T0_OBSERVATIONS)

      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId, speciesId1, density1),
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
      assertTableEquals(listOf(plotDensityRecord(monitoringPlotId, speciesId1, BigDecimal.ZERO)))
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

  @Nested
  inner class AssignT0TempZoneSpeciesDensities {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.assignT0TempZoneSpeciesDensities(
            plantingZoneId,
            listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
        )
      }
    }

    @Test
    fun `inserts new T0 zone record with species and density`() {
      val density = BigDecimal.valueOf(12)

      val changedModel =
          store.assignT0TempZoneSpeciesDensities(
              plantingZoneId,
              listOf(SpeciesDensityModel(speciesId1, density)),
          )

      assertEquals(
          ZoneT0TempDensityChangedModel(
              plantingZoneId = plantingZoneId,
              speciesDensityChanges =
                  setOf(SpeciesDensityChangedModel(speciesId1, newDensity = density)),
          ),
          changedModel,
          "Changed model should be set with species data",
      )

      assertTableEquals(
          listOf(zoneDensityRecord(plantingZoneId, speciesId1, density)),
          "Should have inserted density",
      )

      eventPublisher.assertEventPublished(T0ZoneDataAssignedEvent(plantingZoneId = plantingZoneId))
    }

    @Test
    fun `updates existing T0 zone record when planting zone and species already exist`() {
      val initialDensity = BigDecimal.TEN
      val updatedDensity = BigDecimal.valueOf(15)

      clock.instant = clock.instant().minusSeconds(42)
      store.assignT0TempZoneSpeciesDensities(
          plantingZoneId,
          listOf(
              SpeciesDensityModel(speciesId1, initialDensity),
              SpeciesDensityModel(speciesId2, initialDensity),
          ),
      )
      clock.instant = clock.instant().plusSeconds(60)
      val changedModel =
          store.assignT0TempZoneSpeciesDensities(
              plantingZoneId,
              listOf(
                  SpeciesDensityModel(speciesId1, updatedDensity),
                  SpeciesDensityModel(speciesId2, initialDensity),
              ),
          )

      // speciesId2 should not be in the changed model since density was the same
      assertEquals(
          ZoneT0TempDensityChangedModel(
              plantingZoneId = plantingZoneId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(
                          speciesId1,
                          previousDensity = initialDensity,
                          newDensity = updatedDensity,
                      ),
                  ),
          ),
          changedModel,
          "Changed model should have previous value",
      )

      assertTableEquals(
          listOf(
              PlantingZoneT0TempDensitiesRecord(
                  plantingZoneId,
                  speciesId1,
                  updatedDensity,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant().minusSeconds(60),
                  modifiedTime = clock.instant(),
              ),
              PlantingZoneT0TempDensitiesRecord(
                  plantingZoneId,
                  speciesId2,
                  initialDensity,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant().minusSeconds(60),
                  modifiedTime = clock.instant(),
              ),
          ),
          "Should have updated density on conflict",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0ZoneDataAssignedEvent(plantingZoneId = plantingZoneId),
              T0ZoneDataAssignedEvent(plantingZoneId = plantingZoneId),
          )
      )
    }

    @Test
    fun `deletes existing densities in the zone`() {
      val density1 = BigDecimal.TEN
      val density2 = BigDecimal.valueOf(15)

      store.assignT0TempZoneSpeciesDensities(
          plantingZoneId,
          listOf(SpeciesDensityModel(speciesId1, density1)),
      )
      val changedModel =
          store.assignT0TempZoneSpeciesDensities(
              plantingZoneId,
              listOf(SpeciesDensityModel(speciesId2, density2)),
          )

      assertEquals(
          ZoneT0TempDensityChangedModel(
              plantingZoneId = plantingZoneId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(speciesId2, newDensity = density2),
                      SpeciesDensityChangedModel(speciesId1, previousDensity = density1),
                  ),
          ),
          changedModel,
          "Changed model should have previous value",
      )

      assertTableEquals(
          listOf(zoneDensityRecord(plantingZoneId, speciesId2, density2)),
          "Should have deleted existing density in zone",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0ZoneDataAssignedEvent(plantingZoneId = plantingZoneId),
              T0ZoneDataAssignedEvent(plantingZoneId = plantingZoneId),
          )
      )
    }

    @Test
    fun `allows for zero density values`() {
      store.assignT0TempZoneSpeciesDensities(
          plantingZoneId,
          listOf(SpeciesDensityModel(speciesId1, BigDecimal.ZERO)),
      )
      assertTableEquals(listOf(zoneDensityRecord(plantingZoneId, speciesId1, BigDecimal.ZERO)))

      eventPublisher.assertEventPublished(T0ZoneDataAssignedEvent(plantingZoneId = plantingZoneId))
    }

    @Test
    fun `throws exception for negative density values`() {
      assertThrows<IllegalArgumentException> {
        store.assignT0TempZoneSpeciesDensities(
            plantingZoneId,
            listOf(SpeciesDensityModel(speciesId1, BigDecimal.valueOf(-1))),
        )
      }
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

  private fun includeTempPlotsInSurvivalRates(
      plantingSiteId: PlantingSiteId,
      newSetting: Boolean = true,
  ) =
      dslContext
          .update(PLANTING_SITES)
          .set(PLANTING_SITES.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, newSetting)
          .where(PLANTING_SITES.ID.eq(plantingSiteId))
          .execute()

  private fun deletePermanentPlots() {
    dslContext
        .deleteFrom(OBSERVATION_PLOTS)
        .where(OBSERVATION_PLOTS.IS_PERMANENT.eq(true))
        .execute()
    dslContext
        .deleteFrom(MONITORING_PLOTS)
        .where(MONITORING_PLOTS.PERMANENT_INDEX.isNotNull)
        .execute()
  }

  private fun plotDensityToHectare(density: Int): BigDecimal =
      density.toBigDecimal().toPlantsPerHectare()
}
