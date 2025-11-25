package com.terraformation.backend.tracking.db.biomassStore

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.BiomassStore
import com.terraformation.backend.tracking.db.ObservationLocker
import io.mockk.every
import org.junit.jupiter.api.BeforeEach

abstract class BaseBiomassStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  protected val eventPublisher = TestEventPublisher()
  protected val store: BiomassStore by lazy {
    BiomassStore(dslContext, eventPublisher, ObservationLocker(dslContext), ParentStore(dslContext))
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
