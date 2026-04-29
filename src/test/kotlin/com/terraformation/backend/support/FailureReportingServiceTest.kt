package com.terraformation.backend.support

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
import io.mockk.verify
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertTrue
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

  @Nested
  inner class OnSplatMarkedNeedsAttention {
    private val fileId = FileId(100)
    private val organizationId = OrganizationId(200)
    private val orgName = "Test Organization"
    private val userEmail = "user@example.com"
    private val uploadedByUserId = UserId(300)
    private val markedByUserId = UserId(301)
    private val videoUploadedTime = Instant.parse("2026-04-16T10:00:00Z")

    @BeforeEach
    fun setUp() {
      val orgModel: OrganizationModel = mockk()
      every { orgModel.name } returns orgName
      every { organizationStore.fetchOneById(organizationId) } returns orgModel

      val markedByUser: TerrawareUser = mockk()
      every { markedByUser.email } returns userEmail
      every { userStore.fetchOneById(markedByUserId) } returns markedByUser
    }

    @Test
    fun `creates Jira ticket with correct content`() {
      every { config.atlassian } returns
          TerrawareServerConfig.AtlassianConfig(
              enabled = true,
              account = "test-account",
              apiHost = "https://test.atlassian.net",
              apiToken = "test-token",
              serviceDeskKey = "TEST",
          )
      every { supportService.submitServiceRequest(any(), any(), any()) } returns "TEST-123"

      service.on(
          SplatMarkedNeedsAttentionEvent(
              fileId = fileId,
              markedByUserId = markedByUserId,
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
                  assertTrue(description.contains(userEmail)) {
                    "Description should contain user email: $description"
                  }
                },
            skipReceiptEmail = true,
        )
      }
    }

    @Test
    fun `does not call support service when Atlassian is disabled`() {
      every { config.atlassian } returns TerrawareServerConfig.AtlassianConfig(enabled = false)

      service.on(
          SplatMarkedNeedsAttentionEvent(
              fileId = fileId,
              markedByUserId = markedByUserId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      verify(exactly = 0) { supportService.submitServiceRequest(any(), any(), any()) }
    }
  }

  @Nested
  inner class OnSplatDeleted {
    private val fileId = FileId(100)
    private val organizationId = OrganizationId(200)
    private val orgName = "Test Organization"
    private val userEmail = "user@example.com"
    private val uploadedByUserId = UserId(300)
    private val deletedByUserId = UserId(301)
    private val videoUploadedTime = Instant.parse("2026-04-16T10:00:00Z")

    @BeforeEach
    fun setUp() {
      val orgModel: OrganizationModel = mockk()
      every { orgModel.name } returns orgName
      every { organizationStore.fetchOneById(organizationId) } returns orgModel

      val deletedByUser: TerrawareUser = mockk()
      every { deletedByUser.email } returns userEmail
      every { userStore.fetchOneById(deletedByUserId) } returns deletedByUser
    }

    @Test
    fun `creates Jira ticket with correct content`() {
      every { config.atlassian } returns
          TerrawareServerConfig.AtlassianConfig(
              enabled = true,
              account = "test-account",
              apiHost = "https://test.atlassian.net",
              apiToken = "test-token",
              serviceDeskKey = "TEST",
          )
      every { supportService.submitServiceRequest(any(), any(), any()) } returns "TEST-123"

      service.on(
          SplatDeletedEvent(
              deletedByUserId = deletedByUserId,
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

            User who removed walkthrough: $userEmail
            """
                    .trimIndent(),
            skipReceiptEmail = true,
        )
      }
    }

    @Test
    fun `does not call support service when Atlassian is disabled`() {
      every { config.atlassian } returns TerrawareServerConfig.AtlassianConfig(enabled = false)

      service.on(
          SplatDeletedEvent(
              deletedByUserId = deletedByUserId,
              fileId = fileId,
              organizationId = organizationId,
              uploadedByUserId = uploadedByUserId,
              videoUploadedTime = videoUploadedTime,
          )
      )

      verify(exactly = 0) { supportService.submitServiceRequest(any(), any(), any()) }
    }
  }

  @Nested
  inner class OnSplatGenerationFailed {
    private val fileId = FileId(100)
    private val organizationId = OrganizationId(200)
    private val orgName = "Test Organization"
    private val userEmail = "user@example.com"
    private val uploadedByUserId = UserId(300)
    private val videoUploadedTime = Instant.parse("2026-04-16T10:00:00Z")

    @BeforeEach
    fun setUp() {
      val orgModel: OrganizationModel = mockk()
      every { orgModel.name } returns orgName
      every { orgModel.timeZone } returns ZoneOffset.UTC
      every { organizationStore.fetchOneById(organizationId) } returns orgModel

      val uploadedByUser: TerrawareUser = mockk()
      every { uploadedByUser.email } returns userEmail
      every { userStore.fetchOneById(uploadedByUserId) } returns uploadedByUser
    }

    @Test
    fun `creates Jira ticket with correct content`() {
      every { config.atlassian } returns
          TerrawareServerConfig.AtlassianConfig(
              enabled = true,
              account = "test-account",
              apiHost = "https://test.atlassian.net",
              apiToken = "test-token",
              serviceDeskKey = "TEST",
          )
      every { supportService.submitServiceRequest(any(), any(), any()) } returns "TEST-123"

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
            
            User who uploaded: $userEmail
            
            Upload date: 2026-04-16
            """
                    .trimIndent(),
            skipReceiptEmail = true,
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

      verify(exactly = 0) { supportService.submitServiceRequest(any(), any(), any()) }
    }
  }
}
