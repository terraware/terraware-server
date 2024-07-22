package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationServiceTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val applicationStore = mockk<ApplicationStore>()
  private val preScreenVariableValuesFetcher = mockk<PreScreenVariableValuesFetcher>()
  private val service = ApplicationService(applicationStore, preScreenVariableValuesFetcher)

  private val applicationId = ApplicationId(1)
  private val organizationId = OrganizationId(2)
  private val projectId = ProjectId(3)

  @BeforeEach
  fun setUp() {
    every { user.canUpdateApplicationSubmissionStatus(any()) } returns true
  }

  @Nested
  inner class Submit {
    @Test
    fun `fetches variable values for pre-screen submissions`() {
      val preScreenVariableValues =
          PreScreenVariableValues(
              mapOf(LandUseModelType.Mangroves to BigDecimal(10)),
              123,
              PreScreenProjectType.Terrestrial)
      val applicationModel =
          ExistingApplicationModel(
              createdTime = Instant.EPOCH,
              id = applicationId,
              projectId = projectId,
              organizationId = organizationId,
              status = ApplicationStatus.NotSubmitted)
      val submissionResult = ApplicationSubmissionResult(applicationModel, listOf("error"))

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any()) } returns submissionResult
      every { preScreenVariableValuesFetcher.fetchValues(projectId) } returns
          preScreenVariableValues

      assertEquals(submissionResult, service.submit(applicationId))

      verify(exactly = 1) { applicationStore.submit(applicationId, preScreenVariableValues) }
    }

    @Test
    fun `does not fetch variable values for full application submissions`() {
      val applicationModel =
          ExistingApplicationModel(
              createdTime = Instant.EPOCH,
              id = applicationId,
              projectId = projectId,
              organizationId = organizationId,
              status = ApplicationStatus.PassedPreScreen)
      val submissionResult = ApplicationSubmissionResult(applicationModel, listOf("error"))

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any()) } returns submissionResult

      assertEquals(submissionResult, service.submit(applicationId))

      verify(exactly = 1) { applicationStore.submit(applicationId, null) }
    }
  }
}
