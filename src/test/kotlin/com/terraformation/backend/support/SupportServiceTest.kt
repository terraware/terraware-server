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
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.use
import com.terraformation.backend.support.atlassian.AtlassianHttpClient
import com.terraformation.backend.support.atlassian.model.SupportRequestType
import com.terraformation.backend.support.atlassian.request.PostServiceDeskRequestResponse
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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
  private val systemUser by lazy { SystemUser(usersDao) }
  private val service by lazy {
    SupportService(atlassianHttpClient, config, emailService, messages, systemUser, userStore)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization(name = "Test Organization")
    every { config.atlassian.enabled } returns true
    every { atlassianHttpClient.createServiceDeskRequest(any(), any(), any(), any()) } returns
        PostServiceDeskRequestResponse("123", "SUP-123")
  }

  @Test
  fun `missing contact event creates one English ticket as the system user without a receipt`() {
    val event =
        TerraformationContactMissingEvent(
            inserted.organizationId,
            "Test Organization",
            "Original notification subject",
            "Original notification body",
        )

    Locales.GIBBERISH.use { service.on(event) }

    verify(exactly = 1) {
      atlassianHttpClient.createServiceDeskRequest(
          "There is currently no assigned Terraformation primary project contact for organization Test Organization (${inserted.organizationId}). Please assign one.\n\n" +
              "Original notification subject\n\nOriginal notification body",
          "Organization Test Organization has no primary project contact",
          SupportRequestType.MissingContact,
          systemUser.email,
      )
    }
    verify { emailService wasNot Called }
    assertIsEventListener<TerraformationContactMissingEvent>(service)
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
  fun `missing contact tickets respect Jira field limits`(lengthOffset: Int) {
    val organizationName =
        "x".repeat(255 + lengthOffset - "Organization  has no primary project contact".length)
    val missingContact = messages.missingContact(organizationName, inserted.organizationId)
    val subject = "Original notification subject"
    val descriptionPrefix = "${missingContact.body}\n\n$subject\n\n"
    val body = "x".repeat(32767 + lengthOffset - descriptionPrefix.length)
    val description = descriptionPrefix + body

    service.on(
        TerraformationContactMissingEvent(
            inserted.organizationId,
            organizationName,
            subject,
            body,
        )
    )

    val expectedSummary =
        if (lengthOffset > 0) missingContact.title.take(252) + "..." else missingContact.title
    val expectedDescription = if (lengthOffset > 0) description.take(32764) + "..." else description
    verify(exactly = 1) {
      atlassianHttpClient.createServiceDeskRequest(
          expectedDescription,
          expectedSummary,
          SupportRequestType.MissingContact,
          systemUser.email,
      )
    }
    verify { emailService wasNot Called }
  }
}
