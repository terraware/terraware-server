package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.StableId
import com.terraformation.backend.documentproducer.model.StableIds
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

class AcceleratorProjectVariableValuesServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val service: AcceleratorProjectVariableValuesService by lazy {
    AcceleratorProjectVariableValuesService(
        countriesDao,
        VariableStore(
            dslContext,
            variableNumbersDao,
            variablesDao,
            variableSectionDefaultValuesDao,
            variableSectionRecommendationsDao,
            variableSectionsDao,
            variableSelectsDao,
            variableSelectOptionsDao,
            variableTablesDao,
            variableTableColumnsDao,
            variableTextsDao),
        VariableValueStore(
            TestClock(),
            dslContext,
            TestEventPublisher(),
            variableImageValuesDao,
            variableLinkValuesDao,
            variablesDao,
            variableSectionValuesDao,
            variableSelectOptionValuesDao,
            variableValuesDao,
            variableValueTableRowsDao),
        SystemUser(usersDao),
    )
  }

  private lateinit var brazilOptionId: VariableSelectOptionId
  private lateinit var chileOptionId: VariableSelectOptionId

  private lateinit var agroforestryOptionId: VariableSelectOptionId
  private lateinit var mangrovesOptionId: VariableSelectOptionId
  private lateinit var nativeForestOptionId: VariableSelectOptionId
  private lateinit var sustainableTimberOptionId: VariableSelectOptionId

  private lateinit var variableIdsByStableId: Map<StableId, VariableId>

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()

    variableIdsByStableId = setupStableIdVariables()

    brazilOptionId = insertSelectOption(variableIdsByStableId[StableIds.country]!!, "Brazil")
    chileOptionId = insertSelectOption(variableIdsByStableId[StableIds.country]!!, "Chile")
    insertSelectOption(variableIdsByStableId[StableIds.country]!!, "Ghana")

    agroforestryOptionId =
        insertSelectOption(variableIdsByStableId[StableIds.landUseModelType]!!, "Agroforestry")
    mangrovesOptionId =
        insertSelectOption(variableIdsByStableId[StableIds.landUseModelType]!!, "Mangroves")
    nativeForestOptionId =
        insertSelectOption(variableIdsByStableId[StableIds.landUseModelType]!!, "Native Forest")
    sustainableTimberOptionId =
        insertSelectOption(
            variableIdsByStableId[StableIds.landUseModelType]!!, "Sustainable Timber")

    every { user.canReadProjectAcceleratorDetails(any()) } returns true
    every { user.canUpdateProjectAcceleratorDetails(any()) } returns true
  }

  @Nested
  inner class FetchValues {
    @Test
    fun `returns null or empty values if variables not set`() {
      assertEquals(
          ProjectAcceleratorVariableValuesModel(projectId = inserted.projectId),
          service.fetchValues(inserted.projectId))
    }

    @Test
    fun `returns values for non-deleted variables`() {
      // Single-select, country code should populate region
      insertSelectValue(
          variableIdsByStableId[StableIds.country]!!, optionIds = setOf(brazilOptionId))

      // Multi-select (and corresponding map)
      insertSelectValue(
          variableIdsByStableId[StableIds.landUseModelType]!!,
          optionIds = setOf(agroforestryOptionId, mangrovesOptionId))
      insertValue(
          variableIdsByStableId[
              StableIds.landUseHectaresByLandUseModel[LandUseModelType.Agroforestry]]!!,
          numberValue = BigDecimal(10001))
      insertValue(
          variableIdsByStableId[
              StableIds.landUseHectaresByLandUseModel[LandUseModelType.Mangroves]]!!,
          numberValue = BigDecimal(20002))

      // Number value
      insertValue(
          variableIdsByStableId[StableIds.minCarbonAccumulation]!!, numberValue = BigDecimal.ONE)

      // Text value
      insertValue(
          variableIdsByStableId[StableIds.dealDescription]!!, textValue = "Deal description")

      // Link value
      insertLinkValue(
          variableIdsByStableId[StableIds.slackLink]!!,
          url = "https://example.com/AcceleratorProjectVariableValuesService")

      // Second value should replace the first
      insertValue(
          variableIdsByStableId[StableIds.maxCarbonAccumulation]!!, numberValue = BigDecimal.TWO)
      insertValue(
          variableIdsByStableId[StableIds.maxCarbonAccumulation]!!, numberValue = BigDecimal.TEN)

      // Deleted values should appear as null
      insertValue(
          variableIdsByStableId[StableIds.whatNeedsToBeTrue]!!,
          textValue = "DELETED",
          isDeleted = true)

      assertEquals(
          ProjectAcceleratorVariableValuesModel(
              projectId = inserted.projectId,
              countryCode = "BR",
              dealDescription = "Deal description",
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              landUseModelHectares =
                  mapOf(
                      LandUseModelType.Agroforestry to BigDecimal(10001),
                      LandUseModelType.Mangroves to BigDecimal(20002)),
              maxCarbonAccumulation = BigDecimal.TEN,
              minCarbonAccumulation = BigDecimal.ONE,
              region = Region.LatinAmericaCaribbean,
              slackLink = URI("https://example.com/AcceleratorProjectVariableValuesService"),
          ),
          service.fetchValues(inserted.projectId))
    }

    @Test
    fun `throws exception if no permission to read project accelerator details`() {
      every { user.canReadProjectAcceleratorDetails(any()) } returns false
      every { user.canReadProject(any()) } returns true

      assertThrows<AccessDeniedException> { service.fetchValues(inserted.projectId) }

      every { user.canReadProject(any()) } returns false
      assertThrows<ProjectNotFoundException> { service.fetchValues(inserted.projectId) }
    }
  }

  @Nested
  inner class WriteValues {

    @Test
    fun `can write all values`() {
      val values =
          ProjectAcceleratorVariableValuesModel(
              projectId = inserted.projectId,
              annualCarbon = BigDecimal(250),
              applicationReforestableLand = BigDecimal(100),
              carbonCapacity = BigDecimal(300),
              confirmedReforestableLand = BigDecimal(75),
              countryCode = "BR",
              dealDescription = "New deal description",
              failureRisk = "New failure risk",
              investmentThesis = "New investment thesis",
              landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.NativeForest),
              maxCarbonAccumulation = BigDecimal(1500),
              minCarbonAccumulation = BigDecimal(1000),
              numNativeSpecies = 10,
              perHectareBudget = BigDecimal(500),
              totalCarbon = BigDecimal(400),
              totalExpansionPotential = BigDecimal(700),
              whatNeedsToBeTrue = "New what needs to be true",
          )

      service.writeValues(inserted.projectId, values)

      assertEquals(
          // Region is extracted from country
          values.copy(region = Region.LatinAmericaCaribbean),
          service.fetchValues(inserted.projectId),
          "Fetch model after writing values",
      )
    }

    @Test
    fun `can add, update and remove values`() {
      insertSelectValue(
          variableIdsByStableId[StableIds.country]!!, optionIds = setOf(brazilOptionId))

      insertValue(
          variableIdsByStableId[StableIds.maxCarbonAccumulation]!!, numberValue = BigDecimal.TWO)
      insertValue(
          variableIdsByStableId[StableIds.maxCarbonAccumulation]!!, numberValue = BigDecimal.TEN)

      insertValue(
          variableIdsByStableId[StableIds.minCarbonAccumulation]!!, numberValue = BigDecimal.ONE)

      insertSelectValue(
          variableIdsByStableId[StableIds.landUseModelType]!!,
          optionIds = setOf(nativeForestOptionId))

      val existing = service.fetchValues(inserted.projectId)
      service.writeValues(
          inserted.projectId,
          existing.copy(
              countryCode = null,
              landUseModelTypes = setOf(LandUseModelType.Agroforestry),
              maxCarbonAccumulation = null,
              minCarbonAccumulation = BigDecimal(20),
              totalCarbon = BigDecimal(30),
          ))

      assertEquals(
          existing.copy(
              countryCode = null,
              landUseModelTypes = setOf(LandUseModelType.Agroforestry),
              maxCarbonAccumulation = null,
              minCarbonAccumulation = BigDecimal(20),
              region = null,
              totalCarbon = BigDecimal(30),
          ),
          service.fetchValues(inserted.projectId))
    }

    @Test
    fun `unchanged values will not write new records`() {
      insertValue(
          variableIdsByStableId[StableIds.maxCarbonAccumulation]!!, numberValue = BigDecimal.TWO)
      insertValue(
          variableIdsByStableId[StableIds.minCarbonAccumulation]!!, numberValue = BigDecimal.ONE)

      val existing = variableValuesDao.findAll()
      val model = service.fetchValues(inserted.projectId)
      service.writeValues(inserted.projectId, model)

      assertEquals(existing, variableValuesDao.findAll())
    }

    @Test
    fun `throws exception if no permission to read project accelerator details`() {
      every { user.canUpdateProjectAcceleratorDetails(any()) } returns false
      every { user.canReadProject(any()) } returns true

      assertThrows<AccessDeniedException> {
        service.writeValues(
            inserted.projectId,
            ProjectAcceleratorVariableValuesModel(projectId = inserted.projectId))
      }

      every { user.canReadProject(any()) } returns false
      assertThrows<ProjectNotFoundException> {
        service.writeValues(
            inserted.projectId,
            ProjectAcceleratorVariableValuesModel(projectId = inserted.projectId))
      }
    }
  }
}
