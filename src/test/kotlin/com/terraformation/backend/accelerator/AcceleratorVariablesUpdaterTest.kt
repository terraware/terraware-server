package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.records.ProjectAcceleratorDetailsRecord
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.documentproducer.model.StableIds
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AcceleratorVariablesUpdaterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val applicationStore = mockk<ApplicationStore>()

  private val acceleratorProjectVariableValuesService by lazy {
    AcceleratorProjectVariableValuesService(
        countriesDao,
        variableStore,
        variableValuesStore,
        SystemUser(usersDao),
    )
  }
  private val variableStore by lazy {
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
    )
  }
  private val variableValuesStore by lazy {
    VariableValueStore(
        clock,
        dslContext,
        eventPublisher,
        variableImageValuesDao,
        variableLinkValuesDao,
        variablesDao,
        variableSectionValuesDao,
        variableSelectOptionValuesDao,
        variableValuesDao,
        variableValueTableRowsDao,
    )
  }

  private val updater: AcceleratorVariablesUpdater by lazy {
    AcceleratorVariablesUpdater(
        SystemUser(usersDao),
        applicationStore,
        acceleratorProjectVariableValuesService,
        dslContext,
        variableStore,
    )
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId
  val applicationId = ApplicationId(1)

  private lateinit var countryVariableId: VariableId
  private lateinit var dealNameVariableId: VariableId

  private lateinit var brazilOptionId: VariableSelectOptionId
  private lateinit var chileOptionId: VariableSelectOptionId
  private lateinit var ghanaOptionId: VariableSelectOptionId

  @BeforeEach
  fun setup() {
    every { user.canReadProjectAcceleratorDetails(any()) } returns true

    val variableIds = setupStableIdVariables()
    organizationId = insertOrganization()
    projectId = insertProject()

    countryVariableId = variableIds[StableIds.country]!!
    dealNameVariableId = variableIds[StableIds.dealName]!!

    brazilOptionId = insertSelectOption(countryVariableId, "Brazil")
    chileOptionId = insertSelectOption(countryVariableId, "Chile")
    ghanaOptionId = insertSelectOption(countryVariableId, "Ghana")

    val applicationModel =
        ExistingApplicationModel(
            createdTime = Instant.EPOCH,
            id = applicationId,
            internalName = "XXX",
            modifiedTime = null,
            projectId = projectId,
            projectName = "Project Name",
            organizationId = organizationId,
            organizationName = "Organization 1",
            status = ApplicationStatus.NotSubmitted,
        )

    every { applicationStore.fetchByProjectId(projectId) } returns listOf(applicationModel)
    every { applicationStore.fetchOneById(applicationId) } returns applicationModel
    every { applicationStore.updateCountryCode(applicationId, any()) } returns Unit
  }

  @Nested
  inner class VariableValueUpdatedEventListener {
    @Nested
    inner class Country {
      @Test
      fun `updates the country code and internal name of an application if country variable updated`() {
        insertSelectValue(countryVariableId, optionIds = setOf(brazilOptionId))
        updater.on(VariableValueUpdatedEvent(projectId, countryVariableId))

        verify(exactly = 1) { applicationStore.updateCountryCode(applicationId, "BR") }
      }

      @Test
      fun `does not update if project has no application`() {
        every { applicationStore.fetchByProjectId(projectId) } returns emptyList()

        updater.on(VariableValueUpdatedEvent(projectId, countryVariableId))
        verify(exactly = 0) { applicationStore.updateCountryCode(any(), any()) }
      }

      @Test
      fun `does not update for a non-country variable`() {
        updater.on(VariableValueUpdatedEvent(projectId, dealNameVariableId))

        verify(exactly = 0) { applicationStore.updateCountryCode(any(), any()) }
      }
    }

    @Nested
    inner class DealName {
      @Test
      fun `creates a project accelerator details row`() {
        insertValue(dealNameVariableId, textValue = "New deal name")
        updater.on(VariableValueUpdatedEvent(projectId, dealNameVariableId))

        assertTableEquals(
            ProjectAcceleratorDetailsRecord(projectId = projectId, dealName = "New deal name")
        )
      }

      @Test
      fun `updates an existing project accelerator details row`() {
        insertProjectAcceleratorDetails(
            projectId = projectId,
            dealDescription = "Deal description",
            dealName = "Existing deal name",
        )

        insertValue(dealNameVariableId, textValue = "New deal name")
        updater.on(VariableValueUpdatedEvent(projectId, dealNameVariableId))

        assertTableEquals(
            ProjectAcceleratorDetailsRecord(
                projectId = projectId,
                dealDescription = "Deal description",
                dealName = "New deal name",
            )
        )
      }
    }
  }

  @Nested
  inner class ApplicationInternalNameUpdatedEvent {
    @Test
    fun `updates deal name variable value to application internal name`() {
      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))

      assertEquals(
          "XXX",
          acceleratorProjectVariableValuesService.fetchValues(projectId).dealName,
      )
    }
  }
}
