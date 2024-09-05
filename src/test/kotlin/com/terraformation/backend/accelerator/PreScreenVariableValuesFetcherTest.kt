package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.model.PreScreenVariableValues
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class PreScreenVariableValuesFetcherTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val fetcher: PreScreenVariableValuesFetcher by lazy {
    PreScreenVariableValuesFetcher(
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
            documentsDao,
            dslContext,
            TestEventPublisher(),
            variableImageValuesDao,
            variableLinkValuesDao,
            variablesDao,
            variableSectionValuesDao,
            variableSelectOptionValuesDao,
            variableValuesDao,
            variableValueTableRowsDao),
        deliverableId)
  }

  private lateinit var countryVariableId: VariableId
  private lateinit var deliverableId: DeliverableId
  private lateinit var numSpeciesVariableId: VariableId
  private lateinit var projectTypeVariableId: VariableId
  private lateinit var totalExpansionPotentialVariableId: VariableId
  private lateinit var landUseHectaresVariableIds: Map<LandUseModelType, VariableId>

  private lateinit var brazilOptionId: VariableSelectOptionId
  private lateinit var terrestrialOptionId: VariableSelectOptionId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertProject()
    insertModule(phase = CohortPhase.PreScreen)
    deliverableId = insertDeliverable()

    countryVariableId =
        insertSelectVariable(
            insertVariable(
                type = VariableType.Select,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 1,
                stableId = PreScreenVariableValuesFetcher.STABLE_ID_COUNTRY))

    brazilOptionId = insertSelectOption(inserted.variableId, "Brazil")
    insertSelectOption(inserted.variableId, "Chile")
    insertSelectOption(inserted.variableId, "Ghana")

    numSpeciesVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 2,
                stableId = PreScreenVariableValuesFetcher.STABLE_ID_NUM_SPECIES))
    totalExpansionPotentialVariableId =
        insertNumberVariable(
            insertVariable(
                type = VariableType.Number,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 3,
                stableId = PreScreenVariableValuesFetcher.STABLE_ID_TOTAL_EXPANSION_POTENTIAL))

    projectTypeVariableId =
        insertSelectVariable(
            insertVariable(
                type = VariableType.Select,
                deliverableId = inserted.deliverableId,
                deliverablePosition = 4,
                stableId = PreScreenVariableValuesFetcher.STABLE_ID_PROJECT_TYPE))

    terrestrialOptionId = insertSelectOption(inserted.variableId, "Terrestrial")
    insertSelectOption(inserted.variableId, "Mangrove")
    insertSelectOption(inserted.variableId, "Mixed")

    landUseHectaresVariableIds =
        PreScreenVariableValuesFetcher.stableIdsByLandUseModelType.entries
            .mapIndexed { index, (landUseType, stableId) ->
              landUseType to
                  insertNumberVariable(
                      insertVariable(
                          type = VariableType.Number,
                          deliverableId = inserted.deliverableId,
                          deliverablePosition = index + 4,
                          stableId = stableId))
            }
            .toMap()

    every { user.canReadProjectDeliverables(any()) } returns true
  }

  @Test
  fun `returns null or empty values if variables not set`() {
    assertEquals(
        PreScreenVariableValues(null, emptyMap(), null, null, null),
        fetcher.fetchValues(inserted.projectId))
  }

  @Test
  fun `fetches values for all variables`() {
    insertSelectValue(variableId = countryVariableId, optionIds = setOf(brazilOptionId))
    insertValue(variableId = numSpeciesVariableId, numberValue = BigDecimal(123))
    insertValue(variableId = totalExpansionPotentialVariableId, numberValue = BigDecimal(5555))
    insertSelectValue(variableId = projectTypeVariableId, optionIds = setOf(terrestrialOptionId))
    LandUseModelType.entries.forEach { type ->
      insertValue(
          variableId = landUseHectaresVariableIds[type]!!, numberValue = BigDecimal(type.id))
    }

    assertEquals(
        PreScreenVariableValues(
            countryCode = "BR",
            landUseModelHectares = LandUseModelType.entries.associateWith { BigDecimal(it.id) },
            numSpeciesToBePlanted = 123,
            projectType = PreScreenProjectType.Terrestrial,
            totalExpansionPotential = BigDecimal(5555)),
        fetcher.fetchValues(inserted.projectId))
  }

  @Test
  fun `throws exception if no permission to read project deliverables`() {
    every { user.canReadProjectDeliverables(any()) } returns false
    every { user.canReadProject(any()) } returns true

    assertThrows<AccessDeniedException> { fetcher.fetchValues(inserted.projectId) }
  }
}
