package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationModuleStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val store: ApplicationModuleStore by lazy {
    ApplicationModuleStore(dslContext)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization(countryCode = "US")
    insertProject()
    insertApplication()

    every { user.canReadApplication(any()) } returns true
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns modules with statuses for application`() {
      val prescreenModule = insertModule(phase = CohortPhase.PreScreen)
      val applicationModule = insertModule(phase = CohortPhase.Application)

      val result = store.fetch(inserted.applicationId)
    }

    @Test
    fun `throws exception if no permission to read application`() {
    }
  }
}
