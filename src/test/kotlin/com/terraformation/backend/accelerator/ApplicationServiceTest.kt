package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.accelerator.variables.ApplicationVariableValuesService
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val applicationStore by lazy {
    ApplicationStore(
        clock,
        countriesDao,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        messages,
    )
  }
  private val applicationVariableValuesService = mockk<ApplicationVariableValuesService>()
  private val acceleratorProjectVariableValuesService =
      mockk<AcceleratorProjectVariableValuesService>()
  private val config = mockk<TerrawareServerConfig>()
  private val eventPublisher = TestEventPublisher()
  private val messages = Messages()
  private val boundaryDeliverableId by lazy {
    insertModule(phase = AcceleratorPhase.PreScreen)
    insertDeliverable()
  }
  private val hubSpotService = mockk<HubSpotService>()
  private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService by lazy {
    ProjectAcceleratorDetailsService(
        acceleratorProjectVariableValuesService,
        ProjectAcceleratorDetailsStore(
            clock,
            dslContext,
            eventPublisher,
            ParentStore(dslContext),
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
        PreScreenBoundarySubmissionFetcher(applicationStore, boundaryDeliverableId),
        projectAcceleratorDetailsService,
        SystemUser(usersDao),
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization(name = "Organization 1")
    insertProject(countryCode = "KE")

    every { user.adminOrganizations() } returns setOf(inserted.organizationId)
    every { user.canReadApplication(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectDeliverables(any()) } returns true
    every { user.canUpdateApplicationBoundary(any()) } returns true
    every { user.canUpdateApplicationCountry(any()) } returns true
    every { user.canUpdateApplicationSubmissionStatus(any()) } returns true
  }

  @Nested
  inner class Submit {
    @Test
    fun `fetches variable values for pre-screen submissions`() {
      val applicationId = insertApplication()
      val deliverableId = boundaryDeliverableId
      insertApplicationModule()
      insertSubmission(deliverableId = deliverableId, submissionStatus = SubmissionStatus.Completed)

      every { applicationVariableValuesService.fetchValues(inserted.projectId) } returns
          ApplicationVariableValues(
              countryCode = "KE",
              landUseModelHectares = mapOf(LandUseModelType.NativeForest to BigDecimal(15000)),
              numSpeciesToBePlanted = 1,
              projectType = PreScreenProjectType.Terrestrial,
              totalExpansionPotential = BigDecimal(1000),
          )

      val initial = dslContext.fetch(APPLICATIONS).single()
      val problem = messages.applicationPreScreenFailureTooFewSpecies(10)
      assertEquals(listOf(problem), service.submit(applicationId).problems)

      assertTableEquals(
          initial.apply {
            applicationStatusId = ApplicationStatus.FailedPreScreen
            feedback = "<ul>\n<li>$problem</li>\n</ul>"
          },
      )
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

      val projectId = inserted.projectId
      val applicationId =
          insertApplication(
              countryCode = "KE",
              internalName = internalName,
              status = ApplicationStatus.PassedPreScreen,
          )
      val moduleId = insertModule(phase = AcceleratorPhase.Application)
      insertApplicationModule(applicationId, moduleId, ApplicationModuleStatus.Complete)

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

      every { config.hubSpot } returns hubSpotConfig
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

      service.submit(applicationId)
      assertEquals(
          ApplicationStatus.Submitted,
          applicationsDao.fetchOneById(applicationId)?.applicationStatusId,
          "Status",
      )

      assertEquals(
          dealUrl,
          projectAcceleratorDetailsDao.fetchOneByProjectId(projectId)?.hubspotUrl,
          "HubSpot URL in project accelerator details",
      )

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
      val internalName = "KEN_Organization 1"
      val totalExpansionPotential = BigDecimal(1000)

      val projectId = inserted.projectId
      val applicationId =
          insertApplication(boundary = Turtle(point(37, 0)).makePolygon { rectangle(1000, 1000) })

      val applicationVariableValues =
          ApplicationVariableValues(
              countryCode = "KE",
              landUseModelHectares =
                  mapOf(
                      LandUseModelType.Agroforestry to BigDecimal.ZERO,
                      LandUseModelType.Mangroves to BigDecimal(1),
                      LandUseModelType.NativeForest to BigDecimal(15000),
                  ),
              numSpeciesToBePlanted = 50,
              projectType = PreScreenProjectType.Mixed,
              totalExpansionPotential = totalExpansionPotential,
          )
      every { applicationVariableValuesService.fetchValues(projectId) } returns
          applicationVariableValues
      every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns
          ProjectAcceleratorVariableValuesModel(projectId = projectId)
      every { acceleratorProjectVariableValuesService.writeValues(projectId, any()) } returns Unit

      service.submit(applicationId)
      assertEquals(
          ApplicationStatus.PassedPreScreen,
          applicationsDao.fetchOneById(applicationId)?.applicationStatusId,
          "Status",
      )

      // Allow the assertion to call ProjectAcceleratorDetailsStore.fetchOneById
      every { user.canReadProjectAcceleratorDetails(projectId) } returns true

      val updatedVariableValues =
          ProjectAcceleratorVariableValuesModel(
              applicationReforestableLand = BigDecimal("100.000"),
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
              applicationReforestableLand = BigDecimal("100.000"),
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
      val internalName = "KEN_Organization 1"
      val totalExpansionPotential = BigDecimal(1000)

      val projectId = inserted.projectId
      val applicationId = insertApplication()
      val deliverableId = boundaryDeliverableId
      insertApplicationModule()
      insertSubmission(deliverableId = deliverableId, submissionStatus = SubmissionStatus.Completed)

      val applicationVariableValues =
          ApplicationVariableValues(
              countryCode = "KE",
              landUseModelHectares =
                  mapOf(
                      LandUseModelType.Agroforestry to BigDecimal.ZERO,
                      LandUseModelType.Mangroves to BigDecimal(1),
                      LandUseModelType.NativeForest to BigDecimal(15000),
                  ),
              numSpeciesToBePlanted = 50,
              projectType = PreScreenProjectType.Mixed,
              totalExpansionPotential = totalExpansionPotential,
          )
      every { applicationVariableValuesService.fetchValues(projectId) } returns
          applicationVariableValues
      every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns
          ProjectAcceleratorVariableValuesModel(projectId = projectId)
      every { acceleratorProjectVariableValuesService.writeValues(projectId, any()) } returns Unit

      service.submit(applicationId)
      assertEquals(
          ApplicationStatus.PassedPreScreen,
          applicationsDao.fetchOneById(applicationId)?.applicationStatusId,
          "Status",
      )

      // Allow the assertion to call ProjectAcceleratorDetailsStore.fetchOneById
      every { user.canReadProjectAcceleratorDetails(projectId) } returns true

      val updatedVariableValues =
          ProjectAcceleratorVariableValuesModel(
              applicationReforestableLand = BigDecimal("15001"),
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
              applicationReforestableLand = BigDecimal("15001"),
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
      insertApplication()
      every {
        applicationVariableValuesService.updateCountryVariable(inserted.projectId, any())
      } returns Unit
    }

    @Test
    fun `sets boundary and updates country column and variable if all in one country`() {
      val boundary = Turtle(point(0, 51)).makePolygon { rectangle(100, 100) }
      service.updateBoundary(inserted.applicationId, boundary)

      val application = applicationsDao.fetchOneById(inserted.applicationId)!!
      assertGeometryEquals(boundary, application.boundary, "Boundary")
      assertEquals("GB", application.countryCode, "Country code")
      verify(exactly = 1) {
        applicationVariableValuesService.updateCountryVariable(inserted.projectId, "GB")
      }
    }

    @Test
    fun `does not set internal name if boundary is not all in one country`() {
      // Intersects France, Belgium, Netherlands
      val boundary = Turtle(point(3.5, 50)).makePolygon { rectangle(200000, 100000) }
      service.updateBoundary(inserted.applicationId, boundary)

      val application = applicationsDao.fetchOneById(inserted.applicationId)!!
      assertGeometryEquals(boundary, application.boundary, "Boundary")
      assertNull(application.countryCode, "Country code")
      assertEquals("XXX", application.internalName, "Internal name")
      verify(exactly = 0) {
        applicationVariableValuesService.updateCountryVariable(inserted.projectId, any())
      }
    }
  }
}
