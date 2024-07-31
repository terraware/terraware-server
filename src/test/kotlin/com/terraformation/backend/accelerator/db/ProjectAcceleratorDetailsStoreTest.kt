package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectAcceleratorDetailsStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store by lazy { ProjectAcceleratorDetailsStore(clock, dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectAcceleratorDetails(any()) } returns true
    every { user.canUpdateProjectAcceleratorDetails(any()) } returns true
    every { user.canUpdateProjectDocumentSettings(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns all details fields`() {
      val projectId = insertProject(countryCode = "KE")
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Agroforestry)
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Mangroves)

      // To ensure that the fetchOne works as expected when there are multiple rows
      insertProject(countryCode = "ZW")

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
              investmentThesis = "thesis",
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
              projectLead = "lead",
              totalCarbon = BigDecimal(9),
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )

      assertEquals(
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
              investmentThesis = detailsRow.investmentThesis,
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = detailsRow.maxCarbonAccumulation,
              minCarbonAccumulation = detailsRow.minCarbonAccumulation,
              numCommunities = detailsRow.numCommunities,
              numNativeSpecies = detailsRow.numNativeSpecies,
              perHectareBudget = detailsRow.perHectareBudget,
              pipeline = detailsRow.pipelineId,
              projectId = projectId,
              projectLead = detailsRow.projectLead,
              region = Region.SubSaharanAfrica,
              totalCarbon = detailsRow.totalCarbon,
              totalExpansionPotential = detailsRow.totalExpansionPotential,
              whatNeedsToBeTrue = detailsRow.whatNeedsToBeTrue,
          ),
          store.fetchOneById(projectId))
    }

    @Test
    fun `returns empty details if project exists but no details have been saved yet`() {
      val projectId = insertProject(countryCode = "US")

      assertEquals(
          ProjectAcceleratorDetailsModel(
              countryCode = "US",
              projectId = projectId,
              region = Region.NorthAmerica,
          ),
          store.fetchOneById(projectId))
    }

    @Test
    fun `throws exception if no permission to read accelerator details`() {
      val projectId = insertProject()

      every { user.canReadProjectAcceleratorDetails(any()) } returns false

      assertThrows<AccessDeniedException> { store.fetchOneById(projectId) }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates details for project that has not previously had details saved`() {
      val projectId = insertProject()

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
              investmentThesis = "thesis",
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
              projectLead = "lead",
              totalCarbon = BigDecimal(9),
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )

      store.update(projectId) { updatedDetails }

      assertEquals(
          updatedDetails.copy(region = Region.EastAsiaPacific), store.fetchOneById(projectId))
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
          investmentThesis = "thesis",
          maxCarbonAccumulation = BigDecimal(5),
          minCarbonAccumulation = BigDecimal(4),
          numCommunities = 2,
          numNativeSpecies = 1,
          perHectareBudget = BigDecimal(6),
          pipeline = Pipeline.CarbonSupply,
          projectId = projectId,
          projectLead = "lead",
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
              investmentThesis = "new thesis",
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.Silvopasture),
              maxCarbonAccumulation = BigDecimal(50),
              minCarbonAccumulation = BigDecimal(40),
              numCommunities = 20,
              numNativeSpecies = 10,
              perHectareBudget = BigDecimal(60),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
              projectLead = "new lead",
              totalCarbon = BigDecimal(90),
              totalExpansionPotential = BigDecimal(30),
              whatNeedsToBeTrue = "new needs",
          )

      val otherDetails = store.fetchOneById(otherProjectId)

      store.update(projectId) { updatedDetails }

      assertEquals(
          updatedDetails.copy(region = Region.EastAsiaPacific),
          store.fetchOneById(projectId),
          "Should have updated project details")
      assertEquals(
          otherDetails,
          store.fetchOneById(otherProjectId),
          "Should not have updated details of other project")
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

      store.update(projectId) {
        it.copy(
            dropboxFolderPath = "/dropbox/new",
            fileNaming = "new naming",
            googleFolderUrl = URI("https://yahoo.com/"),
        )
      }

      assertEquals(
          originalRow.copy(fileNaming = "new naming"),
          projectAcceleratorDetailsDao.fetchOneByProjectId(projectId))
    }

    @Test
    fun `throws exception if no permission to update accelerator details`() {
      val projectId = insertProject()

      every { user.canUpdateProjectAcceleratorDetails(any()) } returns false

      assertThrows<AccessDeniedException> { store.update(projectId) { it } }
    }
  }
}
