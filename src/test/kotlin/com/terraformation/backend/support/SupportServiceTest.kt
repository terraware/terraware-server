package com.terraformation.backend.support

import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.TerraformationContactMissingEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.email.model.SupportRequestSubmitted
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.use
import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import com.terraformation.backend.support.atlassian.request.PostServiceDeskRequestResponse
import io.mockk.Called
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class SupportServiceTest : DatabaseTest() {
  private val config: TerrawareServerConfig = mockk(relaxed = true)
  private val atlassianHttpClient: AtlassianHttpClient = mockk()
  private val emailService: EmailService = mockk()
  private val messages = Messages()
  private val eventPublisher = TestEventPublisher()
  private val parentStore by lazy { ParentStore(dslContext) }
  private val organizationStore by lazy {
    OrganizationStore(clock, dslContext, organizationsDao, eventPublisher)
  }
  private val userStore by lazy {
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
  private val service by lazy {
    SupportService(atlassianHttpClient, config, emailService, messages, userStore)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization(name = "Test Organization")
    every { config.atlassian.enabled } returns true
    every { atlassianHttpClient.createServiceDeskRequest(any(), any(), any(), any()) } returns
        PostServiceDeskRequestResponse("123", "SUP-123")
  }

  @ParameterizedTest
  @EnumSource(names = ["Individual", "Funder"])
  fun `individual and funder users can submit support requests`(userType: UserType) {
    every { emailService.sendUserNotification(any(), any(), any()) } just runs
    val userId = insertUser(email = "user@example.com", type = userType)

    val issueKey =
        service.submitServiceRequest(
            requestType = SupportRequestType.BugReport,
            summary = "Summary",
            description = "Description",
            userId = userId,
        )

    assertEquals("SUP-123", issueKey, "Issue key")
    verify(exactly = 1) {
      atlassianHttpClient.createServiceDeskRequest(
          "Description",
          "Summary",
          SupportRequestType.BugReport,
          "user@example.com",
      )
    }
    verify(exactly = 1) {
      emailService.sendUserNotification(
          match { it.userId == userId },
          any<SupportRequestSubmitted>(),
          false,
      )
    }
  }

  @Test
  fun `missing contact event does not create a ticket when Atlassian is disabled`() {
    every { config.atlassian.enabled } returns false

    service.on(
        TerraformationContactMissingEvent(
            inserted.organizationId,
            "Test Organization",
            "Original notification subject",
            "Original notification body",
        )
    )

    verify { atlassianHttpClient wasNot Called }
    verify { emailService wasNot Called }
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, 0, 1])
  fun `missing contact event creates English ticket as email sender within Jira field limits`(
      lengthOffset: Int
  ) {
    every { config.email.senderAddress } returns "Terraware <no-reply@terraware.io>"

    val organizationName =
        "x".repeat(255 + lengthOffset - "Organization  has no primary project contact".length)
    val missingContact = messages.missingContact(organizationName, inserted.organizationId)
    val subject = "Original notification subject"
    val descriptionPrefix = "${missingContact.body}\n\n$subject\n\n"
    val body = "x".repeat(32767 + lengthOffset - descriptionPrefix.length)
    val description = descriptionPrefix + body

    Locales.GIBBERISH.use {
      service.on(
          TerraformationContactMissingEvent(
              inserted.organizationId,
              organizationName,
              subject,
              body,
          )
      )
    }

    val expectedSummary =
        if (lengthOffset > 0) missingContact.title.take(252) + "..." else missingContact.title
    val expectedDescription = if (lengthOffset > 0) description.take(32764) + "..." else description
    verify(exactly = 1) {
      atlassianHttpClient.createServiceDeskRequest(
          expectedDescription,
          expectedSummary,
          SupportRequestType.MissingContact,
          "no-reply@terraware.io",
      )
    }
    verify { emailService wasNot Called }
    assertIsEventListener<TerraformationContactMissingEvent>(service)
  }
}
