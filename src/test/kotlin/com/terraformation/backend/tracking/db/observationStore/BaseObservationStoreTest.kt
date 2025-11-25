package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.ObservationLocker
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.ObservationTestHelper
import io.mockk.every
import org.junit.jupiter.api.BeforeEach

abstract class BaseObservationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  protected val clock = TestClock()
  protected val eventPublisher = TestEventPublisher()
  protected val store: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        eventPublisher,
        ObservationLocker(dslContext),
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubzonesDao,
    )
  }
  protected val helper: ObservationTestHelper by lazy {
    ObservationTestHelper(this, store, user.userId)
  }

  protected lateinit var organizationId: OrganizationId
  protected lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    plantingSiteId = insertPlantingSite(x = 0)

    every { user.canCreateObservation(any()) } returns true
    every { user.canManageObservation(any()) } returns true
    every { user.canReadObservation(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canScheduleAdHocObservation(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
  }
}
