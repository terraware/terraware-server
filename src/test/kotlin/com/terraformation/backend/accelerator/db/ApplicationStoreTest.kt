package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ApplicationModuleModel
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
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
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.equalsOrBothNull
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry
import org.springframework.security.access.AccessDeniedException

class ApplicationStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val messages = Messages()
  private val store: ApplicationStore by lazy {
    ApplicationStore(clock, countriesDao, CountryDetector(), dslContext, messages, organizationsDao)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization(countryCode = "US", name = "Organization 1")
    insertProject(name = "Project A")

    every { user.adminOrganizations() } returns setOf(organizationId)
    every { user.canCreateApplication(any()) } returns true
    every { user.canReadApplication(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReviewApplication(any()) } returns true
    every { user.canUpdateApplicationBoundary(any()) } returns true
    every { user.canUpdateApplicationSubmissionStatus(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `populates initial values and creates history entry`() {
      val moduleId = insertModule(phase = CohortPhase.PreScreen)
      val now = Instant.ofEpochSecond(30)
      clock.instant = now

      val model = store.create(inserted.projectId)

      assertEquals(
          listOf(
              ApplicationsRow(
                  applicationStatusId = ApplicationStatus.NotSubmitted,
                  createdBy = user.userId,
                  createdTime = now,
                  id = model.id,
                  modifiedBy = user.userId,
                  modifiedTime = now,
                  projectId = inserted.projectId,
              )),
          applicationsDao.findAll())

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = model.id,
                  applicationStatusId = ApplicationStatus.NotSubmitted,
                  modifiedBy = user.userId,
                  modifiedTime = now,
              )),
          applicationHistoriesDao.findAll().map { it.copy(id = null) })

      assertEquals(
          listOf(
              ApplicationModulesRow(
                  applicationId = model.id,
                  moduleId = moduleId,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete)),
          applicationModulesDao.findAll())
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
              feedback = "feedback",
              internalComment = "internal comment",
              internalName = "internalName",
              status = ApplicationStatus.PreCheck,
          )

      org1ProjectId2 = insertProject(organizationId = organizationId, name = "Project B")
      org1Project2ApplicationId =
          insertApplication(
              projectId = org1ProjectId2,
              boundary = rectangle(2),
              feedback = "feedback 2",
              internalComment = "internal comment 2",
              internalName = "internalName2",
              status = ApplicationStatus.PLReview,
          )

      organizationId2 = insertOrganization(2, name = "Organization 2")
      org2ProjectId1 = insertProject(organizationId = organizationId2, name = "Project C")
      org2Project1ApplicationId = insertApplication(projectId = org2ProjectId1)

      every { user.adminOrganizations() } returns setOf(organizationId, organizationId2)
    }

    @Nested
    inner class FetchOneById {
      @Test
      fun `fetches application data`() {
        assertEquals(
            ExistingApplicationModel(
                boundary = rectangle(1),
                createdTime = Instant.EPOCH,
                feedback = "feedback",
                id = org1Project1ApplicationId,
                internalComment = "internal comment",
                internalName = "internalName",
                organizationId = organizationId,
                projectId = org1ProjectId1,
                status = ApplicationStatus.PreCheck),
            store.fetchOneById(org1Project1ApplicationId))
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
    inner class FetchByProjectId {
      @Test
      fun `fetches application for project`() {
        assertEquals(
            listOf(
                ExistingApplicationModel(
                    boundary = rectangle(1),
                    createdTime = Instant.EPOCH,
                    feedback = "feedback",
                    id = org1Project1ApplicationId,
                    internalComment = "internal comment",
                    internalName = "internalName",
                    organizationId = organizationId,
                    projectId = org1ProjectId1,
                    status = ApplicationStatus.PreCheck)),
            store.fetchByProjectId(org1ProjectId1))
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
            emptyList<ExistingApplicationModel>(), store.fetchByProjectId(noApplicationProjectId))
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
                    createdTime = Instant.EPOCH,
                    feedback = "feedback",
                    id = org1Project1ApplicationId,
                    internalComment = "internal comment",
                    internalName = "internalName",
                    organizationId = organizationId,
                    projectId = org1ProjectId1,
                    status = ApplicationStatus.PreCheck),
                ExistingApplicationModel(
                    boundary = rectangle(2),
                    createdTime = Instant.EPOCH,
                    feedback = "feedback 2",
                    id = org1Project2ApplicationId,
                    internalComment = "internal comment 2",
                    internalName = "internalName2",
                    organizationId = organizationId,
                    projectId = org1ProjectId2,
                    status = ApplicationStatus.PLReview),
            ),
            store.fetchByOrganizationId(organizationId))
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
                    createdTime = Instant.EPOCH,
                    feedback = "feedback",
                    id = org1Project1ApplicationId,
                    internalComment = "internal comment",
                    internalName = "internalName",
                    organizationId = organizationId,
                    projectId = org1ProjectId1,
                    status = ApplicationStatus.PreCheck),
                ExistingApplicationModel(
                    boundary = rectangle(2),
                    createdTime = Instant.EPOCH,
                    feedback = "feedback 2",
                    id = org1Project2ApplicationId,
                    internalComment = "internal comment 2",
                    internalName = "internalName2",
                    organizationId = organizationId,
                    projectId = org1ProjectId2,
                    status = ApplicationStatus.PLReview),
                ExistingApplicationModel(
                    createdTime = Instant.EPOCH,
                    id = org2Project1ApplicationId,
                    organizationId = organizationId2,
                    projectId = org2ProjectId1,
                    status = ApplicationStatus.NotSubmitted),
            ),
            store.fetchAll())
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
                status = ApplicationStatus.NotSubmitted)
        val laterHistoryId =
            insertApplicationHistory(
                applicationId = org1Project1ApplicationId,
                boundary = rectangle(1),
                feedback = "feedback 2",
                internalComment = "internal comment 2",
                modifiedTime = Instant.ofEpochSecond(10),
                status = ApplicationStatus.PassedPreScreen)

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
            store.fetchHistoryByApplicationId(org1Project1ApplicationId))
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
        val applicationModule =
            insertModule(
                name = "Application",
                overview = "Application Overview",
                phase = CohortPhase.Application)
        insertModule(name = "Hidden module", phase = CohortPhase.Phase1FeasibilityStudy)

        insertApplicationModule(
            inserted.applicationId, prescreenModule, ApplicationModuleStatus.Complete)
        insertApplicationModule(
            inserted.applicationId, applicationModule, ApplicationModuleStatus.Incomplete)

        val expected =
            setOf(
                ApplicationModuleModel(
                    id = prescreenModule,
                    name = "Pre-screen",
                    phase = CohortPhase.PreScreen,
                    overview = "Pre-screen Overview",
                    applicationId = inserted.applicationId,
                    applicationModuleStatus = ApplicationModuleStatus.Complete),
                ApplicationModuleModel(
                    id = applicationModule,
                    name = "Application",
                    phase = CohortPhase.Application,
                    overview = "Application Overview",
                    applicationId = inserted.applicationId,
                    applicationModuleStatus = ApplicationModuleStatus.Incomplete),
            )
        val actual = store.fetchModulesByApplicationId(inserted.applicationId).toSet()

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
      private lateinit var prescreenDeliverableId: DeliverableId
      private lateinit var applicationDeliverableId: DeliverableId

      private lateinit var submissionId1: SubmissionId
      private lateinit var submissionId2: SubmissionId
      private lateinit var submissionId3: SubmissionId
      private lateinit var submissionId4: SubmissionId
      private lateinit var submissionId5: SubmissionId
      private lateinit var submissionId6: SubmissionId

      @BeforeEach
      fun setup() {
        every { user.canReadProjectDeliverables(any()) } returns true
        every { user.canReadOrganizationDeliverables(any()) } returns true
        every { user.canReadModule(any()) } returns true
        every { user.canReadAllDeliverables() } returns true

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
                phase = CohortPhase.Application)

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

        // Org 1 Project 1
        submissionId1 =
            insertSubmission(
                deliverableId = prescreenDeliverableId,
                feedback = "feedback 1",
                internalComment = "comment 1",
                projectId = org1ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )

        submissionId2 =
            insertSubmission(
                deliverableId = applicationDeliverableId,
                feedback = "feedback 2",
                internalComment = "comment 2",
                projectId = org1ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )

        // Org 1 Project 2
        submissionId3 =
            insertSubmission(
                deliverableId = prescreenDeliverableId,
                feedback = "feedback 3",
                internalComment = "comment 3",
                projectId = org1ProjectId2,
                submissionStatus = SubmissionStatus.InReview,
            )

        submissionId4 =
            insertSubmission(
                deliverableId = applicationDeliverableId,
                feedback = "feedback 4",
                internalComment = "comment 4",
                projectId = org1ProjectId2,
                submissionStatus = SubmissionStatus.InReview,
            )

        // Org 2 Project 1
        submissionId5 =
            insertSubmission(
                deliverableId = prescreenDeliverableId,
                feedback = "feedback 5",
                internalComment = "comment 5",
                projectId = org2ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )

        submissionId6 =
            insertSubmission(
                deliverableId = applicationDeliverableId,
                feedback = "feedback 6",
                internalComment = "comment 6",
                projectId = org2ProjectId1,
                submissionStatus = SubmissionStatus.InReview,
            )
      }

      @Test
      fun `filters by IDs`() {
        val deliverableSubmissionModel1 =
            DeliverableSubmissionModel(
                category = DeliverableCategory.Compliance,
                deliverableId = prescreenDeliverableId,
                descriptionHtml = "Pre-screen deliverable description",
                documents = emptyList(),
                dueDate = null,
                feedback = "feedback 1",
                internalComment = "comment 1",
                moduleId = prescreenModuleId,
                moduleName = "Pre-screen",
                moduleTitle = null,
                name = "Pre-screen deliverable",
                organizationId = organizationId,
                organizationName = "Organization 1",
                participantId = null,
                participantName = null,
                projectId = org1ProjectId1,
                projectName = "Project A",
                status = SubmissionStatus.InReview,
                submissionId = submissionId1,
                templateUrl = null,
                type = DeliverableType.Questions,
            )

        val deliverableSubmissionModel2 =
            deliverableSubmissionModel1.copy(
                category = DeliverableCategory.CarbonEligibility,
                deliverableId = applicationDeliverableId,
                descriptionHtml = "Application deliverable description",
                feedback = "feedback 2",
                internalComment = "comment 2",
                moduleId = applicationModuleId,
                moduleName = "Application",
                name = "Application deliverable",
                submissionId = submissionId2,
            )

        val deliverableSubmissionModel3 =
            deliverableSubmissionModel1.copy(
                feedback = "feedback 3",
                internalComment = "comment 3",
                projectId = org1ProjectId2,
                projectName = "Project B",
                submissionId = submissionId3,
            )

        val deliverableSubmissionModel4 =
            deliverableSubmissionModel2.copy(
                feedback = "feedback 4",
                internalComment = "comment 4",
                projectId = org1ProjectId2,
                projectName = "Project B",
                submissionId = submissionId4,
            )

        val deliverableSubmissionModel5 =
            deliverableSubmissionModel1.copy(
                feedback = "feedback 5",
                internalComment = "comment 5",
                organizationId = organizationId2,
                organizationName = "Organization 2",
                projectId = org2ProjectId1,
                projectName = "Project C",
                submissionId = submissionId5,
            )

        val deliverableSubmissionModel6 =
            deliverableSubmissionModel2.copy(
                feedback = "feedback 6",
                internalComment = "comment 6",
                organizationId = organizationId2,
                organizationName = "Organization 2",
                projectId = org2ProjectId1,
                projectName = "Project C",
                submissionId = submissionId6,
            )

        assertEquals(
            setOf(deliverableSubmissionModel1, deliverableSubmissionModel2),
            store.fetchApplicationDeliverables(projectId = org1ProjectId1).toSet(),
            "Fetch application deliverables by projectId for org1 project1")

        assertEquals(
            setOf(deliverableSubmissionModel3, deliverableSubmissionModel4),
            store.fetchApplicationDeliverables(projectId = org1ProjectId2).toSet(),
            "Fetch application deliverables by projectId for org1 project2")

        assertEquals(
            setOf(deliverableSubmissionModel5, deliverableSubmissionModel6),
            store.fetchApplicationDeliverables(projectId = org2ProjectId1).toSet(),
            "Fetch application deliverables by org2 project1")

        assertEquals(
            setOf(deliverableSubmissionModel1),
            store
                .fetchApplicationDeliverables(
                    projectId = org1ProjectId1, deliverableId = prescreenDeliverableId)
                .toSet(),
            "Fetch application deliverables by projectId and deliverableId for org1 project1")

        assertEquals(
            setOf(deliverableSubmissionModel3),
            store
                .fetchApplicationDeliverables(
                    projectId = org1ProjectId2, deliverableId = prescreenDeliverableId)
                .toSet(),
            "Fetch application deliverables by projectId and deliverableId for org1 project2")

        assertEquals(
            setOf(deliverableSubmissionModel5),
            store
                .fetchApplicationDeliverables(
                    projectId = org2ProjectId1, deliverableId = prescreenDeliverableId)
                .toSet(),
            "Fetch application deliverables by projectId and deliverableId for org1 project2")

        assertEquals(
            setOf(deliverableSubmissionModel1, deliverableSubmissionModel2),
            store.fetchApplicationDeliverables(applicationId = org1Project1ApplicationId).toSet(),
            "Fetch application deliverables by applicationId for org1 project1")

        assertEquals(
            setOf(deliverableSubmissionModel3, deliverableSubmissionModel4),
            store.fetchApplicationDeliverables(applicationId = org1Project2ApplicationId).toSet(),
            "Fetch application deliverables by applicationId for org1 project2")

        assertEquals(
            setOf(deliverableSubmissionModel5, deliverableSubmissionModel6),
            store.fetchApplicationDeliverables(applicationId = org2Project1ApplicationId).toSet(),
            "Fetch application deliverables by applicationId for org2 project1")

        assertEquals(
            setOf(deliverableSubmissionModel2),
            store
                .fetchApplicationDeliverables(
                    applicationId = org1Project1ApplicationId,
                    deliverableId = applicationDeliverableId)
                .toSet(),
            "Fetch application deliverables by applicationId and deliverableId for org1 project1")

        assertEquals(
            setOf(deliverableSubmissionModel4),
            store
                .fetchApplicationDeliverables(
                    applicationId = org1Project2ApplicationId,
                    deliverableId = applicationDeliverableId)
                .toSet(),
            "Fetch application deliverables by applicationId and deliverableId for org1 project2")

        assertEquals(
            setOf(deliverableSubmissionModel6),
            store
                .fetchApplicationDeliverables(
                    applicationId = org2Project1ApplicationId,
                    deliverableId = applicationDeliverableId)
                .toSet(),
            "Fetch application deliverables by applicationId and deliverableId for org1 project2")

        assertEquals(
            setOf(
                deliverableSubmissionModel1,
                deliverableSubmissionModel2,
                deliverableSubmissionModel3,
                deliverableSubmissionModel4),
            store.fetchApplicationDeliverables(organizationId = organizationId).toSet(),
            "Fetch application deliverables by organizationId 1")

        assertEquals(
            setOf(deliverableSubmissionModel5, deliverableSubmissionModel6),
            store.fetchApplicationDeliverables(organizationId = organizationId2).toSet(),
            "Fetch application deliverables by organizationId 2")

        assertEquals(
            setOf(
                deliverableSubmissionModel1,
                deliverableSubmissionModel3,
                deliverableSubmissionModel5),
            store.fetchApplicationDeliverables(deliverableId = prescreenDeliverableId).toSet(),
            "Fetch application deliverables by pre-screen deliverableId")

        assertEquals(
            setOf(
                deliverableSubmissionModel2,
                deliverableSubmissionModel4,
                deliverableSubmissionModel6),
            store.fetchApplicationDeliverables(deliverableId = applicationDeliverableId).toSet(),
            "Fetch application deliverables by application deliverableId")

        assertEquals(
            setOf(
                deliverableSubmissionModel1,
                deliverableSubmissionModel3,
                deliverableSubmissionModel5),
            store.fetchApplicationDeliverables(moduleId = prescreenModuleId).toSet(),
            "Fetch application deliverables by pre-screen moduleId")

        assertEquals(
            setOf(
                deliverableSubmissionModel2,
                deliverableSubmissionModel4,
                deliverableSubmissionModel6),
            store.fetchApplicationDeliverables(moduleId = applicationModuleId).toSet(),
            "Fetch application deliverables by application moduleId")

        assertEquals(
            setOf(
                deliverableSubmissionModel1,
                deliverableSubmissionModel2,
                deliverableSubmissionModel3,
                deliverableSubmissionModel4,
                deliverableSubmissionModel5,
                deliverableSubmissionModel6),
            store.fetchApplicationDeliverables().toSet(),
            "Fetch application deliverables by application deliverableId")
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
          insertApplication(createdBy = otherUserId, status = ApplicationStatus.PassedPreScreen)
      val initial = applicationsDao.findAll().single()

      clock.instant = Instant.ofEpochSecond(30)

      store.restart(applicationId)

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.NotSubmitted,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant)),
          applicationsDao.findAll())

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  applicationStatusId = ApplicationStatus.NotSubmitted)),
          applicationHistoriesDao.findAll().map { it.copy(id = null) })
    }

    @Test
    fun `does nothing if application is already unsubmitted`() {
      val applicationId = insertApplication(status = ApplicationStatus.NotSubmitted)
      val initial = applicationsDao.findAll()

      clock.instant = Instant.ofEpochSecond(30)

      store.restart(applicationId)

      assertEquals(initial, applicationsDao.findAll())

      assertEquals(
          emptyList<ApplicationHistoriesRow>(),
          applicationHistoriesDao.findAll(),
          "Should not have inserted any history rows")
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
            status = ApplicationStatus.PLReview)
      }

      assertEquals(
          listOf(
              initial.copy(
                  applicationStatusId = ApplicationStatus.PLReview,
                  feedback = "feedback",
                  internalComment = "internal comment",
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          applicationsDao.findAll())

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  applicationStatusId = ApplicationStatus.PLReview,
                  internalComment = "internal comment",
                  feedback = "feedback")),
          applicationHistoriesDao.findAll().map { it.copy(id = null) })
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

      assertEquals(listOf(messages.applicationPreScreenFailureNoCountry()), result.problems)
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

    @Test
    fun `detects boundary size below minimum`() {
      val boundaries =
          mapOf(
              "Colombia" to point(-75, 3) to 3000,
              "Ghana" to point(-1.5, 7.25) to 3000,
              "Kenya" to point(37, 1) to 3000,
              "Tanzania" to point(34, -8) to 3000,
              "United States" to point(-100, 41) to 15000,
          )

      boundaries.forEach { (countryAndOrigin, minHectares) ->
        val (country, origin) = countryAndOrigin

        insertProject()
        val boundary = Turtle(origin).makePolygon { rectangle(10000, minHectares - 10) }
        val applicationId = insertApplication(boundary = boundary)

        val result = store.submit(applicationId, validVariables(boundary))

        assertEquals(
            listOf(messages.applicationPreScreenFailureBadSize(country, minHectares, 100000)),
            result.problems,
            country)
        assertEquals(ApplicationStatus.FailedPreScreen, result.application.status, country)
      }
    }

    @Test
    fun `detects boundary size above maximum`() {
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 120000) }
      val applicationId = insertApplication(boundary = boundary)

      val result = store.submit(applicationId, validVariables(boundary))

      assertEquals(
          listOf(messages.applicationPreScreenFailureBadSize("United States", 15000, 100000)),
          result.problems)
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects boundary in country that is ineligible for accelerator`() {
      val boundary = Turtle(point(-100, 51)).makePolygon { rectangle(10000, 16000) }
      val applicationId = insertApplication(boundary = boundary)

      val result = store.submit(applicationId, validVariables(boundary))

      assertEquals(
          listOf(messages.applicationPreScreenFailureIneligibleCountry("Canada")), result.problems)
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects mismatch between boundary size and total hectares across land use types`() {
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 50000) }
      val boundaryArea = boundary.calculateAreaHectares()
      val landUseTotal = boundaryArea / BigDecimal.TWO
      val applicationId = insertApplication(boundary = boundary)

      val result =
          store.submit(
              applicationId,
              PreScreenVariableValues(
                  landUseModelHectares =
                      mapOf(
                          LandUseModelType.Monoculture to BigDecimal.ZERO,
                          LandUseModelType.NativeForest to landUseTotal,
                      ),
                  numSpeciesToBePlanted = 500,
                  projectType = PreScreenProjectType.Mixed,
                  totalExpansionPotential = BigDecimal(5000)))

      assertEquals(
          listOf(
              messages.applicationPreScreenFailureLandUseTotalTooLow(
                  boundaryArea.toInt(), landUseTotal.toInt())),
          result.problems)
      assertEquals(ApplicationStatus.FailedPreScreen, result.application.status)
    }

    @Test
    fun `detects monoculture land use too high`() {
      val boundary = Turtle(point(-100, 41)).makePolygon { rectangle(10000, 50000) }
      val boundaryArea = boundary.calculateAreaHectares()
      val halfArea = boundaryArea / BigDecimal.TWO
      val applicationId = insertApplication(boundary = boundary)

      val result =
          store.submit(
              applicationId,
              PreScreenVariableValues(
                  landUseModelHectares =
                      mapOf(
                          LandUseModelType.Monoculture to halfArea,
                          LandUseModelType.NativeForest to halfArea,
                      ),
                  numSpeciesToBePlanted = 500,
                  projectType = PreScreenProjectType.Mixed,
                  totalExpansionPotential = BigDecimal(1000)))

      assertEquals(
          listOf(messages.applicationPreScreenFailureMonocultureTooHigh(10)), result.problems)
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
    fun `updates status and creates history entry`() {
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
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant)),
          applicationsDao.findAll())

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  boundary = initial.boundary,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
                  applicationStatusId = ApplicationStatus.PassedPreScreen)),
          applicationHistoriesDao.findAll().map { it.copy(id = null) })

      assertEquals(
          setOf(
              ApplicationModulesRow(
                  applicationId = applicationId,
                  moduleId = moduleId1,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete),
              ApplicationModulesRow(
                  applicationId = applicationId,
                  moduleId = moduleId2,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete),
          ),
          applicationModulesDao.findAll().toSet())
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

      assertEquals(
          setOf(
              ApplicationModulesRow(
                  applicationId = applicationId,
                  moduleId = moduleId1,
                  applicationModuleStatusId = ApplicationModuleStatus.Complete),
              ApplicationModulesRow(
                  applicationId = applicationId,
                  moduleId = moduleId2,
                  applicationModuleStatusId = ApplicationModuleStatus.Incomplete),
          ),
          applicationModulesDao.findAll().toSet())
    }

    @Test
    fun `does nothing if application is already submitted`() {
      val applicationId = insertApplication(status = ApplicationStatus.PassedPreScreen)
      val initial = applicationsDao.findAll()

      clock.instant = Instant.ofEpochSecond(30)

      store.submit(applicationId)

      assertEquals(initial, applicationsDao.findAll())

      assertEquals(
          emptyList<ApplicationHistoriesRow>(),
          applicationHistoriesDao.findAll(),
          "Should not have inserted any history rows")
    }

    @Test
    fun `throws exception if no permission`() {
      val applicationId = insertApplication()

      every { user.canUpdateApplicationSubmissionStatus(any()) } returns false

      assertThrows<AccessDeniedException> { store.submit(applicationId) }
    }

    private fun validVariables(boundary: Geometry): PreScreenVariableValues {
      val projectHectares = boundary.calculateAreaHectares()
      return PreScreenVariableValues(
          landUseModelHectares =
              mapOf(
                  LandUseModelType.NativeForest to projectHectares,
                  LandUseModelType.Monoculture to BigDecimal.ZERO),
          numSpeciesToBePlanted = 500,
          projectType = PreScreenProjectType.Terrestrial,
          totalExpansionPotential = BigDecimal(1500),
      )
    }
  }

  @Nested
  inner class UpdateBoundary {
    @Test
    fun `updates boundary and sets internal name if not already set`() {
      val otherUserId = insertUser()
      val applicationId = insertApplication(createdBy = otherUserId)
      val boundary = Turtle(point(0, 51)).makePolygon { rectangle(100, 100) }

      clock.instant = Instant.ofEpochSecond(30)

      store.updateBoundary(applicationId, boundary)

      val applicationRow = applicationsDao.findAll().single()
      assertEquals(
          ApplicationsRow(
              applicationStatusId = ApplicationStatus.NotSubmitted,
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "GBR_Organization 1",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              projectId = inserted.projectId,
          ),
          applicationRow.copy(boundary = null))

      // Produce a meaningful assertion failure message if the boundary isn't as expected within
      // the default floating-point inaccuracy tolerance.
      if (!boundary.equalsOrBothNull(applicationRow.boundary)) {
        assertEquals(boundary, applicationRow.boundary, "Boundary in applications row")
      }

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  applicationStatusId = ApplicationStatus.NotSubmitted,
                  boundary = applicationRow.boundary,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          applicationHistoriesDao.findAll().map { it.copy(id = null) })
    }

    @Test
    fun `updates boundary without overwriting existing internal name`() {
      val otherUserId = insertUser()
      val applicationId =
          insertApplication(boundary = rectangle(1), createdBy = otherUserId, internalName = "name")
      val boundary = Turtle(point(0, 51)).makePolygon { rectangle(100, 100) }

      clock.instant = Instant.ofEpochSecond(30)

      store.updateBoundary(applicationId, boundary)

      val applicationRow = applicationsDao.findAll().single()
      assertEquals(
          ApplicationsRow(
              applicationStatusId = ApplicationStatus.NotSubmitted,
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "name",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              projectId = inserted.projectId,
          ),
          applicationRow.copy(boundary = null))

      // Produce a meaningful assertion failure message if the boundary isn't as expected within
      // the default floating-point inaccuracy tolerance.
      if (!boundary.equalsOrBothNull(applicationRow.boundary)) {
        assertEquals(boundary, applicationRow.boundary, "Boundary in applications row")
      }

      assertEquals(
          listOf(
              ApplicationHistoriesRow(
                  applicationId = applicationId,
                  applicationStatusId = ApplicationStatus.NotSubmitted,
                  boundary = applicationRow.boundary,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          applicationHistoriesDao.findAll().map { it.copy(id = null) })
    }

    @Test
    fun `does not set internal name if boundary is not all in one country`() {
      val otherUserId = insertUser()
      val applicationId = insertApplication(createdBy = otherUserId)

      // Intersects France, Belgium, Netherlands
      val boundary = Turtle(point(3.5, 50)).makePolygon { rectangle(200000, 100000) }

      clock.instant = Instant.ofEpochSecond(30)

      store.updateBoundary(applicationId, boundary)

      val applicationRow = applicationsDao.findAll().single()
      assertEquals(
          ApplicationsRow(
              applicationStatusId = ApplicationStatus.NotSubmitted,
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              id = applicationId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              projectId = inserted.projectId,
          ),
          applicationRow.copy(boundary = null))

      // Produce a meaningful assertion failure message if the boundary isn't as expected within
      // the default floating-point inaccuracy tolerance.
      if (!boundary.equalsOrBothNull(applicationRow.boundary)) {
        assertEquals(boundary, applicationRow.boundary, "Boundary in applications row")
      }
    }

    @Test
    fun `throws exception if no permission`() {
      val applicationId = insertApplication()

      every { user.canUpdateApplicationBoundary(applicationId) } returns false

      assertThrows<AccessDeniedException> { store.updateBoundary(applicationId, rectangle(1)) }
    }
  }
}
