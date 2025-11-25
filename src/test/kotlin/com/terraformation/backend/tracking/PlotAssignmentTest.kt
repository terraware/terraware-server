package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteImporter
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import com.terraformation.backend.util.nearlyCoveredBy
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
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        eventPublisher,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubzonesDao,
        parentStore,
    )
  }
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        IdentifierGenerator(clock, dslContext),
        monitoringPlotsDao,
        parentStore,
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao,
        eventPublisher,
    )
  }
  private val plantingSiteImporter: PlantingSiteImporter by lazy {
    PlantingSiteImporter(plantingSiteStore)
  }
  private val observationService: ObservationService by lazy {
    ObservationService(
        mockk(),
        clock,
        dslContext,
        eventPublisher,
        mockk(),
        monitoringPlotsDao,
        mockk(),
        observationMediaFilesDao,
        observationStore,
        plantingSiteStore,
        parentStore,
        SystemUser(usersDao),
        mockk(),
    )
  }

  private val gen = ShapefileGenerator(defaultPermanentPlots = 1, defaultTemporaryPlots = 2)

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

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
    //     |                    |                |             |
    //     +-------------------------------------|-------------+
    //     |   |   |   |   |   |   |   |   |   |   |   |   |   | (plot borders)

    val subzone1Boundary = gen.multiRectangle(0 to 0, 130 to 101)
    val subzone2Boundary = gen.multiRectangle(130 to 0, 230 to 101)
    val zone2Boundary = gen.multiRectangle(230 to 0, 326 to 101)

    val subzone1Feature =
        gen.subzoneFeature(subzone1Boundary, zone = "Z1", permanentPlots = 2, temporaryPlots = 3)
    val subzone2Feature = gen.subzoneFeature(subzone2Boundary)
    val subzone3Feature =
        gen.subzoneFeature(zone2Boundary, zone = "Z2", permanentPlots = 2, temporaryPlots = 3)

    val plantingSite = importSite(listOf(subzone1Feature, subzone2Feature, subzone3Feature))
    val subzone1 =
        plantingSite.plantingZones
            .first { it.name == "Z1" }
            .plantingSubzones
            .first { it.name == "S1" }

    val observationId =
        insertObservation(plantingSiteId = plantingSite.id, state = ObservationState.Upcoming)
    insertObservationRequestedSubzone(plantingSubzoneId = subzone1.id)
    observationService.startObservation(observationId)

    val observationPlots = observationStore.fetchObservationPlotDetails(observationId)

    observationPlots.forEach { plot ->
      assertEquals(subzone1.fullName, plot.plantingSubzoneName, "Plot in unexpected subzone")
      if (!plot.boundary.nearlyCoveredBy(subzone1.boundary)) {
        fail("Plot boundary ${plot.boundary} not within subzone boundary ${subzone1.boundary}")
      }
    }

    // Subzones 1 and 2 will always get one temporary plot each. The third temporary plot could
    // go in either one of them depending on where the permanent plots ended up. Any temporary
    // plots that ended up in subzone 2 will be discarded because subzone 2 has no plants.

    val numPermanentPlots = observationPlots.count { it.model.isPermanent }
    val numTemporaryPlots = observationPlots.count { !it.model.isPermanent }

    when (numPermanentPlots) {
      0 -> {
        // Subzone 1 has no permanent plots, so it should get the extra plot; either subzone 2 has
        // more permanent plots or all of them were disqualified for being partially in an
        // unrequested subzone, in which case subzone 1 gets the extra plot because it's requested.
        assertEquals(2, numTemporaryPlots, "Temporary plots with 0 permanent")
      }
      1 -> {
        // Subzone 1 has one permanent plot, but we can't tell if the other permanent plot would
        // have been in subzone 2 (in which case the extra plot would go to subzone 1 since it's
        // requested) or straddles the subzone boundary (in which case it'd be eliminated and the
        // extra plot would go to subzone 2 for having fewer permanent plots).
        if (numTemporaryPlots < 1 || numTemporaryPlots > 2) {
          // This will always fail but will give a nice assertion failure message.
          assertEquals("either 1 or 2", "$numTemporaryPlots", "Temporary plots with 1 permanent")
        }
      }
      2 -> {
        // Subzone 1 has both permanent plots, so subzone 2 will get the extra plot.
        assertEquals(1, numTemporaryPlots, "Temporary plots with 2 permanent")
      }
      else -> {
        assertEquals("between 0 and 2", "$numPermanentPlots", "Number of permanent plots")
      }
    }
  }

  /**
   * Imports a site from shapefile data. Records the site, zone, and subzone IDs in [DatabaseTest]'s
   * list of inserted IDs so they will be used as default values by [insertDelivery] and such.
   */
  private fun importSite(
      subzoneFeatures: List<ShapefileFeature>,
      exclusionFeature: ShapefileFeature? = null,
  ): ExistingPlantingSiteModel {
    val plantingSiteId =
        plantingSiteImporter.import(
            name = "Test Site ${nextPlantingSiteNumber++}",
            organizationId = organizationId,
            shapefiles =
                listOfNotNull(
                    Shapefile(subzoneFeatures),
                    exclusionFeature?.let { Shapefile(listOf(it)) },
                ),
        )

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
