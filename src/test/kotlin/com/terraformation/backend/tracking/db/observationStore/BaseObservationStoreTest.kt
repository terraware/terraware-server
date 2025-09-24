package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.mockUser
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.ObservationTestHelper
import com.terraformation.backend.util.HECTARES_IN_PLOT
import io.mockk.every
import org.junit.jupiter.api.BeforeEach

abstract class BaseObservationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  protected val hectaresInPlot = HECTARES_IN_PLOT.toBigDecimal()
  protected val divisionScale = 10

  protected val clock = TestClock()
  protected val store: ObservationStore by lazy {
    ObservationStore(
        clock,
        dslContext,
        observationsDao,
        observationPlotConditionsDao,
        observationPlotsDao,
        observationRequestedSubzonesDao,
        ParentStore(dslContext),
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
