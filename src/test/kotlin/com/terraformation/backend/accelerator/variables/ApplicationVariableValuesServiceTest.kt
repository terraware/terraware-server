package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.StableIds
import com.terraformation.backend.mockUser
import io.mockk.every
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ApplicationVariableValuesServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val service: ApplicationVariableValuesService by lazy {
    ApplicationVariableValuesService(
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
            variableTextsDao,
        ),
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
            variableValueTableRowsDao,
        ),
        SystemUser(usersDao),
    )
  }

  private lateinit var brazilOptionId: VariableSelectOptionId
  private lateinit var chileOptionId: VariableSelectOptionId

  private lateinit var terrestrialOptionId: VariableSelectOptionId

  private lateinit var variableIdsByStableId: Map<StableId, VariableId>

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertModule(phase = CohortPhase.PreScreen)

    variableIdsByStableId = setupStableIdVariables()

    brazilOptionId = insertSelectOption(variableIdsByStableId[StableIds.country]!!, "Brazil")
    chileOptionId = insertSelectOption(variableIdsByStableId[StableIds.country]!!, "Chile")
    insertSelectOption(variableIdsByStableId[StableIds.country]!!, "Ghana")

    terrestrialOptionId =
        insertSelectOption(variableIdsByStableId[StableIds.projectType]!!, "Terrestrial")
    insertSelectOption(variableIdsByStableId[StableIds.projectType]!!, "Mangrove")
    insertSelectOption(variableIdsByStableId[StableIds.projectType]!!, "Mixed")

    every { user.canReadProjectDeliverables(any()) } returns true
  }

  @Nested
  inner class FetchValues {
    @Test
    fun `returns null or empty values if variables not set`() {
      assertEquals(
          ApplicationVariableValues(null, null, null, null, emptyMap(), null, null, null, null),
          service.fetchValues(inserted.projectId),
      )
    }

    @Test
    fun `fetches values for all variables`() {
      insertValue(
          variableId = variableIdsByStableId[StableIds.contactEmail]!!,
          textValue = "a@b.com",
      )
      insertValue(
          variableId = variableIdsByStableId[StableIds.contactName]!!,
          textValue = "John Smith",
      )
      insertSelectValue(
          variableId = variableIdsByStableId[StableIds.country]!!,
          optionIds = setOf(brazilOptionId),
      )
      insertValue(
          variableId = variableIdsByStableId[StableIds.numSpecies]!!,
          numberValue = BigDecimal(123),
      )
      insertValue(
          variableId = variableIdsByStableId[StableIds.totalExpansionPotential]!!,
          numberValue = BigDecimal(5555),
      )
      insertValue(
          variableId = variableIdsByStableId[StableIds.website]!!,
          textValue = "https://example.com/",
      )
      insertSelectValue(
          variableId = variableIdsByStableId[StableIds.projectType]!!,
          optionIds = setOf(terrestrialOptionId),
      )
      StableIds.landUseHectaresByLandUseModel.forEach { (type, stableId) ->
        insertValue(
            variableId = variableIdsByStableId[stableId]!!,
            numberValue = BigDecimal(type.id),
        )
      }

      assertEquals(
          ApplicationVariableValues(
              contactEmail = "a@b.com",
              contactName = "John Smith",
              countryCode = "BR",
              landUseModelHectares = LandUseModelType.entries.associateWith { BigDecimal(it.id) },
              numSpeciesToBePlanted = 123,
              projectType = PreScreenProjectType.Terrestrial,
              totalExpansionPotential = BigDecimal(5555),
              website = "https://example.com/",
          ),
          service.fetchValues(inserted.projectId),
      )
    }

    @Test
    fun `throws exception if no permission to read project deliverables`() {
      every { user.canReadProjectDeliverables(any()) } returns false
      every { user.canReadProject(any()) } returns true

      assertThrows<AccessDeniedException> { service.fetchValues(inserted.projectId) }
    }
  }

  @Nested
  inner class UpdateCountryVariable {
    @Test
    fun `adds a new country variable value`() {
      service.updateCountryVariable(inserted.projectId, "BR")

      val lastValueRow =
          variableValuesDao
              .fetchByVariableId(variableIdsByStableId[StableIds.country]!!)
              .filter { it.projectId!! == inserted.projectId }
              .maxBy { it.id!!.value }

      val selections =
          variableSelectOptionValuesDao.fetchByVariableValueId(lastValueRow.id!!).map {
            it.optionId
          }

      assertEquals(listOf(brazilOptionId), selections, "Project country variable value selections")
    }

    @Test
    fun `replaces existing country variable value`() {
      insertSelectValue(
          variableId = variableIdsByStableId[StableIds.country]!!,
          optionIds = setOf(chileOptionId),
      )
      service.updateCountryVariable(inserted.projectId, "BR")

      val lastValueRow =
          variableValuesDao
              .fetchByVariableId(variableIdsByStableId[StableIds.country]!!)
              .filter { it.projectId!! == inserted.projectId }
              .maxBy { it.id!!.value }

      val selections =
          variableSelectOptionValuesDao.fetchByVariableValueId(lastValueRow.id!!).map {
            it.optionId
          }

      assertEquals(listOf(brazilOptionId), selections, "Project country variable value selections")
    }
  }

  @Nested
  inner class UpdateDealNameVariable {
    @Test
    fun `adds a new deal name variable value`() {
      service.updateDealName(inserted.projectId, "New deal name")

      val lastValueRow =
          variableValuesDao
              .fetchByVariableId(variableIdsByStableId[StableIds.dealName]!!)
              .filter { it.projectId!! == inserted.projectId }
              .maxBy { it.id!!.value }

      assertEquals(lastValueRow.textValue, "New deal name")
    }

    @Test
    fun `replaces existing deal name variable value`() {
      insertValue(variableIdsByStableId[StableIds.dealName]!!, textValue = "Old deal name")
      service.updateDealName(inserted.projectId, "New deal name")

      val lastValueRow =
          variableValuesDao
              .fetchByVariableId(variableIdsByStableId[StableIds.dealName]!!)
              .filter { it.projectId!! == inserted.projectId }
              .maxBy { it.id!!.value }

      assertEquals(lastValueRow.textValue, "New deal name")
    }
  }
}
