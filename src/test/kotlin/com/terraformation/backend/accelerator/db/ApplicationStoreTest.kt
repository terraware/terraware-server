package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
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
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationHistoriesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationsRow
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
    insertOrganization(countryCode = "US")
    insertProject()

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

      org1ProjectId2 = insertProject(organizationId = organizationId)
      org1Project2ApplicationId =
          insertApplication(
              projectId = org1ProjectId2,
              boundary = rectangle(2),
              feedback = "feedback 2",
              internalComment = "internal comment 2",
              internalName = "internalName2",
              status = ApplicationStatus.PLReview,
          )

      organizationId2 = insertOrganization(2)
      org2ProjectId1 = insertProject(organizationId = organizationId2)
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
