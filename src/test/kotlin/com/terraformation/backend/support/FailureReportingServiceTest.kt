package com.terraformation.backend.support

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.splat.event.SplatDeletedEvent
import com.terraformation.backend.splat.event.SplatGenerationFailedEvent
import com.terraformation.backend.splat.event.SplatMarkedNeedsAttentionEvent
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FailureReportingServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val config: TerrawareServerConfig = mockk()
  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val supportService: SupportService = mockk()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }

  private val organizationStore: OrganizationStore by lazy {
    OrganizationStore(clock, dslContext, organizationsDao, eventPublisher)
  }

  private val projectStore: ProjectStore by lazy {
    ProjectStore(clock, dslContext, eventPublisher, ParentStore(dslContext), projectsDao)
  }

  private val userStore: UserStore by lazy {
    UserStore(
        clock,
        config,
        dslContext,
        mockk(),
        InMemoryKeycloakAdminClient(),
        dummyKeycloakInfo(),
        organizationStore,
        parentStore,
        PermissionStore(dslContext),
        eventPublisher,
        usersDao,
    )
  }

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }

  private val service: FailureReportingService by lazy {
    FailureReportingService(
        config,
        deliverablesDao,
        organizationStore,
        projectStore,
        supportService,
        systemUser,
        userStore,
    )
  }

  private lateinit var fileId: FileId

  private val orgName = "Test Organization"
  private lateinit var organizationId: OrganizationId

  private lateinit var uploadedByUserId: UserId
  private val uploadedByUserEmail = "uploaded@example.com"

  private lateinit var currentUserId: UserId
  private val currentUserEmail = "current@example.com"

  private val videoUploadedTime = Instant.parse("2026-04-16T10:00:00Z")

  @BeforeEach
  fun setup() {
    fileId = insertFile()
    organizationId = insertOrganization(name = orgName)
    currentUserId = insertUser(email = currentUserEmail)
    uploadedByUserId = insertUser(email = uploadedByUserEmail)

    switchToUser(currentUserId)

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

    every { config.keycloak } returns
        TerrawareServerConfig.KeycloakConfig(
            apiClientId = "dummy",
            apiClientGroupName = "dummy",
            apiClientUsernamePrefix = "dummy",
        )
  }

  @AfterEach
  fun cleanup() {
    CurrentUserHolder.clearCurrentUser()
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
}
