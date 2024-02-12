package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteImporter
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.fail

class PlotAssignmentTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val plantingSiteImporter: PlantingSiteImporter by lazy {
    PlantingSiteImporter(
        clock,
        dslContext,
        monitoringPlotsDao,
        plantingSitesDao,
        plantingZonesDao,
        plantingSubzonesDao,
    )
  }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        recordedPlantsDao)
  }
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        dslContext,
        eventPublisher,
        monitoringPlotsDao,
        parentStore,
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao)
  }
  private val observationService: ObservationService by lazy {
    ObservationService(
        clock,
        dslContext,
        eventPublisher,
        mockk(),
        observationPhotosDao,
        observationStore,
        plantingSiteStore,
        parentStore)
  }

  private val gen = ShapefileGenerator(defaultPermanentClusters = 1, defaultTemporaryPlots = 2)

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
    insertSpecies()

    every { user.canCreatePlantingSite(any()) } returns true
    every { user.canManageObservation(any()) } returns true
    every { user.canReadObservation(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadPlantingSubzone(any()) } returns true
    every { user.canReadPlantingZone(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  // Run test 10 times to exercise different random selections. 10 is somewhat arbitrary but given
  // the small size of the planting site, should be enough that a typical test run will fail at
  // least once if there's a bug.
  @RepeatedTest(10)
  fun `assigns plots to correct locations based on planting status of subzones`() {
    // Import a planting site with this structure, with 1m margin to account for rounding during
    // coordinate system conversion:
    //
    //                    Zone 1                      Zone 2
    //     +-------------------------------------|-------------+
    //     |                    |                |             |
    //     | Subzone 1          | Subzone 2      | Subzone 3   | 100m tall
    //     | (planted)          | (no plants)    | (no plants) |
    //     |                    |                |             |
    //     +-------------------------------------|-------------+
    //     |   |   |   |   |   |   |   |   |   |   |   |   |   | (plot borders)
    //     |       |       |       |       |       |       |     (cluster borders)

    val siteBoundary = gen.multiRectangle(0 to 0, 326 to 101)
    val zone1Boundary = gen.multiRectangle(0 to 0, 230 to 101)
    val subzone1Boundary = gen.multiRectangle(0 to 0, 130 to 101)
    val subzone2Boundary = gen.multiRectangle(130 to 0, 230 to 101)
    val zone2Boundary = gen.multiRectangle(230 to 0, 326 to 101)

    val siteFeature = gen.siteFeature(siteBoundary)
    val zone1Feature = gen.zoneFeature(zone1Boundary, permanentClusters = 2, temporaryPlots = 3)
    val subzone1Feature = gen.subzoneFeature(subzone1Boundary)
    val subzone2Feature = gen.subzoneFeature(subzone2Boundary)
    val zone2Feature = gen.zoneFeature(zone2Boundary, permanentClusters = 2, temporaryPlots = 2)
    val subzone3Feature = gen.subzoneFeature(zone2Boundary)

    val plantingSite =
        importSite(
            siteFeature,
            listOf(zone1Feature, zone2Feature),
            listOf(subzone1Feature, subzone2Feature, subzone3Feature))
    val subzone1 =
        plantingSite.plantingZones
            .first { it.name == "Z1" }
            .plantingSubzones
            .first { it.name == "S1" }

    insertWithdrawal()
    insertDelivery()
    insertPlanting(plantingSubzoneId = subzone1.id)

    val observationId =
        insertObservation(plantingSiteId = plantingSite.id, state = ObservationState.Upcoming)
    observationService.startObservation(observationId)

    val observationPlots = observationStore.fetchObservationPlotDetails(observationId)

    observationPlots.forEach { plot ->
      assertEquals(subzone1.fullName, plot.plantingSubzoneName, "Plot in unexpected subzone")
      if (!plot.boundary.coveredBy(subzone1.boundary)) {
        fail("Plot boundary ${plot.boundary} not within subzone boundary ${subzone1.boundary}")
      }
    }

    // Subzones 1 and 2 will always get one temporary plot each. The third temporary plot could
    // go in either one of them depending on where the permanent plots ended up. Any temporary
    // plots that ended up in subzone 2 will be discarded because subzone 2 has no plants.

    val numPermanentClusters = observationPlots.count { it.model.isPermanent } / 4
    val numTemporaryPlots = observationPlots.count { !it.model.isPermanent }

    when (numPermanentClusters) {
      0 -> {
        // Subzone 1 has no clusters, so it should get the extra plot; either subzone 2 has more
        // clusters or all the clusters were disqualified for being partially in an unplanted
        // subzone, in which case subzone 1 gets the extra plot because it's planted.
        assertEquals(2, numTemporaryPlots, "Temporary plots with 0 clusters")
      }
      1 -> {
        // Subzone 1 has one cluster, but we can't tell if the other cluster would have been in
        // subzone 2 (in which case the extra plot would go to subzone 1 since it's planted) or
        // straddles the subzone boundary (in which case it'd be eliminated and the extra plot
        // would go to subzone 2 for having fewer clusters).
        if (numTemporaryPlots < 1 || numTemporaryPlots > 2) {
          // This will always fail but will give a nice assertion failure message.
          assertEquals("either 1 or 2", "$numTemporaryPlots", "Temporary plots with 1 cluster")
        }
      }
      2 -> {
        // Subzone 1 has both clusters, so subzone 2 will get the extra plot.
        assertEquals(1, numTemporaryPlots, "Temporary plots with 2 clusters")
      }
      else -> {
        assertEquals("between 0 and 2", "$numPermanentClusters", "Number of permanent clusters")
      }
    }
  }

  /**
   * Imports a site from shapefile data. Records the site, zone, and subzone IDs in [DatabaseTest]'s
   * list of inserted IDs so they will be used as default values by [insertDelivery] and such.
   */
  private fun importSite(
      siteFeature: ShapefileFeature,
      zoneFeatures: List<ShapefileFeature>,
      subzoneFeatures: List<ShapefileFeature>,
      exclusionFeature: ShapefileFeature? = null,
  ): PlantingSiteModel {
    val plantingSiteId =
        plantingSiteImporter.importShapefiles(
            name = "Test Site ${nextPlantingSiteNumber++}",
            organizationId = organizationId,
            siteFile = Shapefile(listOf(siteFeature)),
            zonesFile = Shapefile(zoneFeatures),
            subzonesFile = Shapefile(subzoneFeatures),
            exclusionsFile = exclusionFeature?.let { Shapefile(listOf(it)) })

    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

    inserted.plantingSiteIds.add(plantingSiteId)
    plantingSite.plantingZones.forEach { zone ->
      inserted.plantingZoneIds.add(zone.id)
      zone.plantingSubzones.forEach { subzone ->
        inserted.plantingSubzoneIds.add(subzone.id)
        subzone.monitoringPlots.forEach { plot -> inserted.monitoringPlotIds.add(plot.id) }
      }
    }

    return plantingSite
  }
}
