package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class ApplicationDeliverableStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val store: ApplicationDeliverableStore by lazy {
    ApplicationDeliverableStore(dslContext)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization(countryCode = "US")
    insertProject()

    every { user.canReadApplication(any()) } returns true
  }

  @Nested
  inner class Fetch {
  }
}
