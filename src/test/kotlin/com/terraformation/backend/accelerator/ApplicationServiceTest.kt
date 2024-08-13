package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val applicationStore = mockk<ApplicationStore>()
  private val clock = TestClock()
  private val countryDetector = mockk<CountryDetector>()
  private val preScreenVariableValuesFetcher = mockk<PreScreenVariableValuesFetcher>()
  private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore by lazy {
    ProjectAcceleratorDetailsStore(clock, dslContext)
  }
  private val service: ApplicationService by lazy {
    ApplicationService(
        applicationStore,
        countriesDao,
        countryDetector,
        defaultProjectLeadsDao,
        preScreenVariableValuesFetcher,
        projectAcceleratorDetailsStore,
        SystemUser(usersDao),
    )
  }

  // This is only returned by the mock ApplicationStore, not inserted into the database.
  private val applicationId = ApplicationId(1)

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    projectId = insertProject()

    every { user.canReadProject(any()) } returns true
    every { user.canUpdateApplicationSubmissionStatus(any()) } returns true
  }

  @Nested
  inner class Submit {
    @Test
    fun `fetches variable values for pre-screen submissions`() {
      val preScreenVariableValues =
          PreScreenVariableValues(
              landUseModelHectares = mapOf(LandUseModelType.Mangroves to BigDecimal(10)),
              numSpeciesToBePlanted = 123,
              projectType = PreScreenProjectType.Terrestrial,
              totalExpansionPotential = BigDecimal(1000))
      val applicationModel =
          ExistingApplicationModel(
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "XXX",
              modifiedTme = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = "Organization 1",
              status = ApplicationStatus.NotSubmitted)
      val submissionResult = ApplicationSubmissionResult(applicationModel, listOf("error"))

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any()) } returns submissionResult
      every { preScreenVariableValuesFetcher.fetchValues(projectId) } returns
          preScreenVariableValues

      assertEquals(submissionResult, service.submit(applicationId))

      verify(exactly = 1) { applicationStore.submit(applicationId, preScreenVariableValues) }
    }

    @Test
    fun `does not fetch variable values for full application submissions`() {
      val applicationModel =
          ExistingApplicationModel(
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "XXX",
              modifiedTme = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = "Organization 1",
              status = ApplicationStatus.PassedPreScreen)
      val submissionResult = ApplicationSubmissionResult(applicationModel, listOf("error"))

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any()) } returns submissionResult

      assertEquals(submissionResult, service.submit(applicationId))

      verify(exactly = 1) { applicationStore.submit(applicationId, null) }
    }

    @Test
    fun `populates project accelerator details on pre-screen success`() {
      val projectLead = "Johnny Appleseed"
      val internalName = "KEN_Project 1"
      val totalExpansionPotential = BigDecimal(1000)

      val projectId = insertProject()
      insertDefaultProjectLead(Region.SubSaharanAfrica, projectLead)

      val preScreenVariableValues =
          PreScreenVariableValues(
              landUseModelHectares =
                  mapOf(
                      LandUseModelType.Agroforestry to BigDecimal.ZERO,
                      LandUseModelType.Mangroves to BigDecimal(1),
                      LandUseModelType.NativeForest to BigDecimal(100),
                  ),
              numSpeciesToBePlanted = 50,
              projectType = PreScreenProjectType.Mixed,
              totalExpansionPotential = totalExpansionPotential,
          )
      val applicationModel =
          ExistingApplicationModel(
              boundary = Turtle(point(20, 0)).makePolygon { rectangle(1000, 1000) },
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = internalName,
              modifiedTme = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = "Organization 1",
              status = ApplicationStatus.NotSubmitted,
          )

      val submissionResult = ApplicationSubmissionResult(applicationModel, emptyList())

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any()) } returns submissionResult
      every { countryDetector.getCountries(any()) } returns setOf("KE")
      every { preScreenVariableValuesFetcher.fetchValues(projectId) } returns
          preScreenVariableValues

      assertEquals(submissionResult, service.submit(applicationId))

      // Allow the assertion to call ProjectAcceleratorDetailsStore.fetchOneById
      every { user.canReadProjectAcceleratorDetails(projectId) } returns true

      assertEquals(
          ProjectAcceleratorDetailsModel(
              applicationReforestableLand = BigDecimal("100.0"),
              countryCode = "KE",
              fileNaming = internalName,
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.NativeForest),
              numNativeSpecies = 50,
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              projectLead = projectLead,
              totalExpansionPotential = totalExpansionPotential,
          ),
          projectAcceleratorDetailsStore.fetchOneById(projectId),
          "Project accelerator details after submission")
    }
  }
}
