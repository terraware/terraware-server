package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectAcceleratorDetailsServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService = mockk()
  val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore = mockk()

  val existingDetails: ProjectAcceleratorDetailsModel = mockk()
  val updatedDetails: ProjectAcceleratorDetailsModel = mockk()

  val existingValues: ProjectAcceleratorVariableValuesModel = mockk()
  val updatedValues: ProjectAcceleratorVariableValuesModel = mockk()

  val updateFunc: (ProjectAcceleratorDetailsModel) -> ProjectAcceleratorDetailsModel = mockk()

  private val service: ProjectAcceleratorDetailsService by lazy {
    ProjectAcceleratorDetailsService(
        acceleratorProjectVariableValuesService,
        projectAcceleratorDetailsStore,
    )
  }

  private val projectId = ProjectId(1)

  @BeforeEach
  fun setup() {
    every { updateFunc(existingDetails) } returns updatedDetails
    every { updatedDetails.toVariableValuesModel() } returns updatedValues
    every { existingValues.toProjectAcceleratorDetails() } returns existingDetails

    every { projectAcceleratorDetailsStore.fetchOneById(projectId) } returns existingDetails
    every { projectAcceleratorDetailsStore.update(projectId, any()) } returns Unit

    every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns existingValues
    every { acceleratorProjectVariableValuesService.writeValues(projectId, any()) } returns Unit
  }

  @Test
  fun `updates both accelerator details and project variable values`() {
    service.update(projectId, updateFunc)

    verify(exactly = 1) { projectAcceleratorDetailsStore.update(projectId, updateFunc) }
    verify(exactly = 1) {
      acceleratorProjectVariableValuesService.writeValues(projectId, updatedValues)
    }
  }
}
