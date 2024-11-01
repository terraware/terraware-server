package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.PlantingSiteStore
import io.mockk.every
import java.time.ZoneId
import org.junit.jupiter.api.BeforeEach

internal abstract class PlantingSiteStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  protected val clock = TestClock()
  protected val eventPublisher = TestEventPublisher()
  protected val store: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        monitoringPlotsDao,
        ParentStore(dslContext),
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao)
  }

  protected lateinit var timeZone: ZoneId

  protected lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    timeZone = ZoneId.of("Pacific/Honolulu")

    every { user.canCreatePlantingSite(any()) } returns true
    every { user.canMovePlantingSiteToAnyOrg(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadPlantingSubzone(any()) } returns true
    every { user.canReadPlantingZone(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
    every { user.canUpdatePlantingSubzone(any()) } returns true
    every { user.canUpdatePlantingZone(any()) } returns true
  }
}
