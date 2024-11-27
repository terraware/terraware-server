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
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
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

  private lateinit var annualCarbonVariableId: VariableId
  private lateinit var applicationRestorableLandVariableId: VariableId
  private lateinit var carbonCapacityVariableId: VariableId
  private lateinit var confirmedRestorableLandVariableId: VariableId
  private lateinit var countryVariableId: VariableId
  private lateinit var dealDescriptionVariableId: VariableId
  private lateinit var dealNameVariableId: VariableId
  private lateinit var failureRiskVariableId: VariableId
  private lateinit var investmentThesisVariableId: VariableId
  private lateinit var landUseModelTypesVariableId: VariableId
  private lateinit var maxCarbonAccumulationVariableId: VariableId
  private lateinit var minCarbonAccumulationVariableId: VariableId
  private lateinit var numNativeSpeciesVariableId: VariableId
  private lateinit var perHectareBudgetVariableId: VariableId
  private lateinit var totalCarbonVariableId: VariableId
  private lateinit var totalExpansionPotentialVariableId: VariableId
  private lateinit var whatNeedsToBeTrueVariableId: VariableId

  private lateinit var brazilOptionId: VariableSelectOptionId
  private lateinit var chileOptionId: VariableSelectOptionId

  private lateinit var agroforestryOptionId: VariableSelectOptionId
  private lateinit var mangrovesOptionId: VariableSelectOptionId
  private lateinit var nativeForestOptionId: VariableSelectOptionId
  private lateinit var sustainableTimberOptionId: VariableSelectOptionId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()

    annualCarbonVariableId =
        insertNumberVariable(
            insertVariable(type = VariableType.Number, stableId = StableIds.annualCarbon.value))
    applicationRestorableLandVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number, stableId = StableIds.applicationRestorableLand.value))
    carbonCapacityVariableId =
        insertNumberVariable(
            insertVariable(type = VariableType.Number, stableId = StableIds.carbonCapacity.value))
    confirmedRestorableLandVariableId =
        insertNumberVariable(
            insertVariable(type = VariableType.Number, stableId = StableIds.tfRestorableLand.value))
    countryVariableId =
        insertSelectVariable(
            insertVariable(type = VariableType.Select, stableId = StableIds.country.value))

    brazilOptionId = insertSelectOption(inserted.variableId, "Brazil")
    chileOptionId = insertSelectOption(inserted.variableId, "Chile")
    insertSelectOption(inserted.variableId, "Ghana")

    dealDescriptionVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.dealDescription.value))
    dealNameVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.dealName.value))
    failureRiskVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.failureRisk.value))
    investmentThesisVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.investmentThesis.value))

    landUseModelTypesVariableId =
        insertSelectVariable(
            insertVariable(type = VariableType.Select, stableId = StableIds.landUseModelType.value))
    agroforestryOptionId = insertSelectOption(inserted.variableId, "Agroforestry")
    mangrovesOptionId = insertSelectOption(inserted.variableId, "Mangroves")
    nativeForestOptionId = insertSelectOption(inserted.variableId, "Native Forest")
    sustainableTimberOptionId = insertSelectOption(inserted.variableId, "Sustainable Timber")

    maxCarbonAccumulationVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number, stableId = StableIds.maxCarbonAccumulation.value))
    minCarbonAccumulationVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number, stableId = StableIds.minCarbonAccumulation.value))
    numNativeSpeciesVariableId =
        insertNumberVariable(
            insertVariable(type = VariableType.Number, stableId = StableIds.numSpecies.value))
    perHectareBudgetVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number, stableId = StableIds.perHectareEstimatedBudget.value))
    totalCarbonVariableId =
        insertNumberVariable(
            insertVariable(type = VariableType.Number, stableId = StableIds.totalCarbon.value))
    totalExpansionPotentialVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number, stableId = StableIds.totalExpansionPotential.value))
    whatNeedsToBeTrueVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.whatNeedsToBeTrue.value))

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
      insertSelectValue(countryVariableId, optionIds = setOf(brazilOptionId))

      // Multi-select
      insertSelectValue(
          landUseModelTypesVariableId, optionIds = setOf(agroforestryOptionId, mangrovesOptionId))

      // Number value
      insertValue(minCarbonAccumulationVariableId, numberValue = BigDecimal.ONE)

      // Text value
      insertValue(dealDescriptionVariableId, textValue = "Deal description")

      // Second value should replace the first
      insertValue(maxCarbonAccumulationVariableId, numberValue = BigDecimal.TWO)
      insertValue(maxCarbonAccumulationVariableId, numberValue = BigDecimal.TEN)

      // Deleted values should appear as null
      insertValue(whatNeedsToBeTrueVariableId, textValue = "DELETED", isDeleted = true)

      assertEquals(
          ProjectAcceleratorVariableValuesModel(
              projectId = inserted.projectId,
              countryCode = "BR",
              dealDescription = "Deal description",
              landUseModelTypes = setOf(LandUseModelType.Agroforestry, LandUseModelType.Mangroves),
              maxCarbonAccumulation = BigDecimal.TEN,
              minCarbonAccumulation = BigDecimal.ONE,
              region = Region.LatinAmericaCaribbean,
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
      insertSelectValue(countryVariableId, optionIds = setOf(brazilOptionId))

      insertValue(maxCarbonAccumulationVariableId, numberValue = BigDecimal.TWO)
      insertValue(maxCarbonAccumulationVariableId, numberValue = BigDecimal.TEN)

      insertValue(minCarbonAccumulationVariableId, numberValue = BigDecimal.ONE)

      insertSelectValue(landUseModelTypesVariableId, optionIds = setOf(nativeForestOptionId))

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
      insertValue(maxCarbonAccumulationVariableId, numberValue = BigDecimal.TWO)
      insertValue(minCarbonAccumulationVariableId, numberValue = BigDecimal.ONE)

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
