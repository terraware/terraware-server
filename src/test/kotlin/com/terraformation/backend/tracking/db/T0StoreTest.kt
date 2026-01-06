package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlotT0DensitiesRecord
import com.terraformation.backend.db.tracking.tables.records.PlotT0ObservationsRecord
import com.terraformation.backend.db.tracking.tables.records.StratumT0TempDensitiesRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.event.ObservationStateUpdatedEvent
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0StratumDataAssignedEvent
import com.terraformation.backend.tracking.model.OptionalSpeciesDensityModel
import com.terraformation.backend.tracking.model.PlotSpeciesModel
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedModel
import com.terraformation.backend.tracking.model.SiteT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import com.terraformation.backend.tracking.model.StratumT0TempDataModel
import com.terraformation.backend.tracking.model.StratumT0TempDensityChangedModel
import com.terraformation.backend.util.toPlantsPerHectare
import java.math.BigDecimal
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
  private lateinit var stratumId: StratumId
  private lateinit var substratumId: SubstratumId
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
    stratumId = insertStratum(name = "Stratum 2")
    substratumId = insertSubstratum()
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
      insertStratumT0TempDensity(speciesId = speciesId1, stratumDensity = BigDecimal.valueOf(101))
      insertStratumT0TempDensity(speciesId = speciesId2, stratumDensity = BigDecimal.valueOf(102))
      val stratum2 = insertStratum(name = "Stratum 1")
      insertStratumT0TempDensity(speciesId = speciesId1, stratumDensity = BigDecimal.valueOf(201))
      // data from other site not returned
      insertPlantingSite()
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot(plotNumber = 3)
      insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(20))
      insertStratumT0TempDensity(speciesId = speciesId1, stratumDensity = BigDecimal.valueOf(30))

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
              strata =
                  listOf(
                      StratumT0TempDataModel(
                          stratumId = stratum2,
                          densityData =
                              listOf(
                                  SpeciesDensityModel(
                                      speciesId = speciesId1,
                                      density = BigDecimal.valueOf(201),
                                  ),
                              ),
                      ),
                      StratumT0TempDataModel(
                          stratumId = stratumId,
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
      insertSubstratumPopulation(speciesId = speciesId1)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all permanent plots to have t0 data set",
      )
    }

    @Test
    fun `temp plot has no stratum t0 density set`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertSubstratumPopulation(speciesId = speciesId1)
      insertPlotT0Density(speciesId = speciesId1)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all temp plots to have t0 data set",
      )
    }

    @Test
    fun `withdrawal data has other species than t0`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertStratumT0TempDensity(speciesId = speciesId2)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertSubstratumPopulation(speciesId = speciesId2)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires all withdrawn species to have t0 data",
      )
    }

    @Test
    fun `observations have recorded species not in withdrawals or t0 (permanent)`() {
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertSubstratumPopulation(speciesId = speciesId1)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 1)

      assertFalse(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Requires species only in observations to have t0 data",
      )
    }

    @Test
    fun `observations have recorded species not in withdrawals or t0 (temporary)`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertStratumT0TempDensity(speciesId = speciesId1)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertSubstratumPopulation(speciesId = speciesId1)
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
          "No withdrawn data is ok if all plots/strata have t0 data",
      )
    }

    @Test
    fun `no withdrawal data is still all set if plots have t0 data (includes temp)`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertStratumT0TempDensity(speciesId = speciesId1)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 1)
      // Unknown species are excluded in the check:
      insertObservedPlotSpeciesTotals(certainty = RecordedSpeciesCertainty.Unknown, totalLive = 2)
      insertPlotT0Density(speciesId = speciesId2, monitoringPlotId = monitoringPlotId)

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "No withdrawn data is ok if all plots/strata have t0 data including temp",
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
      insertStratumT0TempDensity(speciesId = speciesId1)

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "No permanent plots doesn't affect temp t0 check with data",
      )
    }

    @Test
    fun `plots require substrata to be included in all set`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlotT0Density(speciesId = speciesId1)
      insertPlotT0Density(speciesId = speciesId2)
      insertStratumT0TempDensity(speciesId = speciesId1)
      insertMonitoringPlot(plotNumber = 100, permanentIndex = 100, substratumId = null)
      insertObservationPlot(
          claimedTime = clock.instant(),
          claimedBy = user.userId,
          completedTime = clock.instant(),
          completedBy = user.userId,
          isPermanent = true,
      )
      insertMonitoringPlot(plotNumber = 101, permanentIndex = null, substratumId = null)
      insertObservationPlot(
          claimedTime = clock.instant(),
          claimedBy = user.userId,
          completedTime = clock.instant(),
          completedBy = user.userId,
      )

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Plots without substrata are excluded",
      )
    }

    @Test
    fun `correctly checks all data`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      // ad-hoc plots are excluded
      insertMonitoringPlot(isAdHoc = true, plotNumber = 100, substratumId = null)
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

      insertStratumT0TempDensity(speciesId = speciesId1)
      insertStratumT0TempDensity(speciesId = speciesId2)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertPlotT0Density(speciesId = speciesId2, monitoringPlotId = monitoringPlotId)
      insertSubstratumPopulation(speciesId = speciesId1)

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "All site data set",
      )
    }

    @Test
    fun `geometry changes plot temporary to permanent`() {
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)

      // plots that are permanent now and have completed observations but were temporary during the
      // observations are excluded
      insertMonitoringPlot(plotNumber = 103, permanentIndex = 103)
      insertObservationPlot(
          claimedTime = clock.instant(),
          claimedBy = user.userId,
          completedTime = clock.instant(),
          completedBy = user.userId,
          isPermanent = false,
      )

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Ignore observed plots that weren't permanent at observation",
      )
    }

    @Test
    fun `geometry changes plot permanent to temporary`() {
      includeTempPlotsInSurvivalRates(plantingSiteId)
      insertPlotT0Density(speciesId = speciesId1, monitoringPlotId = monitoringPlotId)
      insertStratumT0TempDensity(speciesId = speciesId1)

      // plots that are temporary now and have completed observations but were permanent during the
      // observations are excluded
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot(plotNumber = 104, permanentIndex = null)
      insertObservationPlot(
          claimedTime = clock.instant(),
          claimedBy = user.userId,
          completedTime = clock.instant(),
          completedBy = user.userId,
          isPermanent = true,
      )

      assertTrue(
          store.fetchAllT0SiteDataSet(plantingSiteId),
          "Ignore observed plots that weren't temporary at observation",
      )
    }
  }

  @Nested
  inner class FetchSiteSpeciesByPlot {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()

      assertThrows<PlantingSiteNotFoundException> { store.fetchSiteSpeciesByPlot(plantingSiteId) }
    }

    @Test
    fun `fetches site species from observations with no withdrawn data`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1)
      // Unknown species are excluded:
      insertObservedPlotSpeciesTotals(certainty = RecordedSpeciesCertainty.Unknown, totalLive = 2)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 1)
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = tempPlotId,
          speciesId = speciesId1,
          totalLive = 1,
      )

      val expected =
          setOf(
              PlotSpeciesModel(
                  monitoringPlotId = monitoringPlotId,
                  species = createSpeciesDensityList(speciesId1 to null, speciesId2 to null),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = tempPlotId,
                  species = createSpeciesDensityList(speciesId1 to null),
              ),
          )

      assertSetEquals(expected, store.fetchSiteSpeciesByPlot(plantingSiteId).toSet())
    }

    @Test
    fun `fetches site species by plot with densities`() {
      val speciesId3 = insertSpecies()
      val speciesId4 = insertSpecies()
      insertStratum()
      val substratum1 = insertSubstratum(areaHa = BigDecimal.ONE)
      val plot1 = insertMonitoringPlot(plotNumber = 3)
      val plot2 = insertMonitoringPlot(plotNumber = 4)
      val substratum2 = insertSubstratum(areaHa = BigDecimal.TEN)
      val plot3 = insertMonitoringPlot(plotNumber = 5)
      val plot4 = insertMonitoringPlot(plotNumber = 6)
      insertStratum()
      val substratum3 = insertSubstratum(areaHa = BigDecimal.valueOf(5))
      val plot5 = insertMonitoringPlot(plotNumber = 7)
      val substratum4 = insertSubstratum(areaHa = BigDecimal.valueOf(2174.6))
      val plot6 = insertMonitoringPlot(plotNumber = 8)
      insertSubstratumPopulation(substratumId = substratum1, speciesId = speciesId1, 100)
      insertSubstratumPopulation(substratumId = substratum1, speciesId = speciesId2, 200)
      insertSubstratumPopulation(substratumId = substratum2, speciesId = speciesId1, 300)
      insertSubstratumPopulation(substratumId = substratum2, speciesId = speciesId2, 395)
      insertSubstratumPopulation(substratumId = substratum2, speciesId = speciesId3, 500)
      insertSubstratumPopulation(substratumId = substratum3, speciesId = speciesId3, 600)
      insertSubstratumPopulation(substratumId = substratum4, speciesId = speciesId1, 500)
      // should be excluded because <0.05 density
      insertSubstratumPopulation(substratumId = substratum4, speciesId = speciesId2, 108)

      // ignored because already in withdrawn
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = plot1,
          speciesId = speciesId1,
          totalLive = 1,
      )
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = plot1,
          speciesId = speciesId4,
          totalDead = 1,
      )
      insertObservedPlotSpeciesTotals(
          monitoringPlotId = plot2,
          speciesId = speciesId4,
          totalDead = 1,
      )

      val expected =
          setOf(
              PlotSpeciesModel(
                  monitoringPlotId = plot1,
                  species =
                      createSpeciesDensityList(
                          speciesId1 to BigDecimal.valueOf(100.0),
                          speciesId2 to BigDecimal.valueOf(200.0),
                          speciesId4 to null,
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot2,
                  species =
                      createSpeciesDensityList(
                          speciesId1 to BigDecimal.valueOf(100.0),
                          speciesId2 to BigDecimal.valueOf(200.0),
                          speciesId4 to null,
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot3,
                  species =
                      createSpeciesDensityList(
                          speciesId1 to BigDecimal.valueOf(30.0),
                          speciesId2 to BigDecimal.valueOf(39.5), // this confirms correct rounding
                          speciesId3 to BigDecimal.valueOf(50.0),
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot4,
                  species =
                      createSpeciesDensityList(
                          speciesId1 to BigDecimal.valueOf(30.0),
                          speciesId2 to BigDecimal.valueOf(39.5),
                          speciesId3 to BigDecimal.valueOf(50.0),
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot5,
                  species = createSpeciesDensityList(speciesId3 to BigDecimal.valueOf(120.0)),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot6,
                  species = createSpeciesDensityList(speciesId1 to BigDecimal.valueOf(0.2)),
              ),
          )

      assertSetEquals(expected, store.fetchSiteSpeciesByPlot(plantingSiteId).toSet())
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
    fun `stores observation densities and adds 0 for all withdrawn or recorded species not in observation`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 2)
      insertObservedPlotSpeciesTotals(speciesId = speciesId2, totalLive = 3, totalDead = 4)
      val speciesId3 = insertSpecies()
      val speciesId4 = insertSpecies()
      insertSubstratumPopulation(
          substratumId = substratumId,
          speciesId = speciesId3,
          totalPlants = 1,
      )
      // should be excluded because no plants:
      insertSubstratumPopulation(
          substratumId = substratumId,
          speciesId = speciesId4,
          totalPlants = 0,
      )
      val speciesId5 = insertSpecies()
      val speciesId6 = insertSpecies()
      insertObservation(completedTime = clock.instant())
      insertObservedPlotSpeciesTotals(speciesId = speciesId5, totalLive = 1)
      // should be excluded because no plants
      insertObservedPlotSpeciesTotals(speciesId = speciesId6, totalLive = 0)
      // should be excluded because incomplete observation
      insertObservation(state = ObservationState.InProgress)
      insertObservedPlotSpeciesTotals(speciesId = speciesId6, totalLive = 1)

      val changedModel = store.assignT0PlotObservation(monitoringPlotId, observationId)

      assertEquals(
          PlotT0DensityChangedModel(
              monitoringPlotId = monitoringPlotId,
              speciesDensityChanges =
                  setOf(
                      SpeciesDensityChangedModel(speciesId1, newDensity = plotDensityToHectare(3)),
                      SpeciesDensityChangedModel(speciesId2, newDensity = plotDensityToHectare(7)),
                      SpeciesDensityChangedModel(speciesId3, newDensity = BigDecimal.ZERO),
                      SpeciesDensityChangedModel(speciesId5, newDensity = BigDecimal.ZERO),
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
              plotDensityRecord(monitoringPlotId, speciesId5, BigDecimal.ZERO),
          ),
          "Should insert species densities including 0",
      )
    }

    @Test
    fun `updates existing T0 plot record when monitoring plot already exists`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 1)
      insertObservedPlotSpeciesTotals(totalLive = 1, totalDead = 1)
      val secondObservationId = insertObservation(plantingSiteId = plantingSiteId)
      insertObservationPlot(isPermanent = true)
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

    @Test
    fun `allows assigning plot that was permanent in its observation but no longer is`() {
      insertObservedPlotSpeciesTotals(speciesId = speciesId1, totalLive = 1, totalDead = 2)
      dslContext
          .update(MONITORING_PLOTS)
          .setNull(MONITORING_PLOTS.PERMANENT_INDEX)
          .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
          .execute()

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
  inner class AssignT0TempStratumSpeciesDensities {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.assignT0TempStratumSpeciesDensities(
            stratumId,
            listOf(SpeciesDensityModel(speciesId1, BigDecimal.TEN)),
        )
      }
    }

    @Test
    fun `inserts new T0 stratum record with species and density`() {
      val density = BigDecimal.valueOf(12)

      val changedModel =
          store.assignT0TempStratumSpeciesDensities(
              stratumId,
              listOf(SpeciesDensityModel(speciesId1, density)),
          )

      assertEquals(
          StratumT0TempDensityChangedModel(
              stratumId = stratumId,
              speciesDensityChanges =
                  setOf(SpeciesDensityChangedModel(speciesId1, newDensity = density)),
          ),
          changedModel,
          "Changed model should be set with species data",
      )

      assertTableEquals(
          listOf(stratumDensityRecord(stratumId, speciesId1, density)),
          "Should have inserted density",
      )

      eventPublisher.assertEventPublished(T0StratumDataAssignedEvent(stratumId = stratumId))
    }

    @Test
    fun `updates existing T0 stratum record when stratum and species already exist`() {
      val initialDensity = BigDecimal.TEN
      val updatedDensity = BigDecimal.valueOf(15)

      clock.instant = clock.instant().minusSeconds(42)
      store.assignT0TempStratumSpeciesDensities(
          stratumId,
          listOf(
              SpeciesDensityModel(speciesId1, initialDensity),
              SpeciesDensityModel(speciesId2, initialDensity),
          ),
      )
      clock.instant = clock.instant().plusSeconds(60)
      val changedModel =
          store.assignT0TempStratumSpeciesDensities(
              stratumId,
              listOf(
                  SpeciesDensityModel(speciesId1, updatedDensity),
                  SpeciesDensityModel(speciesId2, initialDensity),
              ),
          )

      // speciesId2 should not be in the changed model since density was the same
      assertEquals(
          StratumT0TempDensityChangedModel(
              stratumId = stratumId,
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
              StratumT0TempDensitiesRecord(
                  stratumId,
                  speciesId1,
                  updatedDensity,
                  createdBy = user.userId,
                  modifiedBy = user.userId,
                  createdTime = clock.instant().minusSeconds(60),
                  modifiedTime = clock.instant(),
              ),
              StratumT0TempDensitiesRecord(
                  stratumId,
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
              T0StratumDataAssignedEvent(stratumId = stratumId),
              T0StratumDataAssignedEvent(stratumId = stratumId),
          )
      )
    }

    @Test
    fun `deletes existing densities in the stratum`() {
      val density1 = BigDecimal.TEN
      val density2 = BigDecimal.valueOf(15)

      store.assignT0TempStratumSpeciesDensities(
          stratumId,
          listOf(SpeciesDensityModel(speciesId1, density1)),
      )
      val changedModel =
          store.assignT0TempStratumSpeciesDensities(
              stratumId,
              listOf(SpeciesDensityModel(speciesId2, density2)),
          )

      assertEquals(
          StratumT0TempDensityChangedModel(
              stratumId = stratumId,
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
          listOf(stratumDensityRecord(stratumId, speciesId2, density2)),
          "Should have deleted existing density in stratum",
      )

      eventPublisher.assertEventsPublished(
          listOf(
              T0StratumDataAssignedEvent(stratumId = stratumId),
              T0StratumDataAssignedEvent(stratumId = stratumId),
          )
      )
    }

    @Test
    fun `allows for zero density values`() {
      store.assignT0TempStratumSpeciesDensities(
          stratumId,
          listOf(SpeciesDensityModel(speciesId1, BigDecimal.ZERO)),
      )
      assertTableEquals(listOf(stratumDensityRecord(stratumId, speciesId1, BigDecimal.ZERO)))

      eventPublisher.assertEventPublished(T0StratumDataAssignedEvent(stratumId = stratumId))
    }

    @Test
    fun `throws exception for negative density values`() {
      assertThrows<IllegalArgumentException> {
        store.assignT0TempStratumSpeciesDensities(
            stratumId,
            listOf(SpeciesDensityModel(speciesId1, BigDecimal.valueOf(-1))),
        )
      }
    }
  }

  @Nested
  inner class AssignNewObservationSpeciesZero {
    @Test
    fun `t0 densities are not changed when no new species in observation`() {
      val speciesId1 = insertSpecies()

      val t0ObservationId = insertObservation()
      insertObservationPlot(isPermanent = true)
      insertObservedPlotSpeciesTotals(
          observationId = t0ObservationId,
          speciesId = speciesId1,
          totalLive = 1,
      )
      store.assignT0PlotObservation(monitoringPlotId, t0ObservationId)

      insertObservedPlotSpeciesTotals(
          observationId = observationId,
          speciesId = speciesId1,
          totalLive = 5,
      )

      store.on(ObservationStateUpdatedEvent(observationId, ObservationState.Completed))

      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId, speciesId1, BigDecimal.ONE.toPlantsPerHectare())
          )
      )
    }

    @Test
    fun `t0 densities are added with zeros when new species in observation`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()

      val t0ObservationId = insertObservation()
      insertObservationPlot(isPermanent = true)
      insertObservedPlotSpeciesTotals(
          observationId = t0ObservationId,
          speciesId = speciesId1,
          totalLive = 10,
      )
      store.assignT0PlotObservation(monitoringPlotId, t0ObservationId)

      insertObservedPlotSpeciesTotals(
          observationId = observationId,
          speciesId = speciesId2,
          totalLive = 5,
      )

      store.on(ObservationStateUpdatedEvent(observationId, ObservationState.Abandoned))

      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId, speciesId1, BigDecimal.TEN.toPlantsPerHectare()),
              plotDensityRecord(monitoringPlotId, speciesId2, BigDecimal.ZERO),
          )
      )
    }

    @Test
    fun `unknown and other species are not included in t0 densities`() {
      val t0ObservationId = insertObservation()
      insertObservationPlot(isPermanent = true)
      insertObservedPlotSpeciesTotals(
          certainty = RecordedSpeciesCertainty.Other,
          observationId = t0ObservationId,
          speciesName = "testing",
          totalLive = 10,
      )
      insertObservedPlotSpeciesTotals(
          certainty = RecordedSpeciesCertainty.Unknown,
          observationId = t0ObservationId,
          totalLive = 11,
      )
      store.assignT0PlotObservation(monitoringPlotId, t0ObservationId)

      insertObservedPlotSpeciesTotals(
          certainty = RecordedSpeciesCertainty.Other,
          observationId = observationId,
          speciesName = "testing",
          totalLive = 5,
      )
      insertObservedPlotSpeciesTotals(
          certainty = RecordedSpeciesCertainty.Unknown,
          observationId = observationId,
          totalLive = 6,
      )

      store.on(ObservationStateUpdatedEvent(observationId, ObservationState.Abandoned))

      assertTableEmpty(PLOT_T0_DENSITIES)
    }

    @Test
    fun `t0 densities are not added when new observation state is not Completed or Abandoned`() {
      val speciesId1 = insertSpecies()
      val speciesId2 = insertSpecies()

      val t0ObservationId = insertObservation()
      insertObservationPlot(isPermanent = true)
      insertObservedPlotSpeciesTotals(
          observationId = t0ObservationId,
          speciesId = speciesId1,
          totalLive = 10,
      )
      store.assignT0PlotObservation(monitoringPlotId, t0ObservationId)

      insertObservedPlotSpeciesTotals(
          observationId = observationId,
          speciesId = speciesId2,
          totalLive = 5,
      )

      store.on(ObservationStateUpdatedEvent(observationId, ObservationState.InProgress))

      assertTableEquals(
          listOf(
              plotDensityRecord(monitoringPlotId, speciesId1, BigDecimal.TEN.toPlantsPerHectare())
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

  private fun stratumDensityRecord(
      stratumId: StratumId,
      speciesId: SpeciesId,
      density: BigDecimal,
  ) =
      StratumT0TempDensitiesRecord(
          stratumId = stratumId,
          speciesId = speciesId,
          stratumDensity = density,
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

  private fun createSpeciesDensityList(
      vararg densities: Pair<SpeciesId, BigDecimal?>
  ): List<OptionalSpeciesDensityModel> {
    return densities.map { OptionalSpeciesDensityModel(speciesId = it.first, density = it.second) }
  }
}
