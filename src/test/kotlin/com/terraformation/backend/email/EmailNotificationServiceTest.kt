package com.terraformation.backend.email

import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.event.ApplicationSubmittedEvent
import com.terraformation.backend.accelerator.event.DeliverableReadyForReviewEvent
import com.terraformation.backend.accelerator.event.DeliverableStatusUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesAddedToProjectNotificationDueEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ExistingParticipantModel
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserInternalInterestsStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.daily.NotificationJobFinishedEvent
import com.terraformation.backend.daily.NotificationJobSucceededEvent
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.event.DeviceUnresponsiveEvent
import com.terraformation.backend.device.event.SensorBoundsAlertTriggeredEvent
import com.terraformation.backend.device.event.UnknownAutomationTriggeredEvent
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.db.VariableOwnerStore
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.event.CompletedSectionVariableUpdatedEvent
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
import com.terraformation.backend.documentproducer.model.ExistingDocumentModel
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.dummyKeycloakInfo
import com.terraformation.backend.email.model.ObservationNotScheduled
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.report.event.ReportCreatedEvent
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.seedbank.event.AccessionDryingEndEvent
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.event.ObservationNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.ObservationUpcomingNotificationDueEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledSupportNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.event.ScheduleObservationNotificationEvent
import com.terraformation.backend.tracking.event.ScheduleObservationReminderNotificationEvent
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import freemarker.template.Configuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import jakarta.ws.rs.core.MediaType
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.util.Locale
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl

internal class EmailNotificationServiceTest {
  private val acceleratorUser: IndividualUser = mockk()
  private val adminUser: IndividualUser = mockk()
  private val automationStore: AutomationStore = mockk()
  private val clock: InstantSource = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val deliverableStore: DeliverableStore = mockk()
  private val deviceStore: DeviceStore = mockk()
  private val documentStore: DocumentStore = mockk()
  private val facilityStore: FacilityStore = mockk()
  private val organizationStore: OrganizationStore = mockk()
  private val parentStore: ParentStore = mockk()
  private val participantStore: ParticipantStore = mockk()
  private val plantingSiteStore: PlantingSiteStore = mockk()
  private val projectStore: ProjectStore = mockk()
  private val sender: EmailSender = mockk()
  private val speciesStore: SpeciesStore = mockk()
  private val systemUser: SystemUser = SystemUser(mockk())
  private val user: IndividualUser = mockk()
  private val userInternalInterestsStore: UserInternalInterestsStore = mockk()
  private val userStore: UserStore = mockk()
  private val variableOwnerStore: VariableOwnerStore = mockk()
  private val variableStore: VariableStore = mockk()

  private val webAppUrls = WebAppUrls(config, dummyKeycloakInfo())

  private val freeMarkerConfig =
      Configuration(Configuration.VERSION_2_3_31).apply {
        // Load the email template files from the templates folder in the build output.
        setClassLoaderForTemplateLoading(
            EmailNotificationServiceTest::class.java.classLoader, "templates")
      }

  private val emailService =
      spyk(EmailService(config, freeMarkerConfig, parentStore, sender, userStore))

  private val service =
      EmailNotificationService(
          automationStore,
          clock,
          config,
          deliverableStore,
          deviceStore,
          documentStore,
          emailService,
          facilityStore,
          organizationStore,
          parentStore,
          participantStore,
          plantingSiteStore,
          projectStore,
          speciesStore,
          systemUser,
          userInternalInterestsStore,
          userStore,
          variableOwnerStore,
          variableStore,
          webAppUrls)

  private val organization =
      OrganizationModel(
          OrganizationId(99), "Test Organization", createdTime = Instant.EPOCH, totalUsers = 1)
  private val facility: FacilityModel =
      FacilityModel(
          connectionState = FacilityConnectionState.Configured,
          createdTime = Instant.EPOCH,
          description = null,
          facilityNumber = 1,
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
  private val applicationId = ApplicationId(1)
  private val plantingSite =
      ExistingPlantingSiteModel(
          boundary = multiPolygon(1),
          description = null,
          id = PlantingSiteId(1),
          organizationId = organization.id,
          name = "My Site",
          plantingZones = emptyList(),
      )
  private val participant =
      ExistingParticipantModel(
          cohortId = null,
          id = ParticipantId(1),
          name = "My Participant",
          projectIds = emptySet(),
      )
  private val project =
      ExistingProjectModel(
          id = ProjectId(1),
          name = "My Project",
          organizationId = organization.id,
          participantId = participant.id,
      )
  private val species =
      ExistingSpeciesModel(
          createdTime = Instant.EPOCH,
          id = SpeciesId(1),
          modifiedTime = Instant.EPOCH,
          organizationId = organization.id,
          scientificName = "A Species",
      )
  private val upcomingObservation =
      ExistingObservationModel(
          endDate = LocalDate.of(2023, 9, 30),
          id = ObservationId(1),
          plantingSiteId = plantingSite.id,
          startDate = LocalDate.of(2023, 9, 1),
          state = ObservationState.Upcoming)

  private val deliverableCategory = DeliverableCategory.Compliance
  private val deliverable =
      DeliverableSubmissionModel(
          category = deliverableCategory,
          deliverableId = DeliverableId(1),
          descriptionHtml = null,
          documents = emptyList(),
          dueDate = null,
          feedback = null,
          internalComment = null,
          modifiedTime = null,
          moduleId = ModuleId(1),
          moduleName = "Module",
          moduleTitle = null,
          name = "Deliverable name",
          organizationId = organization.id,
          organizationName = organization.name,
          participantId = participant.id,
          participantName = participant.name,
          position = 1,
          projectId = project.id,
          projectName = project.name,
          required = false,
          sensitive = false,
          status = SubmissionStatus.Completed,
          submissionId = SubmissionId(1),
          templateUrl = null,
          type = DeliverableType.Questions,
      )

  private val document =
      ExistingDocumentModel(
          createdBy = UserId(1),
          createdTime = Instant.EPOCH,
          documentTemplateId = DocumentTemplateId(1),
          id = DocumentId(1),
          modifiedBy = UserId(1),
          modifiedTime = Instant.EPOCH,
          name = "My Document",
          ownedBy = UserId(1),
          projectId = project.id,
          projectName = project.name,
          status = DocumentStatus.Ready,
          variableManifestId = VariableManifestId(1),
      )

  private val sectionVariable =
      SectionVariable(
          BaseVariableProperties(
              id = VariableId(1),
              name = "Overview",
              manifestId = VariableManifestId(1),
              position = 0,
              stableId = "stable",
          ),
          renderHeading = false)

  private val organizationRecipients = setOf("org1@terraware.io", "org2@terraware.io")

  private val tfContactUserId = UserId(5)
  private val tfContactEmail = "tfcontact@terraformation.com"
  private val tfContactUser = userForEmail(tfContactEmail)

  private val sectionOwnerUserId = UserId(6)
  private val sectionOwnerEmail = "owner@terraformation.com"
  private val sectionOwnerUser = userForEmail(sectionOwnerEmail)

  private val mimeMessageSlot = slot<MimeMessage>()
  private val sentMessages = mutableMapOf<String, MutableList<MimeMessage>>()

  @BeforeEach
  fun setUp() {
    val emailConfig: TerrawareServerConfig.EmailConfig = mockk(relaxed = true)

    every { clock.instant() } returns Instant.EPOCH
    every { config.email } returns emailConfig
    every { config.webAppUrl } returns URI("https://test.terraware.io")
    every { emailConfig.enabled } returns true
    every { emailConfig.senderAddress } returns "testsender@terraware.io"

    every { acceleratorUser.email } returns "accelerator@terraformation.com"
    every { acceleratorUser.fullName } returns "Accelerator Expert"
    every { acceleratorUser.locale } returns Locale.ENGLISH
    every { acceleratorUser.userId } returns UserId(3)
    every { adminUser.canSendAlert(any()) } returns true
    every { adminUser.email } returns "admin@test.com"
    every { adminUser.fullName } returns "Admin Name"
    every { adminUser.userId } returns UserId(1)
    every { automationStore.fetchOneById(automation.id) } returns automation
    every { deliverableStore.fetchDeliverableCategory(any()) } returns deliverableCategory
    every {
      deliverableStore.fetchDeliverableSubmissions(deliverableId = deliverable.deliverableId)
    } returns listOf(deliverable)
    every {
      deliverableStore.fetchDeliverableSubmissions(
          deliverableId = deliverable.deliverableId, projectId = deliverable.projectId)
    } returns listOf(deliverable)
    every { deviceStore.fetchOneById(devicesRow.id!!) } returns devicesRow
    every { documentStore.fetchOneById(document.id) } returns document
    every { facilityStore.fetchOneById(facility.id) } returns facility
    every { organizationStore.fetchOneById(organization.id) } returns organization
    every { parentStore.getFacilityId(accessionId) } returns facility.id
    every { parentStore.getFacilityName(accessionId) } returns facility.name
    every { parentStore.getOrganizationId(accessionId) } returns organization.id
    every { parentStore.getOrganizationId(applicationId) } returns organization.id
    every { parentStore.getOrganizationId(facility.id) } returns organization.id
    every { parentStore.getOrganizationId(upcomingObservation.id) } returns organization.id
    every { parentStore.getOrganizationId(plantingSite.id) } returns organization.id
    every { parentStore.getOrganizationId(project.id) } returns organization.id
    every { participantStore.fetchOneById(participant.id) } returns participant
    every { plantingSiteStore.fetchSiteById(plantingSite.id, PlantingSiteDepth.Site) } returns
        plantingSite
    every { projectStore.fetchOneById(project.id) } returns project
    every { sender.createMimeMessage() } answers { JavaMailSenderImpl().createMimeMessage() }
    every { speciesStore.fetchSpeciesById(species.id) } returns species
    every { user.email } returns "user@test.com"
    every { user.emailNotificationsEnabled } returns true
    every { user.fullName } returns "Normal User"
    every { user.firstName } returns "Normal"
    every { user.locale } returns Locale.ENGLISH
    every { user.userId } returns UserId(2)
    every { userInternalInterestsStore.conditionForUsers(any()) } returns DSL.trueCondition()
    every { userStore.getTerraformationContactUser(any()) } returns null
    every { userStore.fetchByOrganizationId(any(), any(), any()) } returns
        organizationRecipients.map { userForEmail(it) }
    every {
      userStore.fetchByOrganizationId(organization.id, false, setOf(Role.TerraformationContact))
    } returns listOf(tfContactUser)
    every { userStore.fetchOneById(adminUser.userId) } returns adminUser
    every { userStore.fetchOneById(user.userId) } returns user
    every { userStore.fetchOneById(tfContactUserId) } returns tfContactUser
    every { userStore.fetchOneById(sectionOwnerUserId) } returns sectionOwnerUser
    every { userStore.fetchWithGlobalRoles(setOf(GlobalRole.TFExpert), any()) } returns
        listOf(acceleratorUser)
    every { variableStore.fetchOneVariable(sectionVariable.id, sectionVariable.manifestId) } returns
        sectionVariable

    every { sender.send(capture(mimeMessageSlot)) } answers
        { answer ->
          val message = answer.invocation.args[0] as? MimeMessage ?: fail("No message found")
          // The MimeMessage object is reused and mutated, so need to make a copy of it.
          val messageCopy = MimeMessage(message)
          val recipientsString = message.getRecipientsString(Message.RecipientType.TO)
          recipientsString.forEach {
            sentMessages.getOrPut(it) { mutableListOf() }.add(messageCopy)
          }

          "message id"
        }
    every { userStore.fetchByEmail(any()) } answers
        { answer ->
          userForEmail(answer.invocation.args[0] as String)
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
    assertEquals(organizationRecipients, sentMessages.keys, "Recipients")
    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun userAddedToOrganization() {
    service.on(UserAddedToOrganizationEvent(user.userId, organization.id, adminUser.userId))

    assertBodyContains(organization.name, "Organization name")
    assertBodyContains(adminUser.fullName!!, "Admin name")
    assertBodyContains(webAppUrls.fullOrganizationHome(organization.id), "Link URL")
    assertSubjectContains("You've")
    assertRecipientsEqual(setOf(user.email))
  }

  @Test
  fun userAddedToTerraware() {
    service.on(UserAddedToTerrawareEvent(user.userId, organization.id, adminUser.userId))

    assertBodyContains(organization.name, "Organization name")
    assertBodyContains(adminUser.fullName!!, "Admin name")
    assertBodyContains(
        webAppUrls.terrawareRegistrationUrl(organization.id, user.email), "Registration URL")
    assertSubjectContains("You've")
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
  fun reportCreated() {
    val admins = listOf("admin1@x.com", "admin2@x.com", "gibberish@x.com")
    every {
      userStore.fetchByOrganizationId(organization.id, true, setOf(Role.Owner, Role.Admin))
    } returns admins.map { userForEmail(it) }

    service.on(
        ReportCreatedEvent(
            ReportMetadata(
                ReportId(1),
                organizationId = organization.id,
                quarter = 3,
                status = ReportStatus.New,
                year = 2023)))

    val englishMessage = sentMessageFor("admin1@x.com")
    val gibberishMessage = sentMessageFor("gibberish@x.com")

    assertBodyContains("2023-Q3", "Year and quarter", message = englishMessage)
    assertBodyContains("Report", "English text", message = englishMessage)
    assertBodyContains("Report".toGibberish(), "Gibberish text", message = gibberishMessage)
    assertRecipientsEqual(admins.toSet())
  }

  @Test
  fun observationStarted() {
    val event =
        ObservationStartedEvent(
            ExistingObservationModel(
                endDate = LocalDate.of(2023, 9, 30),
                id = ObservationId(1),
                plantingSiteId = plantingSite.id,
                startDate = LocalDate.of(2023, 9, 1),
                state = ObservationState.InProgress))

    service.on(event)

    assertBodyContains("Observation", "Text")
    assertBodyContains(webAppUrls.fullObservations(organization.id, plantingSite.id), "Link URL")
    assertIsEventListener<ObservationStartedEvent>(service)
  }

  @Test
  fun observationUpcomingNotificationDue() {
    val recipients = setOf("english@x.com", "gibberish@x.com")
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        recipients.map { userForEmail(it) }

    val event = ObservationUpcomingNotificationDueEvent(upcomingObservation)

    service.on(event)

    val englishMessage = sentMessageFor("english@x.com")
    val gibberishMessage = sentMessageFor("gibberish@x.com")

    assertBodyContains("Observation", message = englishMessage)
    assertBodyContains("September 1, 2023", "Start date", message = englishMessage)
    assertBodyContains(
        webAppUrls.googlePlay.toString(), "Google Play URL", message = englishMessage)

    assertBodyContains(
        "Observation".toGibberish(), "Localized text (gibberish)", message = gibberishMessage)
    assertBodyContains("2023 Sep 1", "Start date (gibberish)", message = gibberishMessage)
    assertBodyContains(
        webAppUrls.appStore.toString(), "App Store URL (gibberish)", message = gibberishMessage)

    assertRecipientsEqual(recipients)
  }

  @Test
  fun observationScheduledNotification() {
    val event = ObservationScheduledEvent(upcomingObservation)

    service.on(event)

    assertSubjectContains("Test Organization")
    assertSubjectContains("My Site")
    assertSubjectContains("has scheduled an observation")
    assertBodyContains("observation scheduled")
    assertBodyContains("September 1, 2023", "Start date")
    assertBodyContains("September 30, 2023", "End date")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun observationRescheduledNotification() {
    val event =
        ObservationRescheduledEvent(
            upcomingObservation,
            ExistingObservationModel(
                endDate = LocalDate.of(2023, 10, 31),
                id = ObservationId(1),
                plantingSiteId = plantingSite.id,
                startDate = LocalDate.of(2023, 10, 1),
                state = ObservationState.Upcoming))

    service.on(event)

    assertSubjectContains("Test Organization")
    assertSubjectContains("My Site")
    assertSubjectContains("has rescheduled an observation")
    assertBodyContains("observation rescheduled")
    assertBodyContains("September 1, 2023", "Original start date")
    assertBodyContains("September 30, 2023", "Original end date")
    assertBodyContains("October 1, 2023", "New start date")
    assertBodyContains("October 31, 2023", "New end date")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun scheduleObservationNotification() {
    val recipients = setOf("english@x.com", "gibberish@x.com")
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        recipients.map { userForEmail(it) }

    val event = ScheduleObservationNotificationEvent(PlantingSiteId(1))

    service.on(event)

    val englishMessage = sentMessageFor("english@x.com")
    val gibberishMessage = sentMessageFor("gibberish@x.com")

    assertBodyContains("Schedule an observation", message = englishMessage)

    assertBodyContains(
        "Schedule an observation".toGibberish(),
        "Localized text (gibberish)",
        message = gibberishMessage)

    assertRecipientsEqual(recipients)
  }

  @Test
  fun scheduleObservationReminderNotification() {
    val recipients = setOf("english@x.com", "gibberish@x.com")
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        recipients.map { userForEmail(it) }

    val event = ScheduleObservationReminderNotificationEvent(PlantingSiteId(1))

    service.on(event)

    val englishMessage = sentMessageFor("english@x.com")
    val gibberishMessage = sentMessageFor("gibberish@x.com")

    assertBodyContains("Reminder: Schedule an observation", message = englishMessage)

    assertBodyContains(
        "Reminder: Schedule an observation".toGibberish(),
        "Localized text (gibberish)",
        message = gibberishMessage)

    assertRecipientsEqual(recipients)
  }

  @Test
  fun observationNotScheduledNotificationToTerraformationContact() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event = ObservationNotScheduledNotificationEvent(PlantingSiteId(1))

    service.on(event)

    assertSubjectContains("Test Organization")
    assertSubjectContains("My Site")
    assertSubjectContains("has not scheduled an observation")
    assertBodyContains("but the organization has not scheduled one")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun observationNotScheduledNotificationToTerraformationSupport() {
    every { config.support.email } returns "support@terraformation.com"

    val event = ObservationNotScheduledNotificationEvent(PlantingSiteId(1))

    service.on(event)

    assertSentNoContactNotification()

    val message = sentMessageWithSubject("has not scheduled an observation")

    assertSubjectContains("Test Organization", message)
    assertSubjectContains("My Site", message)
    assertBodyContains("but the organization has not scheduled one", message = message)

    assertRecipientsEqual(setOf("support@terraformation.com"))
  }

  @Test
  fun noObservationNotScheduledNotificationWhenSupportNotConfigured() {
    every { config.support.email } returns null

    val event = ObservationNotScheduledNotificationEvent(PlantingSiteId(1))

    service.on(event)

    assert(sentMessages.isEmpty())
  }

  @Test
  fun `observationMonitoringPlotReplaced with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event =
        ObservationPlotReplacedEvent(
            ReplacementDuration.LongTerm, "Just because", upcomingObservation, MonitoringPlotId(1))

    service.on(event)

    assertSubjectContains("Test Organization")
    assertSubjectContains("has requested an observation plot change")
    assertBodyNotContains("no assigned Terraformation primary project")
    assertBodyContains("justification given is: Just because")
    assertBodyContains("duration for the change is: Long-Term/Permanent")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun `observationMonitoringPlotReplaced without Terraformation contact`() {
    every { config.support.email } returns "support@terraformation.com"

    val event =
        ObservationPlotReplacedEvent(
            ReplacementDuration.LongTerm, "Just because", upcomingObservation, MonitoringPlotId(1))

    service.on(event)

    assertSentNoContactNotification()

    val message = sentMessageWithSubject("has requested an observation plot change")
    assertSubjectContains("Test Organization", message)
    assertBodyContains("justification given is: Just because", message = message)
    assertBodyContains("duration for the change is: Long-Term/Permanent", message = message)

    assertRecipientsEqual(setOf("support@terraformation.com"))
  }

  @Test
  fun `plantingSeasonScheduled with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event =
        PlantingSeasonScheduledEvent(
            plantingSite.id,
            PlantingSeasonId(1),
            LocalDate.of(2023, 1, 1),
            LocalDate.of(2023, 3, 3))

    service.on(event)

    assertSubjectContains("Test Organization")
    assertSubjectContains("My Site")
    assertSubjectContains("scheduled")
    assertBodyContains("My Site")
    assertBodyContains("2023-01-01 through 2023-03-03")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun `plantingSeasonScheduled without Terraformation contact`() {
    every { config.support.email } returns "support@terraformation.com"

    val event =
        PlantingSeasonScheduledEvent(
            plantingSite.id,
            PlantingSeasonId(1),
            LocalDate.of(2023, 1, 1),
            LocalDate.of(2023, 3, 3))

    service.on(event)

    assertEquals(emptyMap<Any, Any>(), sentMessages, "Should not have sent any messages")
  }

  @Test
  fun `plantingSeasonRescheduled with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event =
        PlantingSeasonRescheduledEvent(
            plantingSite.id,
            PlantingSeasonId(1),
            LocalDate.of(2023, 1, 1),
            LocalDate.of(2023, 3, 3),
            LocalDate.of(2023, 1, 2),
            LocalDate.of(2023, 3, 4))

    service.on(event)

    assertSubjectContains("Test Organization")
    assertSubjectContains("My Site")
    assertSubjectContains("rescheduled")
    assertBodyContains("My Site")
    assertBodyContains("was scheduled for 2023-01-01 through 2023-03-03")
    assertBodyContains("is scheduled for 2023-01-02 through 2023-03-04")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun `plantingSeasonRescheduled without Terraformation contact`() {
    every { config.support.email } returns "support@terraformation.com"

    val event =
        PlantingSeasonRescheduledEvent(
            plantingSite.id,
            PlantingSeasonId(1),
            LocalDate.of(2023, 1, 1),
            LocalDate.of(2023, 3, 3),
            LocalDate.of(2023, 1, 2),
            LocalDate.of(2023, 3, 4))

    service.on(event)

    assertEquals(emptyMap<Any, Any>(), sentMessages, "Should not have sent any messages")
  }

  @Test
  fun plantingSeasonStarted() {
    val event = PlantingSeasonStartedEvent(plantingSite.id, PlantingSeasonId(1))

    service.on(event)

    assertSubjectContains("planting")
    assertBodyContains("My Site", "Text")
    assertBodyContains("Planting season", "Text")
    assertBodyContains(webAppUrls.fullNurseryInventory(organization.id), "Link URL")
    assertIsEventListener<PlantingSeasonStartedEvent>(service)
  }

  @Test
  fun `plantingSeasonNotScheduled first email`() {
    val event = PlantingSeasonNotScheduledNotificationEvent(plantingSite.id, 1)
    service.on(event)

    assertSubjectContains("planting")
    assertBodyContains("My Site", "Text")
    assertBodyContains("planting season", "Text")
    assertBodyContains("schedule", "Text")
    assertBodyContains(webAppUrls.fullPlantingSite(organization.id, plantingSite.id), "Link URL")
    assertIsEventListener<PlantingSeasonNotScheduledNotificationEvent>(service)
  }

  @Test
  fun `plantingSeasonNotScheduled second email`() {
    val event = PlantingSeasonNotScheduledNotificationEvent(plantingSite.id, 2)
    service.on(event)

    assertSubjectContains("Reminder")
    assertSubjectContains("planting")
    assertBodyContains("My Site", "Text")
    assertBodyContains("planting season", "Text")
    assertBodyContains("schedule", "Text")
    assertBodyContains(webAppUrls.fullPlantingSite(organization.id, plantingSite.id), "Link URL")
    assertIsEventListener<PlantingSeasonNotScheduledNotificationEvent>(service)
  }

  @Test
  fun plantingSeasonNotScheduledSupport() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event = PlantingSeasonNotScheduledSupportNotificationEvent(plantingSite.id, 1)

    service.on(event)

    assertSubjectContains("Test Organization")
    assertSubjectContains("My Site")
    assertSubjectContains("not scheduled")
    assertBodyContains("My Site")
    assertBodyContains("missing a planting season")
    assertRecipientsEqual(setOf(tfContactEmail))
    assertIsEventListener<PlantingSeasonNotScheduledSupportNotificationEvent>(service)
  }

  @Test
  fun `participantProjectAdded with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event = ParticipantProjectAddedEvent(user.userId, participant.id, project.id)

    service.on(event)

    assertSubjectContains("My Participant")
    assertSubjectContains("added to")
    assertBodyContains(organization.name)
    assertBodyContains(participant.name)
    assertBodyContains(project.name)
    assertBodyContains("added to")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun `participantProjectAdded without Terraformation contact`() {
    every { config.support.email } returns "support@terraformation.com"

    val event = ParticipantProjectAddedEvent(user.userId, participant.id, project.id)

    service.on(event)

    assertSentNoContactNotification()

    val message = sentMessageWithSubject("added to")
    assertSubjectContains("My Participant", message = message)
    assertBodyContains(organization.name, message = message)
    assertBodyContains(participant.name, message = message)
    assertBodyContains(project.name, message = message)
    assertBodyContains("added to", message = message)

    assertRecipientsEqual(setOf("support@terraformation.com"))
  }

  @Test
  fun `participantProjectRemoved with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event = ParticipantProjectRemovedEvent(participant.id, project.id, user.userId)

    service.on(event)

    assertSubjectContains("My Participant")
    assertSubjectContains("removed from")
    assertBodyContains(organization.name)
    assertBodyContains(participant.name)
    assertBodyContains(project.name)
    assertBodyContains("removed from")

    assertRecipientsEqual(setOf(tfContactEmail))
  }

  @Test
  fun `participantProjectRemoved without Terraformation contact`() {
    every { config.support.email } returns "support@terraformation.com"

    val event = ParticipantProjectRemovedEvent(participant.id, project.id, user.userId)

    service.on(event)

    assertSentNoContactNotification()

    val message = sentMessageWithSubject("removed from")
    assertSubjectContains("My Participant", message = message)
    assertBodyContains(organization.name, message = message)
    assertBodyContains(participant.name, message = message)
    assertBodyContains(project.name, message = message)
    assertBodyContains("removed from", message = message)

    assertRecipientsEqual(setOf("support@terraformation.com"))
  }

  @Test
  fun `participantProjectSpeciesAddedToProject without Terraformation contact`() {
    every { config.support.email } returns "support@terraformation.com"

    val event =
        ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
            DeliverableId(1), project.id, species.id)

    service.on(event)

    val message = sentMessageWithSubject("added to")
    assertSubjectContains(participant.name, message = message)
    assertBodyContains(participant.name, message = message)
    assertBodyContains(project.name, message = message)
    assertBodyContains(species.scientificName, message = message)
    assertBodyContains("submitted for use", message = message)

    assertRecipientsEqual(setOf(acceleratorUser.email))
  }

  @Test
  fun `participantProjectSpeciesAddedToProject with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event =
        ParticipantProjectSpeciesAddedToProjectNotificationDueEvent(
            DeliverableId(1), project.id, species.id)

    service.on(event)

    val message = sentMessageWithSubject("added to")
    assertSubjectContains(participant.name, message = message)
    assertBodyContains(participant.name, message = message)
    assertBodyContains(project.name, message = message)
    assertBodyContains(species.scientificName, message = message)
    assertBodyContains("submitted for use", message = message)

    assertRecipientsEqual(setOf(tfContactEmail, acceleratorUser.email))
  }

  @Test
  fun `participantProjectSpeciesEditedToProject without Terraformation contact`() {
    every { config.support.email } returns "support@terraformation.com"

    val event =
        ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
            DeliverableId(1), project.id, species.id)

    service.on(event)

    val message = sentMessageWithSubject("has been edited")
    assertSubjectContains(participant.name, message = message)
    assertBodyContains(participant.name, message = message)
    assertBodyContains(species.scientificName, message = message)
    assertBodyContains("has been edited", message = message)

    assertRecipientsEqual(setOf(acceleratorUser.email))
  }

  @Test
  fun `participantProjectSpeciesEditedToProject with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event =
        ParticipantProjectSpeciesApprovedSpeciesEditedNotificationDueEvent(
            DeliverableId(1), project.id, species.id)

    service.on(event)

    val message = sentMessageWithSubject("has been edited")
    assertSubjectContains(participant.name, message = message)
    assertBodyContains(participant.name, message = message)
    assertBodyContains(species.scientificName, message = message)
    assertBodyContains("has been edited", message = message)

    assertRecipientsEqual(setOf(tfContactEmail, acceleratorUser.email))
  }

  @Test
  fun `applicationSubmittedEvent should notify global role users and TFContact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser
    every { userStore.fetchWithGlobalRoles() } returns listOf(acceleratorUser, tfContactUser)
    val event = ApplicationSubmittedEvent(applicationId)
    service.on(event)
    val message = sentMessageWithSubject("Application submitted for")
    assertSubjectContains(organization.name, message = message)
    assertBodyContains(organization.name, message = message)
    assertRecipientsEqual(setOf(tfContactEmail, acceleratorUser.email))
  }

  @Test
  fun `deliverableReadyForReview with Terraformation contact`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser

    val event = DeliverableReadyForReviewEvent(deliverable.deliverableId, project.id)

    service.on(event)

    val message = sentMessageWithSubject("is ready for review")
    assertSubjectContains(participant.name, message = message)
    assertBodyContains(participant.name, message = message)

    assertRecipientsEqual(setOf(tfContactEmail, acceleratorUser.email))
  }

  @Test
  fun `deliverableReadyForReview does not over-notify Terraformation contact that has a global role`() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser
    every { userStore.fetchWithGlobalRoles() } returns listOf(acceleratorUser, tfContactUser)

    val event = DeliverableReadyForReviewEvent(deliverable.deliverableId, project.id)

    service.on(event)

    val message = sentMessageWithSubject("is ready for review")
    assertSubjectContains(participant.name, message = message)
    assertBodyContains(participant.name, message = message)

    assertRecipientsEqual(setOf(tfContactEmail, acceleratorUser.email))

    // check that we haven't over-notified the TF contact
    verify(exactly = 1) { emailService.sendUserNotification(tfContactUser, any(), any()) }
  }

  @Test
  fun `deliverableReadyForReview without Terraformation contact`() {
    val event = DeliverableReadyForReviewEvent(deliverable.deliverableId, project.id)

    service.on(event)

    val message = sentMessageWithSubject("is ready for review")
    assertSubjectContains(participant.name, message = message)
    assertBodyContains(participant.name, message = message)

    assertRecipientsEqual(setOf(acceleratorUser.email))
  }

  @Test
  fun deliverableStatusUpdated() {
    val event =
        DeliverableStatusUpdatedEvent(
            DeliverableId(1),
            project.id,
            SubmissionStatus.NotSubmitted,
            SubmissionStatus.NotNeeded,
            SubmissionId(1))

    service.on(event)

    assertSubjectContains("Deliverable's status was updated")
    assertBodyContains("A submitted deliverable was reviewed and its status was updated")

    assertRecipientsEqual(organizationRecipients)
  }

  @Test
  fun `deliverableStatusUpdated should not notify about internal-only statuses`() {
    val event =
        DeliverableStatusUpdatedEvent(
            DeliverableId(1),
            project.id,
            SubmissionStatus.InReview,
            SubmissionStatus.NeedsTranslation,
            SubmissionId(1))

    service.on(event)

    assertRecipientsEqual(emptySet())
  }

  @Test
  fun `completedSectionVariableUpdated should notify section owner`() {
    every { variableOwnerStore.fetchOwner(any(), any()) } returns sectionOwnerUserId

    val event =
        CompletedSectionVariableUpdatedEvent(
            document.id, project.id, VariableId(-1), sectionVariable.id)

    service.on(event)

    assertSubjectContains("Variable edited")
    assertBodyContains("A variable has been")

    assertRecipientsEqual(setOf(sectionOwnerEmail))

    assertIsEventListener<CompletedSectionVariableUpdatedEvent>(service)
  }

  @Test
  fun `completedSectionVariableUpdated should not notify if section has no owner`() {
    every { variableOwnerStore.fetchOwner(any(), any()) } returns null

    val event =
        CompletedSectionVariableUpdatedEvent(
            DocumentId(1), project.id, VariableId(2), VariableId(3))

    service.on(event)

    assertRecipientsEqual(emptySet())
  }

  @Test
  fun plantingSiteMapEdited() {
    every { userStore.getTerraformationContactUser(any()) } returns tfContactUser
    every { userStore.fetchWithGlobalRoles() } returns listOf(acceleratorUser, tfContactUser)

    val siteName = "Test Site"
    val existingModel =
        PlantingSiteBuilder.existingSite {
          name = siteName
          organizationId = organization.id
        }

    val event =
        PlantingSiteMapEditedEvent(
            existingModel,
            PlantingSiteEdit(
                areaHaDifference = BigDecimal("-13.2"),
                desiredModel = PlantingSiteBuilder.newSite { name = siteName },
                existingModel = existingModel,
                plantingZoneEdits = emptyList()),
            ReplacementResult(emptySet(), emptySet()))

    service.on(event)

    assertSubjectContains(organization.name)
    assertSubjectContains(siteName)
    assertBodyContains("13.2 hectares have been removed from the")

    assertRecipientsEqual(setOf(tfContactEmail))

    assertIsEventListener<PlantingSiteMapEditedEvent>(service)
  }

  @Test
  fun `accession daily task emails accumulate until processing is finished`() {
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        listOf(userForEmail("1@test.com"))
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        listOf(userForEmail("2@test.com"))
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))

    verify(exactly = 0) { sender.send(any()) }

    service.on(NotificationJobSucceededEvent())

    assertEquals(setOf("1@test.com", "2@test.com"), sentMessages.keys, "Recipients")
  }

  @Test
  fun `accession daily task emails are discarded if processing fails`() {
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        listOf(userForEmail("1@test.com"))
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    service.on(NotificationJobFinishedEvent())

    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        listOf(userForEmail("2@test.com"))
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    service.on(NotificationJobSucceededEvent())

    assertEquals(setOf("2@test.com"), sentMessages.keys, "Recipients")
  }

  @Test
  fun `accession messages are rendered using recipient locale`() {
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns
        listOf(
            userForEmail("english@test.com"),
            userForEmail("gibberish@test.com"),
        )
    service.on(AccessionDryingEndEvent(accessionNumber, accessionId))
    service.on(NotificationJobSucceededEvent())

    val englishMessage = sentMessageFor("english@test.com")
    val gibberishMessage = sentMessageFor("gibberish@test.com")

    assertSubjectContains("accession", englishMessage)
    assertSubjectContains("accession".toGibberish(), gibberishMessage)
    assertBodyContains("accession", "English", message = englishMessage)
    assertBodyContains("accession".toGibberish(), "Gibberish", message = gibberishMessage)
  }

  @Test
  fun `org notification by default fetches recipients for all roles except Terraformation Contact`() {
    val rolesWithoutTerraformationContact =
        Role.entries.filter { it != Role.TerraformationContact }.toSet()
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns emptyList()

    emailService.sendOrganizationNotification(
        organization.id, ObservationNotScheduled(config, "", ""), true)

    verify(exactly = 1) {
      userStore.fetchByOrganizationId(organization.id, true, rolesWithoutTerraformationContact)
    }
  }

  @Test
  fun `org notification fetches recipients for the input roles`() {
    val roles = setOf(Role.Admin, Role.TerraformationContact)
    every { userStore.fetchByOrganizationId(organization.id, any(), any()) } returns emptyList()

    emailService.sendOrganizationNotification(
        organization.id, ObservationNotScheduled(config, "", ""), true, roles)

    verify(exactly = 1) { userStore.fetchByOrganizationId(organization.id, true, roles) }
  }

  private fun assertRecipientsEqual(expected: Set<String>) {
    assertEquals(expected, sentMessages.keys, "Recipients")
  }

  private fun assertSubjectContains(
      @Suppress("SameParameterValue") text: String,
      message: MimeMessage = mimeMessageSlot.captured
  ) {
    assertContains(text, message.subject, "Subject")
  }

  private fun assertBodyContains(
      substring: Any,
      messagePrefix: String = "Localized text",
      hasTextPlain: Boolean = true,
      hasTextHtml: Boolean = true,
      message: MimeMessage = mimeMessageSlot.captured,
  ) {
    val substringText = "$substring"
    var foundTextPlain = false
    var foundTextHtml = false

    textParts(message).forEach { part ->
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

    assertEquals(hasTextPlain, foundTextPlain, "$messagePrefix: Has text/plain part")
    assertEquals(hasTextHtml, foundTextHtml, "$messagePrefix: Has text/html part")
  }

  private fun assertBodyNotContains(
      substring: Any,
      messagePrefix: String = "Localized text",
      hasTextPlain: Boolean = true,
      hasTextHtml: Boolean = true,
      message: MimeMessage = mimeMessageSlot.captured,
  ) {
    val substringText = "$substring"
    var foundTextPlain = false
    var foundTextHtml = false

    textParts(message).forEach { part ->
      if (part.dataHandler.contentType.startsWith(MediaType.TEXT_HTML, ignoreCase = true)) {
        foundTextHtml = true
        assertNotContains(substringText, part.content.toString(), "$messagePrefix: text/html")
      } else if (part.dataHandler.contentType.startsWith(MediaType.TEXT_PLAIN, ignoreCase = true)) {
        foundTextPlain = true
        assertNotContains(substringText, part.content.toString(), "$messagePrefix: text/plain")
      } else {
        fail("$messagePrefix: Unexpected content type: ${part.dataHandler.contentType}")
      }
    }

    assertEquals(hasTextPlain, foundTextPlain, "$messagePrefix: Has text/plain part")
    assertEquals(hasTextHtml, foundTextHtml, "$messagePrefix: Has text/html part")
  }

  private fun assertContains(needle: String, haystack: String, message: String) {
    if (!haystack.contains(needle)) {
      // This isn't actually checking for equality, just logging the expected and actual text in a
      // tool-friendly form.
      assertEquals(needle, haystack, message)
    }
  }

  private fun assertNotContains(needle: String, haystack: String, message: String) {
    if (haystack.contains(needle)) {
      // This isn't actually checking for equality, just logging the expected and actual text in a
      // tool-friendly form.
      assertNotEquals(needle, haystack, message)
    }
  }

  private fun assertSentNoContactNotification() {
    val message = sentMessageWithSubject("has no primary project contact")

    assertSubjectContains("Test Organization", message)
    assertBodyContains("Test Organization (${organization.id})", message = message)
    assertBodyContains("no assigned Terraformation primary project", message = message)
  }

  /** Walks a MIME message of arbitrary structure and returns the text parts. */
  private fun textParts(part: Part): Sequence<Part> = sequence {
    val content = part.content
    if (content is Multipart) {
      yieldAll(
          (0..<content.count)
              .asSequence()
              .map { index -> content.getBodyPart(index) }
              .flatMap { textParts(it) })
    } else if (part.dataHandler.contentType.startsWith("text/", ignoreCase = true)) {
      yield(part)
    }
  }

  private fun userForEmail(email: String): IndividualUser {
    val mock: IndividualUser = mockk()

    every { mock.email } returns email
    every { mock.emailNotificationsEnabled } returns true
    if (email.startsWith("gibberish")) {
      every { mock.locale } returns Locales.GIBBERISH
    } else {
      every { mock.locale } returns Locale.ENGLISH
    }

    return mock
  }

  private fun sentMessageFor(recipient: String) =
      sentMessages[recipient]?.single() ?: fail("No messages found for $recipient")

  private fun sentMessageWithSubject(needle: String) =
      sentMessages.values.flatten().firstOrNull { it.subject.contains(needle) }
          ?: fail("No message subject contained substring: $needle")
}
