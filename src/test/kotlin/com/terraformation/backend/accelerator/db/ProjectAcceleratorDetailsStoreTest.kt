package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ParticipantProjectFileNamingUpdatedEvent
import com.terraformation.backend.accelerator.model.MetricProgressModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import java.net.URI
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectAcceleratorDetailsStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store by lazy { ProjectAcceleratorDetailsStore(clock, dslContext, eventPublisher) }

  @BeforeEach
  fun setUp() {
    insertOrganization()

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectAcceleratorDetails(any()) } returns true
    every { user.canUpdateProjectAcceleratorDetails(any()) } returns true
    every { user.canReadProjectFunderDetails(any()) } returns true
    every { user.canUpdateProjectDocumentSettings(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns all details fields`() {
      insertCohort(name = "Cohort name", phase = CohortPhase.Phase0DueDiligence)
      insertParticipant(name = "Participant name", cohortId = inserted.cohortId)
      val projectId = insertProject(participantId = inserted.participantId)
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Agroforestry)
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Mangroves)

      insertProjectReportConfig()
      insertReport()
      insertPublishedReport()
      insertPublishedReportSystemMetric(metric = SystemMetric.HectaresPlanted, value = 100)
      insertPublishedReportSystemMetric(metric = SystemMetric.TreesPlanted, value = 10)
      insertPublishedReportSystemMetric(metric = SystemMetric.SeedsCollected, value = 1000)
      insertPublishedReportSystemMetric(metric = SystemMetric.SpeciesPlanted, value = 1)

      insertReport()
      insertPublishedReport()
      insertPublishedReportSystemMetric(metric = SystemMetric.HectaresPlanted, value = 200)
      insertPublishedReportSystemMetric(metric = SystemMetric.TreesPlanted, value = 20)
      insertPublishedReportSystemMetric(metric = SystemMetric.SeedsCollected, value = 2000)
      insertPublishedReportSystemMetric(metric = SystemMetric.SpeciesPlanted, value = 2)

      // To ensure that the fetchOne works as expected when there are multiple rows
      insertProject()

      val detailsRow =
          insertProjectAcceleratorDetails(
              annualCarbon = BigDecimal(7),
              applicationReforestableLand = BigDecimal(1),
              carbonCapacity = BigDecimal(8),
              confirmedReforestableLand = BigDecimal(2),
              dealDescription = "description",
              dealStage = DealStage.Phase0DocReview,
              dropboxFolderPath = "/dropbox/path",
              failureRisk = "failure",
              fileNaming = "naming",
              googleFolderUrl = "https://google.com/",
              hubSpotUrl = "https://hubspot.com/",
              investmentThesis = "thesis",
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipeline = Pipeline.AcceleratorProjects,
              plantingSitesCql = "tf_accelerator:fid=123",
              projectBoundariesCql = "project_no=5",
              projectId = projectId,
              totalCarbon = BigDecimal(9),
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )

      val variableValues =
          ProjectAcceleratorVariableValuesModel(
              annualCarbon = BigDecimal(7),
              applicationReforestableLand = BigDecimal(1),
              carbonCapacity = BigDecimal(8),
              countryCode = "KE",
              confirmedReforestableLand = BigDecimal(2),
              dealDescription = "description",
              dealName = "project deal name",
              failureRisk = "failure",
              investmentThesis = "thesis",
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              totalCarbon = BigDecimal(9),
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )

      assertEquals(
          ProjectAcceleratorDetailsModel(
              annualCarbon = detailsRow.annualCarbon,
              applicationReforestableLand = detailsRow.applicationReforestableLand,
              carbonCapacity = detailsRow.carbonCapacity,
              cohortId = inserted.cohortId,
              cohortName = "Cohort name",
              cohortPhase = CohortPhase.Phase0DueDiligence,
              confirmedReforestableLand = detailsRow.confirmedReforestableLand,
              countryCode = "KE",
              dealDescription = detailsRow.dealDescription,
              dealName = "project deal name",
              dealStage = detailsRow.dealStageId,
              dropboxFolderPath = detailsRow.dropboxFolderPath,
              failureRisk = detailsRow.failureRisk,
              fileNaming = detailsRow.fileNaming,
              googleFolderUrl = detailsRow.googleFolderUrl,
              hubSpotUrl = detailsRow.hubspotUrl,
              investmentThesis = detailsRow.investmentThesis,
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = detailsRow.maxCarbonAccumulation,
              metricProgress =
                  listOf(
                      MetricProgressModel(metric = SystemMetric.TreesPlanted, 30),
                      // Species Planted utilitze max instead of sum
                      MetricProgressModel(metric = SystemMetric.SpeciesPlanted, 2),
                      MetricProgressModel(metric = SystemMetric.HectaresPlanted, 300),
                      // Seeds Collected and other metric progress is not tracked
                  ),
              minCarbonAccumulation = detailsRow.minCarbonAccumulation,
              numCommunities = detailsRow.numCommunities,
              numNativeSpecies = detailsRow.numNativeSpecies,
              participantId = inserted.participantId,
              participantName = "Participant name",
              perHectareBudget = detailsRow.perHectareBudget,
              pipeline = detailsRow.pipelineId,
              plantingSitesCql = "tf_accelerator:fid=123",
              projectBoundariesCql = "project_no=5",
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              totalCarbon = detailsRow.totalCarbon,
              totalExpansionPotential = detailsRow.totalExpansionPotential,
              whatNeedsToBeTrue = detailsRow.whatNeedsToBeTrue,
          ),
          store.fetchOneById(projectId, variableValues),
      )
    }

    @Test
    fun `returns empty details if project exists but no details have been saved yet`() {
      val projectId = insertProject()

      assertEquals(
          ProjectAcceleratorDetailsModel(
              projectId = projectId,
          ),
          store.fetchOneById(
              projectId,
              ProjectAcceleratorVariableValuesModel(projectId = projectId),
          ),
      )
    }

    @Test
    fun `throws exception if no permission to read accelerator details`() {
      val projectId = insertProject()

      every { user.canReadProjectAcceleratorDetails(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.fetchOneById(projectId, ProjectAcceleratorVariableValuesModel(projectId = projectId))
      }
    }
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns list of project details including empty details`() {
      val otherProject = insertProject()
      val projectWithDetails = insertProject()

      val variableValues =
          ProjectAcceleratorVariableValuesModel(
              projectId = projectWithDetails,
              dealName = "Project deal name",
          )

      assertSetEquals(
          setOf(
              ProjectAcceleratorDetailsModel(
                  projectId = otherProject,
              ),
              ProjectAcceleratorDetailsModel(
                  projectId = projectWithDetails,
                  dealName = "Project deal name",
              ),
          ),
          store
              .fetch(DSL.trueCondition()) {
                if (it == projectWithDetails) {
                  variableValues
                } else {
                  ProjectAcceleratorVariableValuesModel(projectId = it)
                }
              }
              .toSet(),
      )
    }

    @Test
    fun `filters by permission`() {
      val visibleProject = insertProject()
      val invisibleProject = insertProject()

      every { user.canReadProjectAcceleratorDetails(invisibleProject) } returns false

      assertEquals(
          listOf(ProjectAcceleratorDetailsModel(projectId = visibleProject)),
          store.fetch(DSL.trueCondition()) {
            ProjectAcceleratorVariableValuesModel(projectId = it)
          },
      )
    }

    @Test
    fun `filters by condition`() {
      val projectId = insertProject()
      insertProject()

      assertEquals(
          listOf(ProjectAcceleratorDetailsModel(projectId = projectId)),
          store.fetch(PROJECTS.ID.eq(projectId)) {
            ProjectAcceleratorVariableValuesModel(projectId = it)
          },
      )
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates details for project that has not previously had details saved`() {
      val projectId = insertProject()

      // empty values
      val existingValues = ProjectAcceleratorVariableValuesModel(projectId = projectId)

      val updatedDetails =
          ProjectAcceleratorDetailsModel(
              annualCarbon = BigDecimal(7),
              applicationReforestableLand = BigDecimal(1),
              carbonCapacity = BigDecimal(8),
              confirmedReforestableLand = BigDecimal(2),
              countryCode = "JP",
              dealDescription = "description",
              dealStage = DealStage.Phase0DocReview,
              dropboxFolderPath = "/dropbox",
              failureRisk = "failure",
              fileNaming = "naming",
              googleFolderUrl = URI("https://google.com/"),
              hubSpotUrl = URI("https://hubspot.com/"),
              investmentThesis = "thesis",
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
              region = Region.EastAsiaPacific,
              totalCarbon = BigDecimal(9),
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )

      val updatedValues = updatedDetails.toVariableValuesModel()

      store.update(projectId, existingValues) { updatedDetails }

      assertEquals(
          updatedDetails.copy(region = Region.EastAsiaPacific),
          store.fetchOneById(projectId, updatedValues),
      )
    }

    @Test
    fun `updates details for project with existing details`() {
      val projectId = insertProject(countryCode = "KE")
      val otherProjectId = insertProject(countryCode = "GB")
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Agroforestry)
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Mangroves)

      insertProjectAcceleratorDetails(
          applicationReforestableLand = BigDecimal(1),
          confirmedReforestableLand = BigDecimal(2),
          dealDescription = "description",
          dealStage = DealStage.Phase0DocReview,
          dropboxFolderPath = "/dropbox",
          failureRisk = "failure",
          fileNaming = "naming",
          googleFolderUrl = "https://google.com/",
          hubSpotUrl = "https://hubspot.com/",
          investmentThesis = "thesis",
          maxCarbonAccumulation = BigDecimal(5),
          minCarbonAccumulation = BigDecimal(4),
          numCommunities = 2,
          numNativeSpecies = 1,
          perHectareBudget = BigDecimal(6),
          pipeline = Pipeline.CarbonSupply,
          projectId = projectId,
          totalExpansionPotential = BigDecimal(3),
          whatNeedsToBeTrue = "needs",
      )

      val existingValues =
          ProjectAcceleratorVariableValuesModel(
              applicationReforestableLand = BigDecimal(1),
              confirmedReforestableLand = BigDecimal(2),
              dealDescription = "description",
              failureRisk = "failure",
              investmentThesis = "thesis",
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              projectId = projectId,
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )

      val updatedDetails =
          ProjectAcceleratorDetailsModel(
              annualCarbon = BigDecimal(70),
              applicationReforestableLand = BigDecimal(10),
              carbonCapacity = BigDecimal(80),
              confirmedReforestableLand = BigDecimal(20),
              countryCode = "JP",
              dealDescription = "new description",
              dealStage = DealStage.Phase1,
              dropboxFolderPath = "/dropbox/new",
              failureRisk = "new failure",
              fileNaming = "new naming",
              googleFolderUrl = URI("https://google.com/new"),
              hubSpotUrl = URI("https://hubspot.com/new"),
              investmentThesis = "new thesis",
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.Silvopasture),
              maxCarbonAccumulation = BigDecimal(50),
              minCarbonAccumulation = BigDecimal(40),
              numCommunities = 20,
              numNativeSpecies = 10,
              perHectareBudget = BigDecimal(60),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
              region = Region.EastAsiaPacific,
              totalCarbon = BigDecimal(90),
              totalExpansionPotential = BigDecimal(30),
              whatNeedsToBeTrue = "new needs",
          )

      val updatedValues = updatedDetails.toVariableValuesModel()

      val otherDetails =
          store.fetchOneById(
              otherProjectId,
              ProjectAcceleratorVariableValuesModel(projectId = otherProjectId),
          )

      store.update(projectId, existingValues) { updatedDetails }

      assertEquals(
          updatedDetails.copy(region = Region.EastAsiaPacific),
          store.fetchOneById(projectId, updatedValues),
          "Should have updated project details",
      )
      assertEquals(
          otherDetails,
          store.fetchOneById(
              otherProjectId,
              ProjectAcceleratorVariableValuesModel(projectId = otherProjectId),
          ),
          "Should not have updated details of other project",
      )
    }

    @Test
    fun `publishes event if fileNaming is updated`() {
      val projectId = insertProject(countryCode = "KE")
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Agroforestry)
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Mangroves)

      val detailsRow =
          insertProjectAcceleratorDetails(
              annualCarbon = BigDecimal(7),
              applicationReforestableLand = BigDecimal(1),
              carbonCapacity = BigDecimal(8),
              confirmedReforestableLand = BigDecimal(2),
              dealDescription = "description",
              dealStage = DealStage.Phase0DocReview,
              dropboxFolderPath = "/dropbox/path",
              failureRisk = "failure",
              fileNaming = "naming",
              googleFolderUrl = "https://google.com/",
              hubSpotUrl = "https://hubspot.com/",
              investmentThesis = "thesis",
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
              totalCarbon = BigDecimal(9),
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )

      val existing =
          ProjectAcceleratorDetailsModel(
              annualCarbon = detailsRow.annualCarbon,
              applicationReforestableLand = detailsRow.applicationReforestableLand,
              carbonCapacity = detailsRow.carbonCapacity,
              confirmedReforestableLand = detailsRow.confirmedReforestableLand,
              countryCode = "KE",
              dealDescription = detailsRow.dealDescription,
              dealStage = detailsRow.dealStageId,
              dropboxFolderPath = detailsRow.dropboxFolderPath,
              failureRisk = detailsRow.failureRisk,
              fileNaming = detailsRow.fileNaming,
              googleFolderUrl = detailsRow.googleFolderUrl,
              hubSpotUrl = detailsRow.hubspotUrl,
              investmentThesis = detailsRow.investmentThesis,
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = detailsRow.maxCarbonAccumulation,
              minCarbonAccumulation = detailsRow.minCarbonAccumulation,
              numCommunities = detailsRow.numCommunities,
              numNativeSpecies = detailsRow.numNativeSpecies,
              perHectareBudget = detailsRow.perHectareBudget,
              pipeline = detailsRow.pipelineId,
              projectId = projectId,
              region = Region.SubSaharanAfrica,
              totalCarbon = detailsRow.totalCarbon,
              totalExpansionPotential = detailsRow.totalExpansionPotential,
              whatNeedsToBeTrue = detailsRow.whatNeedsToBeTrue,
          )

      val existingValues = existing.toVariableValuesModel()

      store.update(projectId, existingValues) {
        existing.copy(maxCarbonAccumulation = BigDecimal(50))
      }
      eventPublisher.assertEventNotPublished<ParticipantProjectFileNamingUpdatedEvent>(
          "File naming not updated"
      )

      store.update(projectId, existingValues) { existing.copy(fileNaming = "new naming") }
      eventPublisher.assertEventPublished(
          ParticipantProjectFileNamingUpdatedEvent(projectId),
          "File naming updated",
      )
    }

    @Test
    fun `does not update document storage fields if user does not have permission`() {
      every { user.canUpdateProjectDocumentSettings(any()) } returns false

      val projectId = insertProject()
      val originalRow =
          insertProjectAcceleratorDetails(
              dropboxFolderPath = "/dropbox",
              fileNaming = "naming",
              googleFolderUrl = "https://google.com/",
              projectId = projectId,
          )

      store.update(projectId, ProjectAcceleratorVariableValuesModel(projectId = projectId)) {
        it.copy(
            dropboxFolderPath = "/dropbox/new",
            fileNaming = "new naming",
            googleFolderUrl = URI("https://yahoo.com/"),
        )
      }

      assertEquals(
          originalRow.copy(fileNaming = "new naming"),
          projectAcceleratorDetailsDao.fetchOneByProjectId(projectId),
      )
    }

    @Test
    fun `throws exception if no permission to update accelerator details`() {
      val projectId = insertProject()

      every { user.canUpdateProjectAcceleratorDetails(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.update(projectId, ProjectAcceleratorVariableValuesModel(projectId = projectId)) { it }
      }
    }
  }
}
