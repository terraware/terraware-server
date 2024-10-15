package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
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
        deliverableId)
  }

  private lateinit var countryVariableId: VariableId
  private lateinit var deliverableId: DeliverableId

  private lateinit var contactEmailVariableId: VariableId
  private lateinit var contactNameVariableId: VariableId
  private lateinit var numSpeciesVariableId: VariableId
  private lateinit var projectTypeVariableId: VariableId
  private lateinit var totalExpansionPotentialVariableId: VariableId
  private lateinit var websiteVariableId: VariableId
  private lateinit var landUseHectaresVariableIds: Map<LandUseModelType, VariableId>

  private lateinit var brazilOptionId: VariableSelectOptionId
  private lateinit var chileOptionId: VariableSelectOptionId
  private lateinit var terrestrialOptionId: VariableSelectOptionId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertModule(phase = CohortPhase.PreScreen)
    deliverableId = insertDeliverable()

    contactEmailVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.contactEmail.value))
    contactNameVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.contactName.value))
    countryVariableId =
        insertSelectVariable(
            insertVariable(
                type = VariableType.Select,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 1,
                stableId = StableIds.country.value))

    brazilOptionId = insertSelectOption(inserted.variableId, "Brazil")
    chileOptionId = insertSelectOption(inserted.variableId, "Chile")
    insertSelectOption(inserted.variableId, "Ghana")

    numSpeciesVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 2,
                stableId = StableIds.numSpecies.value))
    totalExpansionPotentialVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 3,
                stableId = StableIds.totalExpansionPotential.value))
    websiteVariableId =
        insertTextVariable(
            insertVariable(type = VariableType.Text, stableId = StableIds.website.value))

    projectTypeVariableId =
        insertSelectVariable(
            insertVariable(
                type = VariableType.Select,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 4,
                stableId = StableIds.projectType.value))
    terrestrialOptionId = insertSelectOption(inserted.variableId, "Terrestrial")
    insertSelectOption(inserted.variableId, "Mangrove")
    insertSelectOption(inserted.variableId, "Mixed")

    landUseHectaresVariableIds =
        StableIds.landUseHectaresByLandUseModel.entries
            .mapIndexed { index, (landUseType, stableId) ->
              landUseType to
                  insertNumberVariable(
                      insertVariable(
                          type = VariableType.Number,
                          deliverableId = inserted.deliverableId,
                          deliverablePosition = index + 4,
                          stableId = stableId.value))
            }
            .toMap()

    every { user.canReadProjectDeliverables(any()) } returns true
  }

  @Nested
  inner class FetchValues {
    @Test
    fun `returns null or empty values if variables not set`() {
      assertEquals(
          ApplicationVariableValues(null, null, null, emptyMap(), null, null, null, null),
          service.fetchValues(inserted.projectId))
    }

    @Test
    fun `fetches values for all variables`() {
      insertValue(variableId = contactEmailVariableId, textValue = "a@b.com")
      insertValue(variableId = contactNameVariableId, textValue = "John Smith")
      insertSelectValue(variableId = countryVariableId, optionIds = setOf(brazilOptionId))
      insertValue(variableId = numSpeciesVariableId, numberValue = BigDecimal(123))
      insertValue(variableId = totalExpansionPotentialVariableId, numberValue = BigDecimal(5555))
      insertValue(variableId = websiteVariableId, textValue = "https://example.com/")
      insertSelectValue(variableId = projectTypeVariableId, optionIds = setOf(terrestrialOptionId))
      LandUseModelType.entries.forEach { type ->
        insertValue(
            variableId = landUseHectaresVariableIds[type]!!, numberValue = BigDecimal(type.id))
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
          service.fetchValues(inserted.projectId))
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
              .fetchByVariableId(countryVariableId)
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
      insertSelectValue(variableId = countryVariableId, optionIds = setOf(chileOptionId))
      service.updateCountryVariable(inserted.projectId, "BR")

      val lastValueRow =
          variableValuesDao
              .fetchByVariableId(countryVariableId)
              .filter { it.projectId!! == inserted.projectId }
              .maxBy { it.id!!.value }

      val selections =
          variableSelectOptionValuesDao.fetchByVariableValueId(lastValueRow.id!!).map {
            it.optionId
          }

      assertEquals(listOf(brazilOptionId), selections, "Project country variable value selections")
    }
  }
}
