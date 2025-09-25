package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.event.ApplicationSubmittedEvent
import com.terraformation.backend.accelerator.model.ApplicationModuleModel
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationHistoriesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationsRow
import com.terraformation.backend.db.accelerator.tables.records.ApplicationHistoriesRecord
import com.terraformation.backend.db.accelerator.tables.records.ApplicationModulesRecord
import com.terraformation.backend.db.accelerator.tables.records.ApplicationsRecord
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_HISTORIES
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.calculateAreaHectares
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.springframework.security.access.AccessDeniedException

class ApplicationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val messages = Messages()
  private val store: ApplicationStore by lazy {
    ApplicationStore(
        clock,
        countriesDao,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        messages,
    )
  }

  private val organizationName = UUID.randomUUID().toString()
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization(countryCode = "US", name = organizationName)
    insertProject(name = "Project A")

    every { user.adminOrganizations() } returns setOf(organizationId)
    every { user.canCreateApplication(any()) } returns true
    every { user.canReadApplication(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReviewApplication(any()) } returns true
    every { user.canUpdateApplicationBoundary(any()) } returns true
    every { user.canUpdateApplicationCountry(any()) } returns true
    every { user.canUpdateApplicationSubmissionStatus(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `populates initial values and creates history entry`() {
      val prescreenModuleId = insertModule(phase = CohortPhase.PreScreen)
      val applicationModuleId = insertModule(phase = CohortPhase.Application)
      insertModule(name = "Not assigned", phase = CohortPhase.Phase1FeasibilityStudy)
      val now = Instant.ofEpochSecond(30)
      clock.instant = now

      val model = store.create(inserted.projectId)

      assertTableEquals(
          ApplicationsRecord(
              applicationStatusId = ApplicationStatus.NotSubmitted,
              createdBy = user.userId,
              createdTime = now,
              id = model.id,
              internalName = "XXX_$organizationName",
              modifiedBy = user.userId,
              modifiedTime = now,
              projectId = inserted.projectId,
          )
      )

      assertTableEquals(
          ApplicationHistoriesRecord(
              applicationId = model.id,
              applicationStatusId = ApplicationStatus.NotSubmitted,
              modifiedBy = user.userId,
              modifiedTime = now,
          )
      )

      assertTableEquals(
          listOf(
              ApplicationModulesRecord(
                  applicationId = model.id,
                  moduleId = prescreenModuleId,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete,
              ),
              ApplicationModulesRecord(
                  applicationId = model.id,
                  moduleId = applicationModuleId,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete,
              ),
          )
      )
    }

    @Test
    fun `throws exception if project already has an application`() {
      insertApplication()

      assertThrows<ProjectApplicationExistsException> { store.create(inserted.projectId) }
    }

    @Test
    fun `throws exception if no permission to create application`() {
      every { user.canCreateApplication(any()) } returns false

      assertThrows<AccessDeniedException> { store.create(inserted.projectId) }
    }
  }

  @Nested
  inner class Fetch {
    private lateinit var organizationId2: OrganizationId

    private lateinit var org1ProjectId1: ProjectId
    private lateinit var org1ProjectId2: ProjectId
    private lateinit var org2ProjectId1: ProjectId

    private lateinit var org1Project1ApplicationId: ApplicationId
    private lateinit var org1Project2ApplicationId: ApplicationId
    private lateinit var org2Project1ApplicationId: ApplicationId

    @BeforeEach
    fun setUp() {
      org1ProjectId1 = inserted.projectId
      org1Project1ApplicationId =
          insertApplication(
              projectId = org1ProjectId1,
              boundary = rectangle(1),
              countryCode = "FR",
              feedback = "feedback",
              internalComment = "internal comment",
              internalName = "Internal Name 1",
              status = ApplicationStatus.ExpertReview,
          )

      org1ProjectId2 = insertProject(organizationId = organizationId, name = "Project B")
      org1Project2ApplicationId =
          insertApplication(
              projectId = org1ProjectId2,
              boundary = rectangle(2),
              feedback = "feedback 2",
              internalComment = "internal comment 2",
              internalName = "Internal Name 2",
              status = ApplicationStatus.SourcingTeamReview,
          )

      organizationId2 = insertOrganization(name = "Organization 2")
      org2ProjectId1 = insertProject(organizationId = organizationId2, name = "Project C")
      org2Project1ApplicationId =
          insertApplication(
              projectId = org2ProjectId1,
              countryCode = "US",
              internalName = "Internal Name 3",
          )

      every { user.adminOrganizations() } returns setOf(organizationId, organizationId2)
    }

    @Nested
    inner class FetchOneById {
      @Test
      fun `fetches application data with latest modified time`() {
        insertApplicationHistory(
            org1Project1ApplicationId,
            modifiedTime = clock.instant.plusSeconds(600),
        )
        insertApplicationHistory(
            org1Project1ApplicationId,
            modifiedTime = clock.instant.plusSeconds(120),
        )

        assertEquals(
            ExistingApplicationModel(
                boundary = rectangle(1),
                countryCode = "FR",
                createdTime = Instant.EPOCH,
                feedback = "feedback",
                id = org1Project1ApplicationId,
                internalComment = "internal comment",
                internalName = "Internal Name 1",
                modifiedTime = clock.instant.plusSeconds(600),
                organizationId = organizationId,
                organizationName = organizationName,
                projectId = org1ProjectId1,
                projectName = "Project A",
                status = ApplicationStatus.ExpertReview,
            ),
            store.fetchOneById(org1Project1ApplicationId),
        )
      }

      @Test
      fun `throws exception if application does not exist`() {
        assertThrows<ApplicationNotFoundException> { store.fetchOneById(ApplicationId(1)) }
      }

      @Test
      fun `throws exception if no permission to read application`() {
        every { user.canReadApplication(org1Project1ApplicationId) } returns false

        assertThrows<ApplicationNotFoundException> { store.fetchOneById(org1Project1ApplicationId) }
      }
    }

    @Nested
    inner class FetchGeoFeatureByProjectId {
      @Test
      fun `fetches application data to a simple feature`() {
        val simpleFeature = store.fetchGeoFeatureById(org1Project1ApplicationId)

        assertEquals(rectangle(1), simpleFeature.defaultGeometry as Geometry, "geometry attribute")
        assertEquals(
            org1Project1ApplicationId.value,
            simpleFeature.getAttribute("applicationId"),
            "applicationId attribute",
        )
        assertEquals("FR", simpleFeature.getAttribute("countryCode"), "countryCode attribute")
        assertEquals(
            "Internal Name 1",
            simpleFeature.getAttribute("internalName"),
            "internalName attribute",
        )
        assertEquals(
            organizationId.value,
            simpleFeature.getAttribute("organizationId"),
            "organizationId attribute",
        )
        assertEquals(
            organizationName,
            simpleFeature.getAttribute("organizationName"),
            "organizationName attribute",
        )
        assertEquals(
            org1ProjectId1.value,
            simpleFeature.getAttribute("projectId"),
            "projectId attribute",
        )
        assertEquals(
            "Project A",
            simpleFeature.getAttribute("projectName"),
            "projectName attribute",
        )
        assertEquals(
            ApplicationStatus.ExpertReview.jsonValue,
            simpleFeature.getAttribute("status"),
            "status attribute",
        )
      }

      @Test
      fun `throws exception if application does not exist`() {
        assertThrows<ApplicationNotFoundException> { store.fetchGeoFeatureById(ApplicationId(1)) }
      }

      @Test
      fun `throws exception if no permission to review application`() {
        every { user.canReviewApplication(org1Project1ApplicationId) } returns false

        assertThrows<AccessDeniedException> { store.fetchGeoFeatureById(org1Project1ApplicationId) }
      }
    }

    @Nested
    inner class FetchByProjectId {
      @Test
      fun `fetches application for project`() {
        assertEquals(
            listOf(
                ExistingApplicationModel(
                    boundary = rectangle(1),
                    countryCode = "FR",
                    createdTime = Instant.EPOCH,
                    feedback = "feedback",
                    id = org1Project1ApplicationId,
                    internalComment = "internal comment",
                    internalName = "Internal Name 1",
                    modifiedTime = null,
                    organizationId = organizationId,
                    organizationName = organizationName,
                    projectId = org1ProjectId1,
                    projectName = "Project A",
                    status = ApplicationStatus.ExpertReview,
                )
            ),
            store.fetchByProjectId(org1ProjectId1),
        )
      }

      @Test
      fun `returns empty list if user is not an admin in the organization`() {
        every { user.adminOrganizations() } returns setOf(organizationId2)

        assertEquals(emptyList<ExistingApplicationModel>(), store.fetchByProjectId(org1ProjectId1))
      }

      @Test
      fun `returns empty list if project has no application`() {
        val noApplicationProjectId = insertProject()
        assertEquals(
            emptyList<ExistingApplicationModel>(),
            store.fetchByProjectId(noApplicationProjectId),
        )
      }

      @Test
      fun `throws exception if no permission to read project`() {
        every { user.canReadProject(org1ProjectId1) } returns false

        assertThrows<ProjectNotFoundException> { store.fetchByProjectId(org1ProjectId1) }
      }
    }

    @Nested
    inner class FetchByOrganizationId {
      @Test
      fun `fetches applications for organization`() {
        assertEquals(
            listOf(
                ExistingApplicationModel(
                    boundary = rectangle(1),
                    countryCode = "FR",
                    createdTime = Instant.EPOCH,
                    feedback = "feedback",
                    id = org1Project1ApplicationId,
                    internalComment = "internal comment",
                    internalName = "Internal Name 1",
                    modifiedTime = null,
                    organizationId = organizationId,
                    organizationName = organizationName,
                    projectId = org1ProjectId1,
                    projectName = "Project A",
                    status = ApplicationStatus.ExpertReview,
                ),
                ExistingApplicationModel(
                    boundary = rectangle(2),
                    createdTime = Instant.EPOCH,
                    feedback = "feedback 2",
                    id = org1Project2ApplicationId,
                    internalComment = "internal comment 2",
                    internalName = "Internal Name 2",
                    modifiedTime = null,
                    organizationId = organizationId,
                    organizationName = organizationName,
                    projectId = org1ProjectId2,
                    projectName = "Project B",
                    status = ApplicationStatus.SourcingTeamReview,
                ),
            ),
            store.fetchByOrganizationId(organizationId),
        )
      }

      @Test
      fun `throws exception if no permission to read organization`() {
        every { user.canReadOrganization(organizationId) } returns false

        assertThrows<OrganizationNotFoundException> { store.fetchByOrganizationId(organizationId) }
      }
    }

    @Nested
    inner class FetchAll {
      @Test
      fun `fetches applications for all organizations`() {
        every { user.canReadAllAcceleratorDetails() } returns true

        assertEquals(
            listOf(
                ExistingApplicationModel(
                    boundary = rectangle(1),
                    countryCode = "FR",
                    createdTime = Instant.EPOCH,
                    feedback = "feedback",
                    id = org1Project1ApplicationId,
                    internalComment = "internal comment",
                    internalName = "Internal Name 1",
                    modifiedTime = null,
                    organizationId = organizationId,
                    organizationName = organizationName,
                    projectId = org1ProjectId1,
                    projectName = "Project A",
                    status = ApplicationStatus.ExpertReview,
                ),
                ExistingApplicationModel(
                    boundary = rectangle(2),
                    createdTime = Instant.EPOCH,
                    feedback = "feedback 2",
                    id = org1Project2ApplicationId,
                    internalComment = "internal comment 2",
                    internalName = "Internal Name 2",
                    modifiedTime = null,
                    organizationId = organizationId,
                    organizationName = organizationName,
                    projectId = org1ProjectId2,
                    projectName = "Project B",
                    status = ApplicationStatus.SourcingTeamReview,
                ),
                ExistingApplicationModel(
                    countryCode = "US",
                    createdTime = Instant.EPOCH,
                    id = org2Project1ApplicationId,
                    internalName = "Internal Name 3",
                    modifiedTime = null,
                    organizationId = organizationId2,
                    organizationName = "Organization 2",
                    projectId = org2ProjectId1,
                    projectName = "Project C",
                    status = ApplicationStatus.NotSubmitted,
                ),
            ),
            store.fetchAll(),
        )
      }

      @Test
      fun `throws exception if no permission to read all accelerator details`() {
        assertThrows<AccessDeniedException> { store.fetchAll() }
      }
    }

    @Nested
    inner class FetchHistoryByApplicationId {
      @Test
      fun `returns history in reverse chronological order`() {
        val earlierHistoryId =
            insertApplicationHistory(
                applicationId = org1Project1ApplicationId,
                feedback = "feedback 1",
                internalComment = "internal comment 1",
                modifiedTime = Instant.ofEpochSecond(5),
                status = ApplicationStatus.NotSubmitted,
            )
        val laterHistoryId =
            insertApplicationHistory(
                applicationId = org1Project1ApplicationId,
                boundary = rectangle(1),
                feedback = "feedback 2",
                internalComment = "internal comment 2",
                modifiedTime = Instant.ofEpochSecond(10),
                status = ApplicationStatus.PassedPreScreen,
            )

        assertEquals(
            listOf(
                ApplicationHistoriesRecord(
                    applicationId = org1Project1ApplicationId,
                    applicationStatusId = ApplicationStatus.PassedPreScreen,
                    boundary = rectangle(1),
                    feedback = "feedback 2",
                    id = laterHistoryId,
                    internalComment = "internal comment 2",
                    modifiedBy = user.userId,
                    modifiedTime = Instant.ofEpochSecond(10),
                ),
                ApplicationHistoriesRecord(
                    applicationId = org1Project1ApplicationId,
                    applicationStatusId = ApplicationStatus.NotSubmitted,
                    feedback = "feedback 1",
                    id = earlierHistoryId,
                    internalComment = "internal comment 1",
                    modifiedBy = user.userId,
                    modifiedTime = Instant.ofEpochSecond(5),
                ),
            ),
            store.fetchHistoryByApplicationId(org1Project1ApplicationId),
        )
      }

      @Test
      fun `throws exception if no permission to read application`() {
        every { user.canReadApplication(org1Project1ApplicationId) } returns false

        assertThrows<ApplicationNotFoundException> {
          store.fetchHistoryByApplicationId(org1Project1ApplicationId)
        }
      }
    }

    @Nested
    inner class FetchModulesByApplicationId {
      @Test
      fun `returns modules with statuses associated with application`() {
        val prescreenModule =
            insertModule(
                name = "Pre-screen",
                overview = "Pre-screen Overview",
                phase = CohortPhase.PreScreen,
            )
        val applicationModule2 =
            insertModule(
                name = "Application 2",
                overview = "Application 2 Overview",
                phase = CohortPhase.Application,
                position = 2,
            )

        val applicationModule1 =
            insertModule(
                name = "Application 1",
                overview = "Application 1 Overview",
                phase = CohortPhase.Application,
                position = 1,
            )
        insertModule(name = "Hidden module", phase = CohortPhase.Phase1FeasibilityStudy)

        insertApplicationModule(
            inserted.applicationId,
            prescreenModule,
            ApplicationModuleStatus.Complete,
        )
        insertApplicationModule(
            inserted.applicationId,
            applicationModule1,
            ApplicationModuleStatus.Incomplete,
        )
        insertApplicationModule(
            inserted.applicationId,
            applicationModule2,
            ApplicationModuleStatus.Incomplete,
        )

        val expected =
            listOf(
                ApplicationModuleModel(
                    id = prescreenModule,
                    name = "Pre-screen",
                    phase = CohortPhase.PreScreen,
                    overview = "Pre-screen Overview",
                    applicationId = inserted.applicationId,
                    applicationModuleStatus = ApplicationModuleStatus.Complete,
                ),
                ApplicationModuleModel(
                    id = applicationModule1,
                    name = "Application 1",
                    phase = CohortPhase.Application,
                    overview = "Application 1 Overview",
                    applicationId = inserted.applicationId,
                    applicationModuleStatus = ApplicationModuleStatus.Incomplete,
                ),
                ApplicationModuleModel(
                    id = applicationModule2,
                    name = "Application 2",
                    phase = CohortPhase.Application,
                    overview = "Application 2 Overview",
                    applicationId = inserted.applicationId,
                    applicationModuleStatus = ApplicationModuleStatus.Incomplete,
                ),
            )
        val actual = store.fetchModulesByApplicationId(inserted.applicationId).toList()

        assertEquals(expected, actual)
      }

      @Test
      fun `throws exception if no permission to read application`() {
        every { user.canReadApplication(inserted.applicationId) } returns false

        assertThrows<ApplicationNotFoundException> {
          store.fetchModulesByApplicationId(inserted.applicationId)
        }
      }
    }

    @Nested
    inner class FetchApplicationDeliverables {
      private lateinit var prescreenModuleId: ModuleId
      private lateinit var applicationModuleId: ModuleId
      private lateinit var extraApplicationModuleId: ModuleId

      private lateinit var prescreenDeliverableId: DeliverableId
      private lateinit var applicationDeliverableId: DeliverableId
      private lateinit var extraApplicationDeliverableId: DeliverableId

      private lateinit var org1Project1PrescreenSubmission: SubmissionId
      private lateinit var org1Project1ApplicationSubmission: SubmissionId
      private lateinit var org1Project2PrescreenSubmission: SubmissionId
      private lateinit var org2Project1PrescreenSubmission: SubmissionId
      private lateinit var org2Project1ApplicationSubmission: SubmissionId
      private lateinit var org2Project1ExtraApplicationSubmission: SubmissionId

      private val now = Instant.ofEpochSecond(30)

      @BeforeEach
      fun setup() {
        every { user.canReadProjectDeliverables(any()) } returns true
        every { user.canReadOrganizationDeliverables(any()) } returns true
        every { user.canReadModule(any()) } returns true
        every { user.canReadAllDeliverables() } returns true

        clock.instant = now

        prescreenModuleId =
            insertModule(
                name = "Pre-screen",
                overview = "Pre-screen Overview",
                phase = CohortPhase.PreScreen,
            )
        applicationModuleId =
            insertModule(
                name = "Application",
                overview = "Application Overview",
                phase = CohortPhase.Application,
            )

        // Extra module only for org 2 project 1, not visible to Org 1.
        extraApplicationModuleId =
            insertModule(
                name = "Extra Application",
                overview = "Extra Application Overview",
                phase = CohortPhase.Application,
            )

        insertApplicationModule(org1Project1ApplicationId, prescreenModuleId)
        insertApplicationModule(org1Project1ApplicationId, applicationModuleId)

        insertApplicationModule(org1Project2ApplicationId, prescreenModuleId)
        insertApplicationModule(org1Project2ApplicationId, applicationModuleId)

        insertApplicationModule(org2Project1ApplicationId, prescreenModuleId)
        insertApplicationModule(org2Project1ApplicationId, applicationModuleId)
        insertApplicationModule(org2Project1ApplicationId, extraApplicationModuleId)

        prescreenDeliverableId =
            insertDeliverable(
                descriptionHtml = "Pre-screen deliverable description",
                deliverableCategoryId = DeliverableCategory.Compliance,
                deliverableTypeId = DeliverableType.Questions,
                moduleId = prescreenModuleId,
                name = "Pre-screen deliverable",
            )

        applicationDeliverableId =
            insertDeliverable(
                descriptionHtml = "Application deliverable description",
                deliverableCategoryId = DeliverableCategory.CarbonEligibility,
                deliverableTypeId = DeliverableType.Questions,
                moduleId = applicationModuleId,
                name = "Application deliverable",
            )

        extraApplicationDeliverableId =
            insertDeliverable(
                descriptionHtml = "Extra application deliverable description",
                deliverableCategoryId = DeliverableCategory.FinancialViability,
                deliverableTypeId = DeliverableType.Questions,
                moduleId = extraApplicationModuleId,
                name = "Extra application deliverable",
            )

        // Org 1 Project 1
        org1Project1PrescreenSubmission =
            insertSubmission(
                deliverableId = prescreenDeliverableId,
                feedback = "feedback 1",
                modifiedTime = now,
                internalComment = "comment 1",
                projectId = org1ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )

        org1Project1ApplicationSubmission =
            insertSubmission(
                deliverableId = applicationDeliverableId,
                feedback = "feedback 2",
                modifiedTime = now,
                internalComment = "comment 2",
                projectId = org1ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )

        // Org 1 Project 2
        org1Project2PrescreenSubmission =
            insertSubmission(
                deliverableId = prescreenDeliverableId,
                feedback = "feedback 3",
                modifiedTime = now,
                internalComment = "comment 3",
                projectId = org1ProjectId2,
                submissionStatus = SubmissionStatus.InReview,
            )

        // No submission for Org 1 Project 2 Application Deliverable

        // Org 2 Project 1
        org2Project1PrescreenSubmission =
            insertSubmission(
                deliverableId = prescreenDeliverableId,
                feedback = "feedback 5",
                modifiedTime = now,
                internalComment = "comment 5",
                projectId = org2ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )

        org2Project1ApplicationSubmission =
            insertSubmission(
                deliverableId = applicationDeliverableId,
                feedback = "feedback 6",
                modifiedTime = now,
                internalComment = "comment 6",
                projectId = org2ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )

        org2Project1ExtraApplicationSubmission =
            insertSubmission(
                deliverableId = extraApplicationDeliverableId,
                feedback = "feedback 7",
                modifiedTime = now,
                internalComment = "comment 7",
                projectId = org2ProjectId1,
                submissionStatus = SubmissionStatus.Completed,
            )
      }

      @Test
      fun `filters by IDs`() {
        val org1Project1PrescreenModel =
            DeliverableSubmissionModel(
                category = DeliverableCategory.Compliance,
                cohortId = null,
                cohortName = null,
                deliverableId = prescreenDeliverableId,
                descriptionHtml = "Pre-screen deliverable description",
                documents = emptyList(),
                dueDate = null,
                feedback = "feedback 1",
                internalComment = "comment 1",
                modifiedTime = clock.instant,
                moduleId = prescreenModuleId,
                moduleName = "Pre-screen",
                moduleTitle = null,
                name = "Pre-screen deliverable",
                organizationId = organizationId,
                organizationName = organizationName,
                participantId = null,
                participantName = null,
                position = 1,
                projectDealName = "Internal Name 1",
                projectId = org1ProjectId1,
                projectName = "Project A",
                required = false,
                sensitive = false,
                status = SubmissionStatus.InReview,
                submissionId = org1Project1PrescreenSubmission,
                templateUrl = null,
                type = DeliverableType.Questions,
            )

        val org1Project1ApplicationModel =
            org1Project1PrescreenModel.copy(
                category = DeliverableCategory.CarbonEligibility,
                deliverableId = applicationDeliverableId,
                descriptionHtml = "Application deliverable description",
                feedback = "feedback 2",
                internalComment = "comment 2",
                moduleId = applicationModuleId,
                moduleName = "Application",
                name = "Application deliverable",
                position = 2,
                submissionId = org1Project1ApplicationSubmission,
            )

        val org1Project2PrescreenModel =
            org1Project1PrescreenModel.copy(
                feedback = "feedback 3",
                internalComment = "comment 3",
                projectDealName = "Internal Name 2",
                projectId = org1ProjectId2,
                projectName = "Project B",
                submissionId = org1Project2PrescreenSubmission,
            )

        val org1Project2ApplicationModel =
            org1Project1ApplicationModel.copy(
                feedback = null,
                modifiedTime = null,
                internalComment = null,
                projectDealName = "Internal Name 2",
                projectId = org1ProjectId2,
                projectName = "Project B",
                submissionId = null,
                status = SubmissionStatus.NotSubmitted,
            )

        val org2Project1PrescreenModel =
            org1Project1PrescreenModel.copy(
                feedback = "feedback 5",
                internalComment = "comment 5",
                organizationId = organizationId2,
                organizationName = "Organization 2",
                projectDealName = "Internal Name 3",
                projectId = org2ProjectId1,
                projectName = "Project C",
                submissionId = org2Project1PrescreenSubmission,
            )

        val org2Project1ApplicationModel =
            org1Project1ApplicationModel.copy(
                feedback = "feedback 6",
                internalComment = "comment 6",
                organizationId = organizationId2,
                organizationName = "Organization 2",
                projectDealName = "Internal Name 3",
                projectId = org2ProjectId1,
                projectName = "Project C",
                submissionId = org2Project1ApplicationSubmission,
            )

        val org2Project1ExtraApplicationModel =
            org2Project1ApplicationModel.copy(
                category = DeliverableCategory.FinancialViability,
                deliverableId = extraApplicationDeliverableId,
                descriptionHtml = "Extra application deliverable description",
                feedback = "feedback 7",
                internalComment = "comment 7",
                moduleId = extraApplicationModuleId,
                moduleName = "Extra Application",
                name = "Extra application deliverable",
                position = 3,
                submissionId = org2Project1ExtraApplicationSubmission,
                status = SubmissionStatus.Completed,
            )

        assertSetEquals(
            setOf(org1Project1PrescreenModel, org1Project1ApplicationModel),
            store.fetchApplicationDeliverables(projectId = org1ProjectId1).toSet(),
            "Fetch application deliverables by projectId for org1 project1",
        )

        assertSetEquals(
            setOf(org1Project2PrescreenModel, org1Project2ApplicationModel),
            store.fetchApplicationDeliverables(projectId = org1ProjectId2).toSet(),
            "Fetch application deliverables by projectId for org1 project2",
        )

        assertSetEquals(
            setOf(
                org2Project1PrescreenModel,
                org2Project1ApplicationModel,
                org2Project1ExtraApplicationModel,
            ),
            store.fetchApplicationDeliverables(projectId = org2ProjectId1).toSet(),
            "Fetch application deliverables by org2 project1",
        )

        assertSetEquals(
            setOf(org1Project1PrescreenModel),
            store
                .fetchApplicationDeliverables(
                    projectId = org1ProjectId1,
                    deliverableId = prescreenDeliverableId,
                )
                .toSet(),
            "Fetch application deliverables by projectId and deliverableId for org1 project1",
        )

        assertSetEquals(
            setOf(org1Project2PrescreenModel),
            store
                .fetchApplicationDeliverables(
                    projectId = org1ProjectId2,
                    deliverableId = prescreenDeliverableId,
                )
                .toSet(),
            "Fetch application deliverables by projectId and deliverableId for org1 project2",
        )

        assertSetEquals(
            setOf(org2Project1PrescreenModel),
            store
                .fetchApplicationDeliverables(
                    projectId = org2ProjectId1,
                    deliverableId = prescreenDeliverableId,
                )
                .toSet(),
            "Fetch application deliverables by projectId and deliverableId for org1 project2",
        )

        assertSetEquals(
            setOf(org1Project1PrescreenModel, org1Project1ApplicationModel),
            store.fetchApplicationDeliverables(applicationId = org1Project1ApplicationId).toSet(),
            "Fetch application deliverables by applicationId for org1 project1",
        )

        assertSetEquals(
            setOf(org1Project2PrescreenModel, org1Project2ApplicationModel),
            store.fetchApplicationDeliverables(applicationId = org1Project2ApplicationId).toSet(),
            "Fetch application deliverables by applicationId for org1 project2",
        )

        assertSetEquals(
            setOf(
                org2Project1PrescreenModel,
                org2Project1ApplicationModel,
                org2Project1ExtraApplicationModel,
            ),
            store.fetchApplicationDeliverables(applicationId = org2Project1ApplicationId).toSet(),
            "Fetch application deliverables by applicationId for org2 project1",
        )

        assertSetEquals(
            setOf(org1Project1ApplicationModel),
            store
                .fetchApplicationDeliverables(
                    applicationId = org1Project1ApplicationId,
                    deliverableId = applicationDeliverableId,
                )
                .toSet(),
            "Fetch application deliverables by applicationId and deliverableId for org1 project1",
        )

        assertSetEquals(
            setOf(org1Project2ApplicationModel),
            store
                .fetchApplicationDeliverables(
                    applicationId = org1Project2ApplicationId,
                    deliverableId = applicationDeliverableId,
                )
                .toSet(),
            "Fetch application deliverables by applicationId and deliverableId for org1 project2",
        )

        assertSetEquals(
            setOf(org2Project1ApplicationModel),
            store
                .fetchApplicationDeliverables(
                    applicationId = org2Project1ApplicationId,
                    deliverableId = applicationDeliverableId,
                )
                .toSet(),
            "Fetch application deliverables by applicationId and deliverableId for org1 project2",
        )

        assertSetEquals(
            setOf(
                org1Project1PrescreenModel,
                org1Project1ApplicationModel,
                org1Project2PrescreenModel,
                org1Project2ApplicationModel,
            ),
            store.fetchApplicationDeliverables(organizationId = organizationId).toSet(),
            "Fetch application deliverables by organizationId 1",
        )

        assertSetEquals(
            setOf(
                org2Project1PrescreenModel,
                org2Project1ApplicationModel,
                org2Project1ExtraApplicationModel,
            ),
            store.fetchApplicationDeliverables(organizationId = organizationId2).toSet(),
            "Fetch application deliverables by organizationId 2",
        )

        assertSetEquals(
            setOf(
                org1Project1PrescreenModel,
                org1Project2PrescreenModel,
                org2Project1PrescreenModel,
            ),
            store.fetchApplicationDeliverables(deliverableId = prescreenDeliverableId).toSet(),
            "Fetch application deliverables by pre-screen deliverableId",
        )

        assertSetEquals(
            setOf(
                org1Project1ApplicationModel,
                org1Project2ApplicationModel,
                org2Project1ApplicationModel,
            ),
            store.fetchApplicationDeliverables(deliverableId = applicationDeliverableId).toSet(),
            "Fetch application deliverables by application deliverableId",
        )

        assertSetEquals(
            setOf(
                org1Project1PrescreenModel,
                org1Project2PrescreenModel,
                org2Project1PrescreenModel,
            ),
            store.fetchApplicationDeliverables(moduleId = prescreenModuleId).toSet(),
            "Fetch application deliverables by pre-screen moduleId",
        )

        assertSetEquals(
            setOf(
                org1Project1ApplicationModel,
                org1Project2ApplicationModel,
                org2Project1ApplicationModel,
            ),
            store.fetchApplicationDeliverables(moduleId = applicationModuleId).toSet(),
            "Fetch application deliverables by application moduleId",
        )

        assertSetEquals(
            setOf(
                org1Project1PrescreenModel,
                org1Project1ApplicationModel,
                org1Project2PrescreenModel,
                org1Project2ApplicationModel,
                org2Project1PrescreenModel,
                org2Project1ApplicationModel,
                org2Project1ExtraApplicationModel,
            ),
            store.fetchApplicationDeliverables().toSet(),
            "Fetch application deliverables by application deliverableId",
        )
      }

      @Test
      fun `throws exception if no permission to read entities`() {
        every { user.canReadAllDeliverables() } returns false
        every { user.canReadOrganization(any()) } returns false
        every { user.canReadOrganizationDeliverables(any()) } returns false
        every { user.canReadProject(any()) } returns false
        every { user.canReadProjectDeliverables(any()) } returns false
        every { user.canReadModule(any()) } returns false
        every { user.canReadApplication(any()) } returns false

        assertThrows<OrganizationNotFoundException> {
          store.fetchApplicationDeliverables(organizationId = organizationId)
        }
        assertThrows<ApplicationNotFoundException> {
          store.fetchApplicationDeliverables(applicationId = org1Project1ApplicationId)
        }
        assertThrows<ProjectNotFoundException> {
          store.fetchApplicationDeliverables(projectId = org1ProjectId1)
        }
        assertThrows<ModuleNotFoundException> {
          store.fetchApplicationDeliverables(moduleId = prescreenModuleId)
        }
        assertThrows<AccessDeniedException> {
          store.fetchApplicationDeliverables(deliverableId = prescreenDeliverableId)
        }

        assertThrows<AccessDeniedException> { store.fetchApplicationDeliverables() }
      }
    }
  }

  @Nested
  inner class Restart {
    @Test
    fun `updates status and creates history entry`() {
      val otherUserId = insertUser()
      val applicationId =
          insertApplication(
              createdBy = otherUserId,
              status = ApplicationStatus.PassedPreScreen,
              internalName = "XXX_internalName",
          )
      val initial = applicationsDao.findAll().single()

      clock.instant = Instant.ofEpochSecond(30)

      store.restart(applicationId)

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.NotSubmitted,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          applicationsDao.findAll(),
      )

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  applicationStatusId = ApplicationStatus.NotSubmitted,
              )
          ),
          applicationHistoriesDao.findAll().map { it.copy(id = null) },
      )
    }

    @Test
    fun `does nothing if application is already unsubmitted`() {
      val applicationId = insertApplication(status = ApplicationStatus.NotSubmitted)
      val initial = applicationsDao.findAll()

      clock.instant = Instant.ofEpochSecond(30)

      store.restart(applicationId)

      assertEquals(initial, applicationsDao.findAll())

      assertTableEmpty(APPLICATION_HISTORIES, "Should not have inserted any history rows")
    }

    @Test
    fun `throws exception if application is already submitted`() {
      val submittedStauses =
          ApplicationStatus.entries.filter {
            it != ApplicationStatus.NotSubmitted &&
                it != ApplicationStatus.FailedPreScreen &&
                it != ApplicationStatus.PassedPreScreen
          }
      submittedStauses.forEach {
        insertProject()
        val applicationId = insertApplication(status = it, internalName = "Application $it")
        assertThrows<IllegalStateException> { store.restart(applicationId) }
      }
    }

    @Test
    fun `throws exception if no permission`() {
      val applicationId = insertApplication()

      every { user.canUpdateApplicationSubmissionStatus(any()) } returns false

      assertThrows<AccessDeniedException> { store.restart(applicationId) }
    }
  }

  @Nested
  inner class Review {
    @Test
    fun `updates review-related fields and creates history entry`() {
      val otherUserId = insertUser()
      val applicationId = insertApplication(createdBy = otherUserId)
      val initial = applicationsDao.findAll().single()

      clock.instant = Instant.ofEpochSecond(30)

      store.review(applicationId) {
        it.copy(
            boundary = rectangle(1),
            createdTime = Instant.ofEpochSecond(100),
            feedback = "feedback",
            internalComment = "internal comment",
            internalName = "new name",
            organizationId = OrganizationId(-1),
            projectId = ProjectId(-1),
            status = ApplicationStatus.SourcingTeamReview,
        )
      }

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.SourcingTeamReview,
                  feedback = "feedback",
                  internalComment = "internal comment",
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          applicationsDao.findAll(),
      )

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  applicationStatusId = ApplicationStatus.SourcingTeamReview,
                  internalComment = "internal comment",
                  feedback = "feedback",
              )
          ),
          applicationHistoriesDao.findAll().map { it.copy(id = null) },
      )
    }

    @Test
    fun `throws exception if no permission`() {
      val applicationId = insertApplication()

      every { user.canReviewApplication(any()) } returns false

      assertThrows<AccessDeniedException> { store.review(applicationId) { it } }
    }
  }

  @Nested
  inner class Submit {
    @Test
    fun `detects missing boundary`() {
      val applicationId = insertApplication()

      val result = store.submit(applicationId, validVariables(rectangle(1)))

      assertEquals(listOf(messages.applicationPreScreenFailureNoBoundary()), result.problems)
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects boundary outside any countries`() {
      val boundary = rectangle(1)
      val applicationId = insertApplication(boundary = boundary)

      val result = store.submit(applicationId, validVariables(boundary))

      assertEquals(listOf(messages.applicationPreScreenBoundaryInNoCountry()), result.problems)
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects boundary country and variable country mismatch`() {
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 20000) }
      val applicationId = insertApplication(boundary = boundary)

      val result = store.submit(applicationId, validVariables(boundary).copy(countryCode = "TZ"))

      assertEquals(
          listOf(
              messages.applicationPreScreenFailureMismatchCountries("United States", "Tanzania")
          ),
          result.problems,
      )
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects boundary in multiple countries`() {
      val boundary = Turtle(point(30, 50)).makePolygon { rectangle(200000, 200000) }
      val applicationId = insertApplication(boundary = boundary)

      val result = store.submit(applicationId, validVariables(boundary))

      assertEquals(listOf(messages.applicationPreScreenFailureMultipleCountries()), result.problems)
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @MethodSource(
        "com.terraformation.backend.accelerator.db.ApplicationStoreTest#siteLocationsAndSizes"
    )
    @ParameterizedTest
    fun `detects land use hectares below minimum`(
        country: String,
        origin: Point,
        minTotalHectares: Int,
        minMangroveHectares: Int?,
    ) {
      insertProject()
      val boundary = Turtle(origin).makePolygon { rectangle(10000, minTotalHectares + 10) }
      val countryCode = countriesDao.fetchOneByName(country)?.code
      val applicationId = insertApplication(boundary = boundary, internalName = country)

      val result =
          store.submit(
              applicationId,
              ApplicationVariableValues(
                  countryCode = countryCode,
                  landUseModelHectares =
                      mapOf(LandUseModelType.NativeForest to BigDecimal(minTotalHectares - 10)),
                  numSpeciesToBePlanted = 500,
                  projectType = PreScreenProjectType.Mixed,
                  totalExpansionPotential = BigDecimal(5000),
              ),
          )

      assertEquals(
          listOf(
              messages.applicationPreScreenFailureBadSize(
                  country,
                  minTotalHectares,
                  100000,
                  minMangroveHectares,
              )
          ),
          result.problems,
          country,
      )
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status, country)
    }

    @MethodSource(
        "com.terraformation.backend.accelerator.db.ApplicationStoreTest#siteLocationsAndSizes"
    )
    @ParameterizedTest
    fun `passes minimum land use hectares with total hectares`(
        country: String,
        origin: Point,
        minTotalHectares: Int,
        minMangroveHectares: Int?,
    ) {
      insertProject()
      val boundary = Turtle(origin).makePolygon { rectangle(10000, minTotalHectares + 10) }
      val countryCode = countriesDao.fetchOneByName(country)?.code
      val applicationId = insertApplication(boundary = boundary, internalName = country)

      val result =
          store.submit(
              applicationId,
              ApplicationVariableValues(
                  countryCode = countryCode,
                  landUseModelHectares =
                      mapOf(
                          LandUseModelType.NativeForest to BigDecimal(minTotalHectares - 10),
                          LandUseModelType.Mangroves to BigDecimal(10),
                      ),
                  numSpeciesToBePlanted = 500,
                  projectType = PreScreenProjectType.Mixed,
                  totalExpansionPotential = BigDecimal(5000),
              ),
          )

      assertEquals(emptyList<String>(), result.problems, country)
      assertEquals(ApplicationStatus.PassedPreScreen, result.application.status, country)
    }

    @MethodSource(
        "com.terraformation.backend.accelerator.db.ApplicationStoreTest#siteLocationsAndSizes"
    )
    @ParameterizedTest
    fun `passes minimum land use hectares with mangrove hectares`(
        country: String,
        origin: Point,
        minTotalHectares: Int,
        minMangroveHectares: Int?,
    ) {
      if (minMangroveHectares == null) {
        return
      }

      insertProject()
      val boundary = Turtle(origin).makePolygon { rectangle(10000, minTotalHectares + 10) }
      val countryCode = countriesDao.fetchOneByName(country)?.code
      val applicationId = insertApplication(boundary = boundary, internalName = country)

      val result =
          store.submit(
              applicationId,
              ApplicationVariableValues(
                  countryCode = countryCode,
                  landUseModelHectares =
                      mapOf(
                          LandUseModelType.NativeForest to BigDecimal(10),
                          LandUseModelType.Mangroves to BigDecimal(minMangroveHectares),
                      ),
                  numSpeciesToBePlanted = 500,
                  projectType = PreScreenProjectType.Mixed,
                  totalExpansionPotential = BigDecimal(5000),
              ),
          )

      assertEquals(emptyList<String>(), result.problems, country)
      assertEquals(ApplicationStatus.PassedPreScreen, result.application.status, country)
    }

    @Test
    fun `detects land use hectares above maximum`() {
      val boundary = Turtle(point(122, 11.25)).makePolygon { rectangle(10000, 8000) }
      val applicationId = insertApplication(boundary = boundary)

      val result =
          store.submit(
              applicationId,
              ApplicationVariableValues(
                  countryCode = "PH",
                  landUseModelHectares =
                      mapOf(
                          LandUseModelType.NativeForest to BigDecimal(60000),
                          LandUseModelType.Agroforestry to BigDecimal(60000),
                          LandUseModelType.Mangroves to BigDecimal(2000),
                      ),
                  numSpeciesToBePlanted = 500,
                  projectType = PreScreenProjectType.Mixed,
                  totalExpansionPotential = BigDecimal(5000),
              ),
          )

      assertEquals(
          listOf(messages.applicationPreScreenFailureBadSize("Philippines", 3000, 100000, 1000)),
          result.problems,
      )
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects boundary in country that is ineligible for accelerator`() {
      val boundary = Turtle(point(-100, 51)).makePolygon { rectangle(10000, 16000) }
      val applicationId = insertApplication(boundary = boundary)

      val result = store.submit(applicationId, validVariables(boundary).copy(countryCode = "CA"))

      assertEquals(
          listOf(messages.applicationPreScreenFailureIneligibleCountry("Canada")),
          result.problems,
      )
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects too few species for project type`() {
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 50000) }
      val applicationId = insertApplication(boundary = boundary)

      val result =
          store.submit(applicationId, validVariables(boundary).copy(numSpeciesToBePlanted = 9))

      assertEquals(listOf(messages.applicationPreScreenFailureTooFewSpecies(10)), result.problems)
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `writes problems as html list to feedback`() {
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 120000) }
      val applicationId = insertApplication(boundary = boundary)

      val result =
          store.submit(applicationId, validVariables(boundary).copy(numSpeciesToBePlanted = 9))

      assertEquals(
          listOf(
              messages.applicationPreScreenFailureBadSize("United States", 15000, 100000),
              messages.applicationPreScreenFailureTooFewSpecies(10),
          ),
          result.problems,
          "Pre-Screen problems from submitting",
      )

      val feedbackHtml =
          "<ul>\n" +
              "<li>${result.problems[0]}</li>\n" +
              "<li>${result.problems[1]}</li>\n" +
              "</ul>"

      assertEquals(feedbackHtml, result.application.feedback, "Pre-Screen feedback HTML")
      assertEquals(
          ApplicationStatus.FailedPreScreen,
          result.application.status,
          "Pre-Screen status",
      )
    }

    @Test
    fun `clears feedback for passing prescreen`() {
      val otherUserId = insertUser()
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 20000) }
      val applicationId =
          insertApplication(boundary = boundary, createdBy = otherUserId, feedback = "feedback")
      val initial = applicationsDao.findAll().single()

      clock.instant = Instant.ofEpochSecond(30)

      store.submit(applicationId, validVariables(boundary))

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.PassedPreScreen,
                  countryCode = "US",
                  feedback = null,
                  internalName = "USA_$organizationName",
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          applicationsDao.findAll(),
      )
    }

    @Test
    fun `adds suffix to conflicting internal name after passing pre-screen`() {
      val otherUserId = insertUser()
      insertApplication(createdBy = otherUserId, internalName = "USA_$organizationName")
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 20000) }

      insertProject()
      val applicationId = insertApplication(boundary = boundary, createdBy = otherUserId)
      val initial = applicationsDao.fetchOneById(applicationId)!!

      clock.instant = Instant.ofEpochSecond(30)

      store.submit(applicationId, validVariables(boundary))

      assertEquals(
          initial.copy(
              applicationStatusId = ApplicationStatus.PassedPreScreen,
              countryCode = "US",
              internalName = "USA_${organizationName}_2",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          ),
          applicationsDao.fetchOneById(applicationId),
      )
    }

    @Test
    fun `passes prescreen with completed submission without boundary`() {
      val otherUserId = insertUser()
      val applicationId =
          insertApplication(boundary = null, createdBy = otherUserId, feedback = "feedback")
      val initial = applicationsDao.findAll().single()

      clock.instant = Instant.ofEpochSecond(30)

      val validVariables =
          ApplicationVariableValues(
              countryCode = "US",
              landUseModelHectares =
                  mapOf(
                      LandUseModelType.NativeForest to BigDecimal(15000),
                      LandUseModelType.Monoculture to BigDecimal.ZERO,
                  ),
              numSpeciesToBePlanted = 500,
              projectType = PreScreenProjectType.Terrestrial,
              totalExpansionPotential = BigDecimal(1500),
          )

      val validSubmission = mockk<DeliverableSubmissionModel>()
      every { validSubmission.status } returns SubmissionStatus.Completed

      store.submit(applicationId, validVariables, validSubmission)

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.PassedPreScreen,
                  countryCode = "US",
                  feedback = null,
                  internalName = "USA_$organizationName",
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          applicationsDao.findAll(),
      )
    }

    @Test
    fun `updates status and creates history entry for submitting prescreen`() {
      val otherUserId = insertUser()
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 20000) }
      val applicationId = insertApplication(boundary = boundary, createdBy = otherUserId)
      val initial = applicationsDao.findAll().single()
      val moduleId1 = insertModule(phase = CohortPhase.Application)
      val moduleId2 = insertModule(phase = CohortPhase.Application)
      insertModule(phase = CohortPhase.PreScreen)

      clock.instant = Instant.ofEpochSecond(30)

      store.submit(applicationId, validVariables(boundary))

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.PassedPreScreen,
                  countryCode = "US",
                  internalName = "USA_$organizationName",
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          applicationsDao.findAll(),
      )

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  boundary = initial.boundary,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  applicationStatusId = ApplicationStatus.PassedPreScreen,
              )
          ),
          applicationHistoriesDao.findAll().map { it.copy(id = null) },
      )

      assertTableEquals(
          listOf(
              ApplicationModulesRecord(
                  applicationId = applicationId,
                  moduleId = moduleId1,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete,
              ),
              ApplicationModulesRecord(
                  applicationId = applicationId,
                  moduleId = moduleId2,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete,
              ),
          )
      )
    }

    @Test
    fun `updates status and creates history entry for submitting full application if all modules are completed`() {
      val otherUserId = insertUser()
      val applicationId =
          insertApplication(createdBy = otherUserId, status = ApplicationStatus.PassedPreScreen)
      val initial = applicationsDao.findAll().single()

      val moduleId1 = insertModule(phase = CohortPhase.Application)
      val moduleId2 = insertModule(phase = CohortPhase.Application)

      insertApplicationModule(applicationId, moduleId1, ApplicationModuleStatus.Complete)
      insertApplicationModule(applicationId, moduleId2, ApplicationModuleStatus.Complete)

      clock.instant = Instant.ofEpochSecond(30)
      store.submit(applicationId)

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.Submitted,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          applicationsDao.findAll(),
      )

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  boundary = initial.boundary,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  applicationStatusId = ApplicationStatus.Submitted,
              )
          ),
          applicationHistoriesDao.findAll().map { it.copy(id = null) },
      )

      eventPublisher.assertEventPublished(ApplicationSubmittedEvent(applicationId))
    }

    @Test
    fun `detects incomplete modules for submitting full application`() {
      val otherUserId = insertUser()
      val applicationId =
          insertApplication(createdBy = otherUserId, status = ApplicationStatus.PassedPreScreen)
      val initial = applicationsDao.findAll()

      val moduleId1 = insertModule(phase = CohortPhase.Application)
      val moduleId2 = insertModule(phase = CohortPhase.Application)

      insertApplicationModule(applicationId, moduleId1, ApplicationModuleStatus.Complete)
      insertApplicationModule(applicationId, moduleId2, ApplicationModuleStatus.Incomplete)

      val result = store.submit(applicationId)
      assertEquals(listOf(messages.applicationModulesIncomplete()), result.problems)
      assertEquals(initial, applicationsDao.findAll())
      assertTableEmpty(APPLICATION_HISTORIES, "Should not have inserted any history rows")
    }

    @Test
    fun `does not update existing module status on resubmit`() {
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 20000) }
      val applicationId = insertApplication(boundary = boundary)
      val moduleId1 = insertModule(phase = CohortPhase.Application)
      val moduleId2 = insertModule(phase = CohortPhase.Application)

      insertApplicationModule(applicationId, moduleId1, ApplicationModuleStatus.Complete)

      clock.instant = Instant.ofEpochSecond(30)

      store.submit(applicationId, validVariables(boundary))

      assertSetEquals(
          setOf(
              ApplicationModulesRow(
                  applicationId = applicationId,
                  moduleId = moduleId1,
                  applicationModuleStatusId = ApplicationModuleStatus.Complete,
              ),
              ApplicationModulesRow(
                  applicationId = applicationId,
                  moduleId = moduleId2,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete,
              ),
          ),
          applicationModulesDao.findAll().toSet(),
      )
    }

    @Test
    fun `does nothing if application is already submitted`() {
      val applicationId = insertApplication(status = ApplicationStatus.Submitted)
      val initial = applicationsDao.findAll()

      clock.instant = Instant.ofEpochSecond(30)

      store.submit(applicationId)

      assertEquals(initial, applicationsDao.findAll())

      assertTableEmpty(APPLICATION_HISTORIES, "Should not have inserted any history rows")
    }

    @Test
    fun `throws exception if no permission`() {
      val applicationId = insertApplication()

      every { user.canUpdateApplicationSubmissionStatus(any()) } returns false

      assertThrows<AccessDeniedException> { store.submit(applicationId) }
    }

    private fun validVariables(boundary: Geometry): ApplicationVariableValues {
      val projectHectares = boundary.calculateAreaHectares()
      return ApplicationVariableValues(
          countryCode = "US",
          landUseModelHectares =
              mapOf(
                  LandUseModelType.NativeForest to projectHectares,
                  LandUseModelType.Monoculture to BigDecimal.ZERO,
              ),
          numSpeciesToBePlanted = 500,
          projectType = PreScreenProjectType.Terrestrial,
          totalExpansionPotential = BigDecimal(1500),
      )
    }
  }

  @Nested
  inner class UpdateBoundary {
    @Test
    fun `updates boundary`() {
      val otherUserId = insertUser()
      val applicationId =
          insertApplication(createdBy = otherUserId, internalName = "XXX_$organizationName")
      val boundary = Turtle(point(0, 51)).makePolygon { rectangle(100, 100) }

      clock.instant = Instant.ofEpochSecond(30)

      store.updateBoundary(applicationId, boundary)

      val applicationRow = applicationsDao.findAll().single()
      assertEquals(
          ApplicationsRow(
              applicationStatusId = ApplicationStatus.NotSubmitted,
              countryCode = null,
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "XXX_$organizationName",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              projectId = inserted.projectId,
          ),
          applicationRow.copy(boundary = null),
      )

      assertGeometryEquals(boundary, applicationRow.boundary, "Boundary in applications row")

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  applicationStatusId = ApplicationStatus.NotSubmitted,
                  boundary = applicationRow.boundary,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )
          ),
          applicationHistoriesDao.findAll().map { it.copy(id = null) },
      )
    }

    @Test
    fun `throws exception if no permission`() {
      val applicationId = insertApplication()

      every { user.canUpdateApplicationBoundary(applicationId) } returns false

      assertThrows<AccessDeniedException> { store.updateBoundary(applicationId, rectangle(1)) }
    }
  }

  @Nested
  inner class UpdateCountry {
    @Test
    fun `updates internal name and country code, and pubilshes event if they are changed`() {
      val otherUserId = insertUser()
      val applicationId =
          insertApplication(createdBy = otherUserId, internalName = "XXX_$organizationName")

      clock.instant = Instant.ofEpochSecond(30)

      store.updateCountryCode(applicationId, "US")

      val applicationRow = applicationsDao.findAll().single()
      assertEquals(
          ApplicationsRow(
              applicationStatusId = ApplicationStatus.NotSubmitted,
              countryCode = "US",
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "USA_$organizationName",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              projectId = inserted.projectId,
          ),
          applicationRow,
      )

      eventPublisher.assertEventPublished(
          ApplicationInternalNameUpdatedEvent(applicationId),
          "Country and internal name updated",
      )
      eventPublisher.clear()

      store.updateCountryCode(applicationId, "US")
      eventPublisher.assertEventNotPublished<ApplicationInternalNameUpdatedEvent>(
          "Country and internal name not updated"
      )
    }

    @Test
    fun `throws exception if no permission`() {
      val applicationId = insertApplication()

      every { user.canUpdateApplicationCountry(applicationId) } returns false

      assertThrows<AccessDeniedException> { store.updateCountryCode(applicationId, "US") }
    }
  }

  companion object {
    @JvmStatic
    fun siteLocationsAndSizes() =
        listOf(
            Arguments.of("Ghana", point(-1.5, 7.25), 3000, null),
            Arguments.of("Philippines", point(122, 11.25), 3000, 1000),
            Arguments.of("Indonesia", point(114, -0.8), 15000, 1000),
            Arguments.of("United States", point(-100, 41), 15000, null),
        )
  }
}
