package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.ProjectPhaseService
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectFileNamingUpdatedEvent
import com.terraformation.backend.accelerator.model.MetricProgressModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.records.CohortsRecord
import com.terraformation.backend.db.accelerator.tables.records.ProjectAcceleratorDetailsRecord
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import java.net.URI
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
      val projectId =
          insertProject(cohortId = inserted.cohortId, phase = CohortPhase.Phase0DueDiligence)
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
              perHectareBudget = detailsRow.perHectareBudget,
              phase = CohortPhase.Phase0DueDiligence,
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
    @BeforeEach
    fun setUp() {
      eventPublisher.register<CohortPhaseUpdatedEvent> { ProjectPhaseService(dslContext).on(it) }
    }

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

    @Test
    fun `updates existing cohort phase when project phase changes`() {
      insertCohort(name = "Test Cohort", phase = CohortPhase.Phase0DueDiligence)
      val projectId =
          insertProject(cohortId = inserted.cohortId, phase = CohortPhase.Phase0DueDiligence)
      insertProject(cohortId = inserted.cohortId, phase = CohortPhase.Phase0DueDiligence)

      val existingValues =
          ProjectAcceleratorVariableValuesModel(
              projectId = projectId,
          )

      clock.instant = clock.instant.plusSeconds(100)

      store.update(projectId, existingValues) {
        it.copy(
            phase = CohortPhase.Phase1FeasibilityStudy,
            fileNaming = "test-naming",
            dropboxFolderPath = "/dropbox/test",
            googleFolderUrl = URI("https://drive.google.com/test"),
        )
      }

      assertEquals(
          listOf(CohortPhase.Phase1FeasibilityStudy, CohortPhase.Phase1FeasibilityStudy),
          projectsDao.findAll().map { it.phaseId },
          "Project phases",
      )

      assertTableEquals(
          CohortsRecord(
              name = "Test Cohort",
              phaseId = CohortPhase.Phase1FeasibilityStudy,
              createdBy = user.userId,
              createdTime = clock.instant.minusSeconds(100),
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `does not update cohort phase when project phase is unchanged`() {
      insertCohort(name = "Test Cohort", phase = CohortPhase.Phase0DueDiligence)
      val projectId = insertProject(cohortId = inserted.cohortId)

      val existingValues =
          ProjectAcceleratorVariableValuesModel(
              projectId = projectId,
          )

      store.update(projectId, existingValues) {
        it.copy(
            numCommunities = 5,
            phase = CohortPhase.Phase0DueDiligence,
            fileNaming = "test-naming",
            dropboxFolderPath = "/dropbox/test",
            googleFolderUrl = URI("https://drive.google.com/test"),
        )
      }

      assertTableEquals(
          CohortsRecord(
              name = "Test Cohort",
              phaseId = CohortPhase.Phase0DueDiligence,
              createdBy = currentUser().userId,
              createdTime = clock.instant,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant,
          ),
          "Cohort should remain unchanged",
      )
    }

    @Test
    fun `creates new cohort when project has no cohort and phase is set`() {
      val projectId = insertProject(name = "Test Project 1")

      val existingValues =
          ProjectAcceleratorVariableValuesModel(
              projectId = projectId,
          )

      store.update(projectId, existingValues) {
        it.copy(
            phase = CohortPhase.Phase1FeasibilityStudy,
            fileNaming = "test-naming",
            dropboxFolderPath = "/dropbox/test",
            googleFolderUrl = URI("https://drive.google.com/test"),
        )
      }

      val projectsRow = projectsDao.fetchOneById(projectId)!!
      assertNotNull(projectsRow.cohortId, "Project cohort ID")
      assertEquals(CohortPhase.Phase1FeasibilityStudy, projectsRow.phaseId, "Project phase")

      assertTableEquals(
          ProjectAcceleratorDetailsRecord(
              projectId = projectId,
              fileNaming = "test-naming",
              dropboxFolderPath = "/dropbox/test",
              googleFolderUrl = URI("https://drive.google.com/test"),
          )
      )

      assertTableEquals(
          CohortsRecord(
              name = "Test Project 1",
              phaseId = CohortPhase.Phase1FeasibilityStudy,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      val project = projectsDao.fetchOneById(projectId)
      assertNotNull(project?.cohortId, "Project should have cohort_id set")
    }

    @Test
    fun `clears cohort and deletes it with modules when phase is set to null and no other projects use it`() {
      insertCohort(name = "Test Cohort", phase = CohortPhase.Phase0DueDiligence)
      insertModule()
      insertCohortModule()
      val projectId =
          insertProject(cohortId = inserted.cohortId, phase = CohortPhase.Phase0DueDiligence)

      val existingValues =
          ProjectAcceleratorVariableValuesModel(
              projectId = projectId,
          )

      store.update(projectId, existingValues) {
        it.copy(
            phase = null,
            fileNaming = null,
            dropboxFolderPath = null,
            googleFolderUrl = null,
        )
      }

      val project = projectsDao.fetchOneById(projectId)
      assertNull(project!!.cohortId, "Project should have cohort_id cleared")
      assertNull(project.phaseId, "Project should have phase_id cleared")

      assertTableEmpty(COHORTS)
      assertTableEmpty(COHORT_MODULES)
    }

    @Test
    fun `clears cohort but keeps it when phase is set to null and other projects use it`() {
      insertCohort(name = "Shared Cohort", phase = CohortPhase.Phase0DueDiligence)
      val projectId1 =
          insertProject(
              name = "Project 1",
              cohortId = inserted.cohortId,
              phase = CohortPhase.Phase0DueDiligence,
          )
      val projectId2 =
          insertProject(
              name = "Project 2",
              cohortId = inserted.cohortId,
              phase = CohortPhase.Phase0DueDiligence,
          )

      val existingValues =
          ProjectAcceleratorVariableValuesModel(
              projectId = projectId1,
          )

      store.update(projectId1, existingValues) {
        it.copy(
            phase = null,
            fileNaming = null,
            dropboxFolderPath = null,
            googleFolderUrl = null,
        )
      }

      val project1 = projectsDao.fetchOneById(projectId1)
      assertNull(project1!!.cohortId, "Project 1 should have cohort_id cleared")
      assertNull(project1.phaseId, "Project 1 should have phase_id cleared")

      val project2 = projectsDao.fetchOneById(projectId2)
      assertEquals(inserted.cohortId, project2?.cohortId, "Project 2 should still have cohort_id")

      assertTableEquals(
          CohortsRecord(
              name = "Shared Cohort",
              phaseId = CohortPhase.Phase0DueDiligence,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          ),
          "Cohort should still exist when other projects reference it",
      )
    }

    @Test
    fun `throws exception when phase is set but fileNaming is null`() {
      val projectId = insertProject()

      val existingValues = ProjectAcceleratorVariableValuesModel(projectId = projectId)

      val exception =
          assertThrows<IllegalArgumentException> {
            store.update(projectId, existingValues) {
              it.copy(
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  fileNaming = null,
                  dropboxFolderPath = "/dropbox/test",
                  googleFolderUrl = URI("https://drive.google.com/test"),
              )
            }
          }

      assertEquals(
          "If phase is selected, file naming, dropbox folder path, and Google folder URL must be set.",
          exception.message,
      )
    }

    @Test
    fun `throws exception when phase is set but dropboxFolderPath is null`() {
      val projectId = insertProject()

      val existingValues = ProjectAcceleratorVariableValuesModel(projectId = projectId)

      val exception =
          assertThrows<IllegalArgumentException> {
            store.update(projectId, existingValues) {
              it.copy(
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  fileNaming = "test-naming",
                  dropboxFolderPath = null,
                  googleFolderUrl = URI("https://drive.google.com/test"),
              )
            }
          }

      assertEquals(
          "If phase is selected, file naming, dropbox folder path, and Google folder URL must be set.",
          exception.message,
      )
    }

    @Test
    fun `throws exception when phase is set but googleFolderUrl is null`() {
      val projectId = insertProject()

      val existingValues = ProjectAcceleratorVariableValuesModel(projectId = projectId)

      val exception =
          assertThrows<IllegalArgumentException> {
            store.update(projectId, existingValues) {
              it.copy(
                  phase = CohortPhase.Phase1FeasibilityStudy,
                  fileNaming = "test-naming",
                  dropboxFolderPath = "/dropbox/test",
                  googleFolderUrl = null,
              )
            }
          }

      assertEquals(
          "If phase is selected, file naming, dropbox folder path, and Google folder URL must be set.",
          exception.message,
      )
    }

    @Test
    fun `allows update when phase is null and document fields are null`() {
      val projectId = insertProject()

      val existingValues = ProjectAcceleratorVariableValuesModel(projectId = projectId)

      store.update(projectId, existingValues) {
        it.copy(
            phase = null,
            fileNaming = null,
            dropboxFolderPath = null,
            googleFolderUrl = null,
            numCommunities = 5,
        )
      }

      assertTableEquals(
          ProjectAcceleratorDetailsRecord(
              projectId = projectId,
              fileNaming = null,
              dropboxFolderPath = null,
              googleFolderUrl = null,
              numCommunities = 5,
          )
      )
    }

    @Test
    fun `allows update when phase and all required document fields are set`() {
      val projectId = insertProject()

      val existingValues = ProjectAcceleratorVariableValuesModel(projectId = projectId)

      store.update(projectId, existingValues) {
        it.copy(
            phase = CohortPhase.Phase1FeasibilityStudy,
            fileNaming = "test-naming",
            dropboxFolderPath = "/dropbox/test",
            googleFolderUrl = URI("https://drive.google.com/test"),
        )
      }

      assertTableEquals(
          ProjectAcceleratorDetailsRecord(
              projectId = projectId,
              fileNaming = "test-naming",
              dropboxFolderPath = "/dropbox/test",
              googleFolderUrl = URI("https://drive.google.com/test"),
          )
      )
    }
  }
}
