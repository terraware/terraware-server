package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.db.VariableValueStore
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.BeforeEach

class ProjectAcceleratorDetailsServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  val clock = TestClock()
  val eventPublisher = TestEventPublisher()

  val variableStore: VariableStore by lazy {
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
        variableTextsDao)
  }
  val variableValueStore: VariableValueStore by lazy {
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
        variableValueTableRowsDao)
  }

  val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService by lazy {
    AcceleratorProjectVariableValuesService(
        countriesDao, variableStore, variableValueStore, SystemUser(usersDao))
  }
  val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore by lazy {
    ProjectAcceleratorDetailsStore(
        clock,
        dslContext,
        eventPublisher,
    )
  }

  private val service: ProjectAcceleratorDetailsService by lazy {
    ProjectAcceleratorDetailsService(
        acceleratorProjectVariableValuesService,
        projectAcceleratorDetailsStore,
    )
  }

  private val projectId = ProjectId(1)

  @BeforeEach fun setup() {}
}
