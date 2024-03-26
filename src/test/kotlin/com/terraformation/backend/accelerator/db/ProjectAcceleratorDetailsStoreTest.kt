package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectAcceleratorDetailsRow
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
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
    insertUser()
    insertOrganization()

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectAcceleratorDetails(any()) } returns true
    every { user.canUpdateProjectAcceleratorDetails(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns all details fields`() {
      val projectId = insertProject(countryCode = "KE")
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Agroforestry)
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Mangroves)

      val detailsRow =
          ProjectAcceleratorDetailsRow(
              applicationReforestableLand = BigDecimal(1),
              confirmedReforestableLand = BigDecimal(2),
              dealDescription = "description",
              dealStageId = DealStage.Phase0DocReview,
              failureRisk = "failure",
              investmentThesis = "thesis",
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipelineId = Pipeline.AcceleratorProjects,
              projectId = projectId,
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          )
      projectAcceleratorDetailsDao.insert(detailsRow)

      assertEquals(
          ProjectAcceleratorDetailsModel(
              applicationReforestableLand = detailsRow.applicationReforestableLand,
              confirmedReforestableLand = detailsRow.confirmedReforestableLand,
              countryCode = "KE",
              dealDescription = detailsRow.dealDescription,
              dealStage = detailsRow.dealStageId,
              failureRisk = detailsRow.failureRisk,
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
              applicationReforestableLand = BigDecimal(1),
              confirmedReforestableLand = BigDecimal(2),
              countryCode = "JP",
              dealDescription = "description",
              dealStage = DealStage.Phase0DocReview,
              failureRisk = "failure",
              investmentThesis = "thesis",
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
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
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Agroforestry)
      insertProjectLandUseModelType(landUseModelType = LandUseModelType.Mangroves)

      projectAcceleratorDetailsDao.insert(
          ProjectAcceleratorDetailsRow(
              applicationReforestableLand = BigDecimal(1),
              confirmedReforestableLand = BigDecimal(2),
              dealDescription = "description",
              dealStageId = DealStage.Phase0DocReview,
              failureRisk = "failure",
              investmentThesis = "thesis",
              maxCarbonAccumulation = BigDecimal(5),
              minCarbonAccumulation = BigDecimal(4),
              numCommunities = 2,
              numNativeSpecies = 1,
              perHectareBudget = BigDecimal(6),
              pipelineId = Pipeline.CarbonSupply,
              projectId = projectId,
              totalExpansionPotential = BigDecimal(3),
              whatNeedsToBeTrue = "needs",
          ))

      val updatedDetails =
          ProjectAcceleratorDetailsModel(
              applicationReforestableLand = BigDecimal(10),
              confirmedReforestableLand = BigDecimal(20),
              countryCode = "JP",
              dealDescription = "new description",
              dealStage = DealStage.Phase1,
              failureRisk = "new failure",
              investmentThesis = "new thesis",
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.Silvopasture),
              maxCarbonAccumulation = BigDecimal(50),
              minCarbonAccumulation = BigDecimal(40),
              numCommunities = 20,
              numNativeSpecies = 10,
              perHectareBudget = BigDecimal(60),
              pipeline = Pipeline.AcceleratorProjects,
              projectId = projectId,
              totalExpansionPotential = BigDecimal(30),
              whatNeedsToBeTrue = "new needs",
          )

      store.update(projectId) { updatedDetails }

      assertEquals(
          updatedDetails.copy(region = Region.EastAsiaPacific), store.fetchOneById(projectId))
    }

    @Test
    fun `throws exception if no permission to update accelerator details`() {
      val projectId = insertProject()

      every { user.canUpdateProjectAcceleratorDetails(any()) } returns false

      assertThrows<AccessDeniedException> { store.update(projectId) { it } }
    }
  }
}
