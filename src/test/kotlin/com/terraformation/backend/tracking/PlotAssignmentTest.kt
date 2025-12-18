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
import com.terraformation.backend.tracking.db.ObservationLocker
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
  private val observationLocker: ObservationLocker by lazy { ObservationLocker(dslContext) }
  private val observationStore: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        eventPublisher,
        observationLocker,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubstrataDao,
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
        eventPublisher,
        strataDao,
        substrataDao,
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
        observationLocker,
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
    every { user.canReadStratum(any()) } returns true
    every { user.canReadSubstratum(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  // Run test 10 times to exercise different random selections. 10 is somewhat arbitrary but given
  // the small size of the planting site, should be enough that a typical test run will fail at
  // least once if there's a bug.
  @RepeatedTest(10)
  fun `assigns plots to correct locations based on planting status of substrata`() {
    // Import a planting site with this structure, with 1m margin to account for rounding during
    // coordinate system conversion:
    //
    //                   Stratum 1                  Stratum 2
    //     +-------------------------------------|--------------+
    //     |                    |                |              |
    //     | Substratum 1       | Substratum 2   | Substratum 3 | 100m tall
    //     |                    |                |              |
    //     +-------------------------------------|--------------+
    //     |   |   |   |   |    |   |   |   |   |   |   |   |   | (plot borders)

    val substratum1Boundary = gen.multiRectangle(0 to 0, 130 to 101)
    val substratum2Boundary = gen.multiRectangle(130 to 0, 230 to 101)
    val stratum2Boundary = gen.multiRectangle(230 to 0, 326 to 101)

    val substratum1Feature =
        gen.substratumFeature(
            substratum1Boundary,
            stratum = "Z1",
            permanentPlots = 2,
            temporaryPlots = 3,
        )
    val substratum2Feature = gen.substratumFeature(substratum2Boundary)
    val substratum3Feature =
        gen.substratumFeature(
            stratum2Boundary,
            stratum = "Z2",
            permanentPlots = 2,
            temporaryPlots = 3,
        )

    val plantingSite =
        importSite(listOf(substratum1Feature, substratum2Feature, substratum3Feature))
    val substratum1 =
        plantingSite.strata.first { it.name == "Z1" }.substrata.first { it.name == "S1" }

    val observationId =
        insertObservation(plantingSiteId = plantingSite.id, state = ObservationState.Upcoming)
    insertObservationRequestedSubstratum(substratumId = substratum1.id)
    observationService.startObservation(observationId)

    val observationPlots = observationStore.fetchObservationPlotDetails(observationId)

    observationPlots.forEach { plot ->
      assertEquals(substratum1.fullName, plot.substratumName, "Plot in unexpected substratum")
      if (!plot.boundary.nearlyCoveredBy(substratum1.boundary)) {
        fail(
            "Plot boundary ${plot.boundary} not within substratum boundary ${substratum1.boundary}"
        )
      }
    }

    // Substrata 1 and 2 will always get one temporary plot each. The third temporary plot could
    // go in either one of them depending on where the permanent plots ended up. Any temporary
    // plots that ended up in substratum 2 will be discarded because substratum 2 has no plants.

    val numPermanentPlots = observationPlots.count { it.model.isPermanent }
    val numTemporaryPlots = observationPlots.count { !it.model.isPermanent }

    when (numPermanentPlots) {
      0 -> {
        // Substratum 1 has no permanent plots, so it should get the extra plot; either substratum 2
        // has more permanent plots or all of them were disqualified for being partially in an
        // unrequested substratum, in which case substratum 1 gets the extra plot because it's
        // requested.
        assertEquals(2, numTemporaryPlots, "Temporary plots with 0 permanent")
      }
      1 -> {
        // Substratum 1 has one permanent plot, but we can't tell if the other permanent plot would
        // have been in substratum 2 (in which case the extra plot would go to substratum 1 since
        // it's requested) or straddles the substratum boundary (in which case it'd be eliminated
        // and the extra plot would go to substratum 2 for having fewer permanent plots).
        if (numTemporaryPlots < 1 || numTemporaryPlots > 2) {
          // This will always fail but will give a nice assertion failure message.
          assertEquals("either 1 or 2", "$numTemporaryPlots", "Temporary plots with 1 permanent")
        }
      }
      2 -> {
        // Substratum 1 has both permanent plots, so substratum 2 will get the extra plot.
        assertEquals(1, numTemporaryPlots, "Temporary plots with 2 permanent")
      }
      else -> {
        assertEquals("between 0 and 2", "$numPermanentPlots", "Number of permanent plots")
      }
    }
  }

  /**
   * Imports a site from shapefile data. Records the site, stratum, and substratum IDs in
   * [DatabaseTest]'s list of inserted IDs so they will be used as default values by
   * [insertDelivery] and such.
   */
  private fun importSite(
      substratumFeatures: List<ShapefileFeature>,
      exclusionFeature: ShapefileFeature? = null,
  ): ExistingPlantingSiteModel {
    val plantingSiteId =
        plantingSiteImporter.import(
            name = "Test Site ${nextPlantingSiteNumber++}",
            organizationId = organizationId,
            shapefiles =
                listOfNotNull(
                    Shapefile(substratumFeatures),
                    exclusionFeature?.let { Shapefile(listOf(it)) },
                ),
        )

    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

    inserted.plantingSiteIds.add(plantingSiteId)
    plantingSite.strata.forEach { stratum ->
      inserted.stratumIds.add(stratum.id)
      stratum.substrata.forEach { substratum ->
        inserted.substratumIds.add(substratum.id)
        substratum.monitoringPlots.forEach { plot -> inserted.monitoringPlotIds.add(plot.id) }
      }
    }

    return plantingSite
  }
}
