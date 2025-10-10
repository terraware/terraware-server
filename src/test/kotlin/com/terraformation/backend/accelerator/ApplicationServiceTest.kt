package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.accelerator.variables.ApplicationVariableValuesService
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val applicationStore = mockk<ApplicationStore>()
  private val applicationVariableValuesService = mockk<ApplicationVariableValuesService>()
  private val acceleratorProjectVariableValuesService =
      mockk<AcceleratorProjectVariableValuesService>()
  private val clock = TestClock()
  private val config = mockk<TerrawareServerConfig>()
  private val eventPublisher = TestEventPublisher()
  private val preScreenBoundarySubmissionFetcher = mockk<PreScreenBoundarySubmissionFetcher>()
  private val hubSpotService = mockk<HubSpotService>()
  private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService by lazy {
    ProjectAcceleratorDetailsService(
        acceleratorProjectVariableValuesService,
        ProjectAcceleratorDetailsStore(
            clock,
            dslContext,
            eventPublisher,
        ),
    )
  }
  private val service: ApplicationService by lazy {
    ApplicationService(
        applicationStore,
        applicationVariableValuesService,
        config,
        TestSingletons.countryDetector,
        hubSpotService,
        preScreenBoundarySubmissionFetcher,
        projectAcceleratorDetailsService,
        SystemUser(usersDao),
    )
  }

  // This is only returned by the mock ApplicationStore, not inserted into the database.
  private val applicationId = ApplicationId(1)

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  private val boundarySubmission = mockk<DeliverableSubmissionModel>()

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    projectId = insertProject(countryCode = "KE")

    every { user.canReadProject(any()) } returns true
    every { user.canUpdateApplicationSubmissionStatus(any()) } returns true
  }

  @Nested
  inner class Submit {
    @Test
    fun `fetches variable values for pre-screen submissions`() {
      val applicationVariableValues =
          ApplicationVariableValues(
              countryCode = null,
              landUseModelHectares = mapOf(LandUseModelType.Mangroves to BigDecimal(10)),
              numSpeciesToBePlanted = 123,
              projectType = PreScreenProjectType.Terrestrial,
              totalExpansionPotential = BigDecimal(1000),
          )
      val applicationModel =
          ExistingApplicationModel(
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "XXX",
              modifiedTime = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = "Organization 1",
              status = ApplicationStatus.NotSubmitted,
          )
      val submissionResult = ApplicationSubmissionResult(applicationModel, listOf("error"))

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any(), any()) } returns submissionResult
      every { applicationVariableValuesService.fetchValues(projectId) } returns
          applicationVariableValues
      every { preScreenBoundarySubmissionFetcher.fetchSubmission(projectId) } returns
          boundarySubmission

      assertEquals(submissionResult, service.submit(applicationId))

      verify(exactly = 1) {
        applicationStore.submit(applicationId, applicationVariableValues, boundarySubmission)
      }
    }

    @Test
    fun `creates HubSpot objects for full application submissions`() {
      val applicationReforestableLand = BigDecimal("100.0")
      val contactEmail = "a@b.com"
      val contactName = "John Smith"
      val internalName = "XXX_Test"
      val organizationName = "Organization 1"
      val website = "https://b.com/"
      val dealUrl = URI("https://example")

      val applicationModel =
          ExistingApplicationModel(
              countryCode = "KE",
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = internalName,
              modifiedTime = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = organizationName,
              status = ApplicationStatus.PassedPreScreen,
          )
      val applicationVariableValues =
          ApplicationVariableValues(
              contactEmail = contactEmail,
              contactName = contactName,
              countryCode = "KE",
              landUseModelHectares = emptyMap(),
              numSpeciesToBePlanted = 50,
              projectType = PreScreenProjectType.Mixed,
              totalExpansionPotential = BigDecimal.ONE,
              website = website,
          )
      val acceleratorVariableValues =
          ProjectAcceleratorVariableValuesModel(
              applicationReforestableLand = applicationReforestableLand,
              projectId = projectId,
          )
      val hubSpotConfig =
          TerrawareServerConfig.HubSpotConfig(clientId = "", clientSecret = "", enabled = true)
      val submissionResult =
          ApplicationSubmissionResult(
              applicationModel.copy(status = ApplicationStatus.Submitted),
              emptyList(),
          )

      every { config.hubSpot } returns hubSpotConfig
      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any()) } returns submissionResult
      every { applicationVariableValuesService.fetchValues(projectId) } returns
          applicationVariableValues
      every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns
          acceleratorVariableValues
      every {
        hubSpotService.createApplicationObjects(any(), any(), any(), any(), any(), any(), any())
      } returns dealUrl
      every { user.canReadProject(projectId) } returns true

      insertProjectAcceleratorDetails(
          applicationReforestableLand = applicationReforestableLand,
          fileNaming = internalName,
          projectId = projectId,
      )

      assertEquals(submissionResult, service.submit(applicationId))

      assertEquals(
          dealUrl,
          projectAcceleratorDetailsDao.fetchOneByProjectId(projectId)?.hubspotUrl,
          "HubSpot URL in project accelerator details",
      )

      verify(exactly = 1) { applicationStore.submit(applicationId, null, null) }

      verify(exactly = 1) {
        hubSpotService.createApplicationObjects(
            applicationReforestableLand = applicationReforestableLand,
            companyName = organizationName,
            contactEmail = contactEmail,
            contactName = contactName,
            countryCode = "KE",
            dealName = internalName,
            website = website,
        )
      }
    }

    @Test
    fun `populates project accelerator details with boundary area size on pre-screen success`() {
      val internalName = "KEN_Project 1"
      val totalExpansionPotential = BigDecimal(1000)

      val projectId = insertProject()

      val applicationVariableValues =
          ApplicationVariableValues(
              countryCode = "KE",
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
              modifiedTime = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = "Organization 1",
              status = ApplicationStatus.NotSubmitted,
          )

      val submissionResult = ApplicationSubmissionResult(applicationModel, emptyList())

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any(), any()) } returns submissionResult
      every { applicationVariableValuesService.fetchValues(projectId) } returns
          applicationVariableValues
      every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns
          ProjectAcceleratorVariableValuesModel(projectId = projectId)
      every { acceleratorProjectVariableValuesService.writeValues(projectId, any()) } returns Unit
      every { preScreenBoundarySubmissionFetcher.fetchSubmission(projectId) } returns
          boundarySubmission

      assertEquals(submissionResult, service.submit(applicationId))

      // Allow the assertion to call ProjectAcceleratorDetailsStore.fetchOneById
      every { user.canReadProjectAcceleratorDetails(projectId) } returns true

      val updatedVariableValues =
          ProjectAcceleratorVariableValuesModel(
              applicationReforestableLand = BigDecimal("100.0"),
              countryCode = "KE",
              dealName = internalName,
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.NativeForest),
              numNativeSpecies = 50,
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              totalExpansionPotential = totalExpansionPotential,
          )

      // Verify that updates are written to variables
      verify(exactly = 1) {
        acceleratorProjectVariableValuesService.writeValues(
            projectId,
            updatedVariableValues.copy(region = null),
        )
      }

      // After variable writes, service should return updated variables
      every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns
          updatedVariableValues.copy(region = Region.SubSaharanAfrica)

      assertEquals(
          ProjectAcceleratorDetailsModel(
              applicationReforestableLand = BigDecimal("100.0"),
              countryCode = "KE",
              dealName = internalName,
              fileNaming = internalName,
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.NativeForest),
              numNativeSpecies = 50,
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              totalExpansionPotential = totalExpansionPotential,
          ),
          projectAcceleratorDetailsService.fetchOneById(projectId),
          "Project accelerator details after submission",
      )
    }

    @Test
    fun `populates project accelerator details with total land use on pre-screen success if no boundary`() {
      val internalName = "KEN_Project 1"
      val totalExpansionPotential = BigDecimal(1000)

      val projectId = insertProject()

      val applicationVariableValues =
          ApplicationVariableValues(
              countryCode = "KE",
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
              boundary = null,
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = internalName,
              modifiedTime = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = "Organization 1",
              status = ApplicationStatus.NotSubmitted,
          )

      val submissionResult = ApplicationSubmissionResult(applicationModel, emptyList())

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.submit(applicationId, any(), any()) } returns submissionResult
      every { applicationVariableValuesService.fetchValues(projectId) } returns
          applicationVariableValues
      every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns
          ProjectAcceleratorVariableValuesModel(projectId = projectId)
      every { acceleratorProjectVariableValuesService.writeValues(projectId, any()) } returns Unit
      every { preScreenBoundarySubmissionFetcher.fetchSubmission(projectId) } returns
          boundarySubmission

      assertEquals(submissionResult, service.submit(applicationId))

      // Allow the assertion to call ProjectAcceleratorDetailsStore.fetchOneById
      every { user.canReadProjectAcceleratorDetails(projectId) } returns true

      val updatedVariableValues =
          ProjectAcceleratorVariableValuesModel(
              applicationReforestableLand = BigDecimal("101"),
              countryCode = "KE",
              dealName = internalName,
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.NativeForest),
              numNativeSpecies = 50,
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              totalExpansionPotential = totalExpansionPotential,
          )

      // Verify that updates are written to variables
      verify(exactly = 1) {
        acceleratorProjectVariableValuesService.writeValues(
            projectId,
            updatedVariableValues.copy(region = null),
        )
      }

      // After variable writes, service should return updated variables
      every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns
          updatedVariableValues

      assertEquals(
          ProjectAcceleratorDetailsModel(
              applicationReforestableLand = BigDecimal("101"),
              countryCode = "KE",
              dealName = internalName,
              fileNaming = internalName,
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.NativeForest),
              numNativeSpecies = 50,
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              totalExpansionPotential = totalExpansionPotential,
          ),
          projectAcceleratorDetailsService.fetchOneById(projectId),
          "Project accelerator details after submission",
      )
    }
  }

  @Nested
  inner class UpdateBoundary {
    @BeforeEach
    fun setup() {
      val applicationModel =
          ExistingApplicationModel(
              createdTime = Instant.EPOCH,
              id = applicationId,
              internalName = "XXX",
              modifiedTime = null,
              projectId = projectId,
              projectName = "Project Name",
              organizationId = organizationId,
              organizationName = "Organization 1",
              status = ApplicationStatus.NotSubmitted,
          )

      every { applicationStore.fetchOneById(applicationId) } returns applicationModel
      every { applicationStore.updateBoundary(applicationId, any()) } returns Unit
      every { applicationStore.updateCountryCode(applicationId, any()) } returns Unit

      every { applicationVariableValuesService.updateCountryVariable(projectId, any()) } returns
          Unit
    }

    @Test
    fun `sets boundary and updates country column and variable if all in one country`() {
      val boundary = Turtle(point(0, 51)).makePolygon { rectangle(100, 100) }
      service.updateBoundary(applicationId, boundary)

      verify(exactly = 1) { applicationStore.updateBoundary(applicationId, boundary) }
      verify(exactly = 1) { applicationStore.updateCountryCode(applicationId, "GB") }
      verify(exactly = 1) {
        applicationVariableValuesService.updateCountryVariable(projectId, "GB")
      }
    }

    @Test
    fun `does not set internal name if boundary is not all in one country`() {
      // Intersects France, Belgium, Netherlands
      val boundary = Turtle(point(3.5, 50)).makePolygon { rectangle(200000, 100000) }
      service.updateBoundary(applicationId, boundary)

      verify(exactly = 1) { applicationStore.updateBoundary(applicationId, boundary) }
      verify(exactly = 0) { applicationStore.updateCountryCode(applicationId, any()) }
      verify(exactly = 0) {
        applicationVariableValuesService.updateCountryVariable(projectId, any())
      }
    }
  }
}
