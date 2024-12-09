package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.event.ApplicationInternalNameUpdatedEvent
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.accelerator.model.ApplicationVariableValues
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.PreScreenProjectType
import com.terraformation.backend.accelerator.variables.ApplicationVariableValuesService
import com.terraformation.backend.accelerator.variables.StableIds
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.model.BaseVariableProperties
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApplicationVariablesUpdaterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val applicationStore = mockk<ApplicationStore>()
  private val variableStore = mockk<VariableStore>()
  private val applicationVariableValuesService = mockk<ApplicationVariableValuesService>()

  private val updater: ApplicationVariablesUpdater by lazy {
    ApplicationVariablesUpdater(
        SystemUser(usersDao),
        applicationStore,
        variableStore,
        applicationVariableValuesService,
    )
  }

  private val applicationId = ApplicationId(1)
  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(1)

  private val applicationModel =
      ExistingApplicationModel(
          createdTime = Instant.EPOCH,
          id = applicationId,
          internalName = "XXX",
          modifiedTime = null,
          projectId = projectId,
          projectName = "Project Name",
          organizationId = organizationId,
          organizationName = "Organization 1",
          status = ApplicationStatus.NotSubmitted)

  private val countryVariableId = VariableId(1)
  private val countryVariable =
      SelectVariable(
          BaseVariableProperties(
              id = countryVariableId,
              manifestId = null,
              name = "country",
              position = 1,
              stableId = StableIds.country.value),
          false,
          emptyList(),
      )

  @BeforeEach
  fun setup() {
    every { applicationStore.fetchByProjectId(projectId) } returns listOf(applicationModel)
    every { applicationStore.fetchOneById(applicationId) } returns applicationModel
    every { applicationStore.updateCountryCode(applicationId, any()) } returns Unit
    every { variableStore.fetchOneVariable(countryVariableId) } returns countryVariable

    every { applicationVariableValuesService.fetchValues(projectId) } returns
        ApplicationVariableValues(
            countryCode = "US",
            landUseModelHectares = emptyMap(),
            numSpeciesToBePlanted = 10,
            projectType = PreScreenProjectType.Terrestrial,
            totalExpansionPotential = BigDecimal(10))
    every { applicationVariableValuesService.updateDealName(projectId, any()) } returns Unit
  }

  @Nested
  inner class VariableValueUpdatedEventListener {

    @Test
    fun `updates the country code and internal name of an application if country variable updated`() {
      updater.on(VariableValueUpdatedEvent(projectId, countryVariableId))

      verify(exactly = 1) { applicationStore.updateCountryCode(applicationId, "US") }
    }

    @Test
    fun `does not update if project has no application`() {
      every { applicationStore.fetchByProjectId(projectId) } returns emptyList()

      updater.on(VariableValueUpdatedEvent(projectId, countryVariableId))

      verify(exactly = 0) { applicationVariableValuesService.fetchValues(any()) }
      verify(exactly = 0) { applicationStore.updateCountryCode(any(), any()) }
    }

    @Test
    fun `does not update for a non-country variable`() {
      val variableId = VariableId(2)
      every { variableStore.fetchOneVariable(variableId) } returns
          SelectVariable(
              BaseVariableProperties(
                  id = variableId,
                  manifestId = null,
                  name = "not country",
                  position = 1,
                  stableId = ""),
              false,
              emptyList(),
          )

      updater.on(VariableValueUpdatedEvent(projectId, variableId))

      verify(exactly = 0) { applicationVariableValuesService.fetchValues(any()) }
      verify(exactly = 0) { applicationStore.updateCountryCode(any(), any()) }
    }
  }

  @Nested
  inner class ApplicationInternalNameUpdatedEvent {
    @Test
    fun `updates deal name variable value to application internal name`() {
      updater.on(ApplicationInternalNameUpdatedEvent(applicationId))
      verify(exactly = 1) {
        applicationVariableValuesService.updateDealName(projectId, applicationModel.internalName)
      }
    }
  }
}
