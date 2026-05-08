package com.terraformation.backend.support

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.accelerator.tables.daos.DeliverablesDao
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.splat.event.SplatDeletedEvent
import com.terraformation.backend.splat.event.SplatGenerationFailedEvent
import com.terraformation.backend.splat.event.SplatMarkedNeedsAttentionEvent
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FailureReportingServiceTest {
  private val config: TerrawareServerConfig = mockk()
  private val deliverablesDao: DeliverablesDao = mockk()
  private val organizationStore: OrganizationStore = mockk()
  private val projectStore: ProjectStore = mockk()
  private val supportService: SupportService = mockk()
  private val systemUser: SystemUser = SystemUser(mockk())
  private val userStore: UserStore = mockk()

  private val service =
      FailureReportingService(
          config,
          deliverablesDao,
          organizationStore,
          projectStore,
          supportService,
          systemUser,
          userStore,
      )

  private val fileId = FileId(100)

  val orgModel: OrganizationModel = mockk()
  private val organizationId = OrganizationId(200)
  private val orgName = "Test Organization"

  val uploadedByUser: TerrawareUser = mockk()
  private val uploadedByUserId = UserId(300)
  private val uploadedByUserEmail = "uploaded@example.com"

  val currentUser: TerrawareUser = mockk()
  private val currentUserId = UserId(301)
  private val currentUserEmail = "current@example.com"

  private val videoUploadedTime = Instant.parse("2026-04-16T10:00:00Z")

  @BeforeEach
  fun setup() {
    every { organizationStore.fetchOneById(organizationId) } returns orgModel
    every { orgModel.name } returns orgName
    every { orgModel.timeZone } returns ZoneId.of("UTC")

    every { userStore.fetchOneById(uploadedByUserId) } returns uploadedByUser
    every { uploadedByUser.email } returns uploadedByUserEmail
    every { uploadedByUser.userId } returns uploadedByUserId

    every { userStore.fetchOneById(currentUserId) } returns currentUser
    every { currentUser.email } returns currentUserEmail
    every { currentUser.userId } returns currentUserId

    every { CurrentUserHolder.getCurrentUser() } returns currentUser
    every {
      supportService.submitServiceRequest(any(), any(), any(), any(), any(), any(), any())
    } returns "TEST-123"

    every { config.atlassian } returns
        TerrawareServerConfig.AtlassianConfig(
            enabled = true,
            account = "test-account",
            apiHost = "https://test.atlassian.net",
            apiToken = "test-token",
            serviceDeskKey = "TEST",
        )
  }

  @Nested
  inner class OnSplatMarkedNeedsAttention {
    @Test
    fun `creates Jira ticket with correct content`() {
      service.on(
          SplatMarkedNeedsAttentionEvent(
              fileId = fileId,
              markedByUserId = currentUserId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      verify {
        supportService.submitServiceRequest(
            requestType = SupportRequestType.BugReport,
            summary = "Virtual walkthrough marked as needs attention",
            description =
                withArg { description ->
                  assertTrue(description.contains("Virtual walkthrough #$fileId")) {
                    "Description should contain file ID: $description"
                  }
                  assertTrue(description.contains(orgName)) {
                    "Description should contain org name: $description"
                  }
                  assertTrue(description.contains("$organizationId")) {
                    "Description should contain org ID: $description"
                  }
                  assertTrue(description.contains(currentUserEmail)) {
                    "Description should contain user email: $description"
                  }
                },
            skipReceiptEmail = true,
            userId = currentUserId,
        )
      }
    }

    @Test
    fun `does not call support service when Atlassian is disabled`() {
      every { config.atlassian } returns TerrawareServerConfig.AtlassianConfig(enabled = false)

      service.on(
          SplatMarkedNeedsAttentionEvent(
              fileId = fileId,
              markedByUserId = currentUserId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      assertSupportRequestNotSubmitted()
    }
  }

  @Nested
  inner class OnSplatDeleted {
    @Test
    fun `creates Jira ticket with correct content`() {
      service.on(
          SplatDeletedEvent(
              deletedByUserId = currentUserId,
              fileId = fileId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      verify {
        supportService.submitServiceRequest(
            requestType = SupportRequestType.BugReport,
            summary = "Virtual walkthrough removed by user",
            description =
                """
            A virtual walkthrough was removed by a user.

            Virtual walkthrough #$fileId.

            Org: $orgName (ID: $organizationId)

            User who removed walkthrough: $currentUserEmail
            """
                    .trimIndent(),
            skipReceiptEmail = true,
            userId = currentUserId,
        )
      }
    }

    @Test
    fun `does not call support service when Atlassian is disabled`() {
      every { config.atlassian } returns TerrawareServerConfig.AtlassianConfig(enabled = false)

      service.on(
          SplatDeletedEvent(
              deletedByUserId = currentUserId,
              fileId = fileId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      assertSupportRequestNotSubmitted()
    }
  }

  @Nested
  inner class OnSplatGenerationFailed {
    @Test
    fun `creates Jira ticket with correct content`() {
      service.on(
          SplatGenerationFailedEvent(
              fileId = fileId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      verify {
        supportService.submitServiceRequest(
            requestType = SupportRequestType.BugReport,
            summary = "Virtual walkthrough processing failed",
            description =
                """
            Virtual walkthrough processing failed.
             
            Virtual walkthrough #${fileId}.

            Org: ${orgName} (ID: ${organizationId})
            
            User who uploaded: $uploadedByUserEmail
            
            Upload date: 2026-04-16
            """
                    .trimIndent(),
            skipReceiptEmail = true,
            userId = uploadedByUserId,
        )
      }
    }

    @Test
    fun `does not call support service when Atlassian is disabled`() {
      every { config.atlassian } returns TerrawareServerConfig.AtlassianConfig(enabled = false)

      service.on(
          SplatGenerationFailedEvent(
              fileId = fileId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      assertSupportRequestNotSubmitted()
    }
  }

  private fun assertSupportRequestNotSubmitted() {
    verify(exactly = 0) {
      supportService.submitServiceRequest(any(), any(), any(), any(), any(), any(), any())
    }
  }

  companion object {
    @JvmStatic
    @BeforeAll
    fun setupMock() {
      mockkObject(CurrentUserHolder)
    }

    @JvmStatic
    @BeforeAll
    fun teardownMock() {
      unmockkObject(CurrentUserHolder)
    }
  }
}
