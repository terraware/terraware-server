package com.terraformation.backend.email

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.daily.NotificationJobFinishedEvent
import com.terraformation.backend.daily.NotificationJobSucceededEvent
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import freemarker.template.Configuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.URI
import java.time.Duration
import java.time.Instant
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.MimeMessage
import javax.ws.rs.core.MediaType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl

internal class EmailNotificationServiceTest {
  private val adminUser: IndividualUser = mockk()
  private val automationStore: AutomationStore = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val deviceStore: DeviceStore = mockk()
  private val facilityStore: FacilityStore = mockk()
  private val organizationStore: OrganizationStore = mockk()
  private val parentStore: ParentStore = mockk()
  private val sender: EmailSender = mockk()
  private val user: IndividualUser = mockk()
  private val userStore: UserStore = mockk()

  private val webAppUrls: WebAppUrls = WebAppUrls(config)

  private val freeMarkerConfig =
      Configuration(Configuration.VERSION_2_3_31).apply {
        // Load the email template files from the templates folder in the build output.
        setClassLoaderForTemplateLoading(
            EmailNotificationServiceTest::class.java.classLoader, "templates")
      }

  private val emailService =
      EmailService(config, freeMarkerConfig, organizationStore, parentStore, sender)

  private val service =
      EmailNotificationService(
          automationStore,
          config,
          deviceStore,
          emailService,
          facilityStore,
          organizationStore,
          parentStore,
          userStore,
          webAppUrls)

  private val organization =
      OrganizationModel(
          OrganizationId(99), "Test Organization", createdTime = Instant.EPOCH, totalUsers = 1)
  private val facility: FacilityModel =
      FacilityModel(
          connectionState = FacilityConnectionState.Configured,
          createdTime = Instant.EPOCH,
          description = null,
          id = FacilityId(123),
          modifiedTime = Instant.EPOCH,
          name = "Test Facility",
          organizationId = organization.id,
          lastTimeseriesTime = Instant.EPOCH,
          maxIdleMinutes = 15,
          nextNotificationTime = Instant.EPOCH,
          type = FacilityType.SeedBank)
  private val devicesRow =
      DevicesRow(
          id = DeviceId(8), facilityId = facility.id, name = "Test Device", deviceType = "sensor")
  private val automation =
      AutomationModel(
          createdTime = Instant.EPOCH,
          description = null,
          deviceId = devicesRow.id!!,
          facilityId = facility.id,
          id = AutomationId(9),
          lowerThreshold = 1.0,
          modifiedTime = Instant.EPOCH,
          name = "Test Automation",
          settings = null,
          timeseriesName = "Test Timeseries",
          type = AutomationModel.SENSOR_BOUNDS_TYPE,
          upperThreshold = 2.0,
          verbosity = 0)
  private val accessionId = AccessionId(13)
  private val accessionNumber = "202201010001"

  private val organizationRecipients = setOf("org1@terraware.io", "org2@terraware.io")

  private val mimeMessageSlot = slot<MimeMessage>()
  private val recipients = mutableSetOf<String>()

  @BeforeEach
  fun setUp() {
    val emailConfig: TerrawareServerConfig.EmailConfig = mockk(relaxed = true)
    every { config.email } returns emailConfig
    every { config.webAppUrl } returns URI("https://test.terraware.io")
    every { emailConfig.enabled } returns true
    every { emailConfig.senderAddress } returns "testsender@terraware.io"

    every { adminUser.canSendAlert(any()) } returns true
    every { adminUser.email } returns "admin@test.com"
    every { adminUser.fullName } returns "Admin Name"
    every { adminUser.userId } returns UserId(1)
    every { automationStore.fetchOneById(automation.id) } returns automation
    every { deviceStore.fetchOneById(devicesRow.id!!) } returns devicesRow
    every { facilityStore.fetchOneById(facility.id) } returns facility
    every { organizationStore.fetchEmailRecipients(any(), any()) } returns
        organizationRecipients.toList()
    every { organizationStore.fetchOneById(organization.id) } returns organization
    every { parentStore.getFacilityId(accessionId) } returns facility.id
    every { parentStore.getFacilityName(accessionId) } returns facility.name
    every { parentStore.getOrganizationId(accessionId) } returns organization.id
    every { parentStore.getOrganizationId(facility.id) } returns organization.id
    every { sender.createMimeMessage() } returns JavaMailSenderImpl().createMimeMessage()
    every { user.email } returns "user@test.com"
    every { user.emailNotificationsEnabled } returns true
    every { user.fullName } returns "Normal User"
    every { user.userId } returns UserId(2)
    every { userStore.fetchOneById(adminUser.userId) } returns adminUser
    every { userStore.fetchOneById(user.userId) } returns user

    every { sender.send(capture(mimeMessageSlot)) } answers
        { answer ->
          (answer.invocation.args[0] as? MimeMessage)
              ?.getRecipientsString(Message.RecipientType.TO)
              ?.let { recipients.addAll(it) }
              ?: fail("No recipients found")
          "message id"
        }
    every { userStore.fetchByEmail(any()) } answers
        { answer ->
          val mock: IndividualUser = mockk()
          val email = answer.invocation.args[0] as String

          every { mock.email } returns email
          every { mock.emailNotificationsEnabled } returns true
          mock
        }
  }

  @Test
  fun facilityAlertRequested() {
    val body = "test body"
    val subject = "test subject"

    service.on(FacilityAlertRequestedEvent(facility.id, subject, body, adminUser.userId))

    assertSubjectContains(subject)
    assertBodyContains(body, "Alert body", hasTextHtml = false)
    assertBodyContains(facility.name, "Facility name", hasTextHtml = false)
    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun facilityIdle() {
    service.on(FacilityIdleEvent(facility.id))

    assertBodyContains(webAppUrls.fullFacilityMonitoring(organization.id, facility.id), "Link URL")
    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun sensorBoundsAlertTriggered() {
    service.on(SensorBoundsAlertTriggeredEvent(automation.id, 3.1))

    assertBodyContains(devicesRow.name!!, "Device name")
    assertBodyContains(
        webAppUrls.fullFacilityMonitoring(organization.id, facility.id, devicesRow), "Link URL")
    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun unknownAutomationTriggeredEvent() {
    service.on(UnknownAutomationTriggeredEvent(automation.id, "Bogus Type", "Test Message"))

    assertBodyContains(automation.name, "Automation name")
    assertBodyContains(
        webAppUrls.fullFacilityMonitoring(organization.id, facility.id, devicesRow), "Link URL")
    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun deviceUnresponsive() {
    service.on(DeviceUnresponsiveEvent(devicesRow.id!!, Instant.EPOCH, Duration.ofMinutes(14)))

    assertBodyContains(devicesRow.name!!, "Device name")
    assertBodyContains(
        webAppUrls.fullFacilityMonitoring(organization.id, facility.id, devicesRow), "Link URL")
    assertEquals(organizationRecipients, recipients, "Recipients")
    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun userAddedToOrganization() {
    service.on(UserAddedToOrganizationEvent(user.userId, organization.id, adminUser.userId))

    assertBodyContains(organization.name, "Organization name")
    assertBodyContains(adminUser.fullName!!, "Admin name")
    assertBodyContains(webAppUrls.fullOrganizationHome(organization.id), "Link URL")
    assertRecipientsEqual(setOf(user.email))
  }

  @Test
  fun accessionDryingEnd() {
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    service.on(NotificationJobSucceededEvent())

    assertBodyContains(facility.name, "Facility name")
    assertBodyContains(accessionNumber, "Accession number")
    assertBodyContains(webAppUrls.fullAccession(accessionId, organization.id), "Link URL")
    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun `accession daily task emails accumulate until processing is finished`() {
    every { organizationStore.fetchEmailRecipients(organization.id, any()) } returns
        listOf("1@test.com")
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    every { organizationStore.fetchEmailRecipients(organization.id, any()) } returns
        listOf("2@test.com")
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))

    verify(exactly = 0) { sender.send(any()) }

    service.on(NotificationJobSucceededEvent())

    assertEquals(setOf("1@test.com", "2@test.com"), recipients, "Recipients")
  }

  @Test
  fun `accession daily task emails are discarded if processing fails`() {
    every { organizationStore.fetchEmailRecipients(organization.id, any()) } returns
        listOf("1@test.com")
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    service.on(NotificationJobFinishedEvent())

    every { organizationStore.fetchEmailRecipients(organization.id, any()) } returns
        listOf("2@test.com")
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    service.on(NotificationJobSucceededEvent())

    assertEquals(setOf("2@test.com"), recipients, "Recipients")
  }

  private fun assertRecipientsEqual(expected: Set<String>) {
    assertEquals(expected, recipients, "Recipients")
  }

  private fun assertSubjectContains(@Suppress("SameParameterValue") text: String) {
    assertContains(text, mimeMessageSlot.captured.subject, "Subject")
  }

  private fun assertBodyContains(
      substring: Any,
      messagePrefix: String,
      hasTextPlain: Boolean = true,
      hasTextHtml: Boolean = true,
  ) {
    val substringText = "$substring"
    var foundTextPlain = false
    var foundTextHtml = false

    textParts(mimeMessageSlot.captured).forEach { part ->
      if (part.dataHandler.contentType.startsWith(MediaType.TEXT_HTML, ignoreCase = true)) {
        foundTextHtml = true
        assertContains(substringText, part.content.toString(), "$messagePrefix: text/html")
      } else if (part.dataHandler.contentType.startsWith(MediaType.TEXT_PLAIN, ignoreCase = true)) {
        foundTextPlain = true
        assertContains(substringText, part.content.toString(), "$messagePrefix: text/plain")
      } else {
        fail("$messagePrefix: Unexpected content type: ${part.dataHandler.contentType}")
      }
    }

    assertEquals(hasTextPlain, foundTextPlain, "Has text/plain part")
    assertEquals(hasTextHtml, foundTextHtml, "Has text/html part")
  }

  private fun assertContains(needle: String, haystack: String, message: String) {
    if (!haystack.contains(needle)) {
      // This isn't actually checking for equality, just logging the expected and actual text in a
      // tool-friendly form.
      assertEquals(needle, haystack, message)
    }
  }

  /** Walks a MIME message of arbitrary structure and returns the text parts. */
  private fun textParts(part: Part): Sequence<Part> = sequence {
    val content = part.content
    if (content is Multipart) {
      yieldAll(
          (0 until content.count)
              .asSequence()
              .map { index -> content.getBodyPart(index) }
              .flatMap { textParts(it) })
    } else if (part.dataHandler.contentType.startsWith("text/", ignoreCase = true)) {
      yield(part)
    }
  }
}
