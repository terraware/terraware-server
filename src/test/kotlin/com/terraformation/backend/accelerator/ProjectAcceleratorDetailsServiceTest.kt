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

  private val projectId = ProjectId(1)
  val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService = mockk()
  val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore = mockk()

  val existingDetails: ProjectAcceleratorDetailsModel =
      ProjectAcceleratorDetailsModel(projectId = projectId)
  val updatedDetails: ProjectAcceleratorDetailsModel =
      ProjectAcceleratorDetailsModel(projectId = projectId, whatNeedsToBeTrue = "updated")

  val existingValues: ProjectAcceleratorVariableValuesModel =
      ProjectAcceleratorVariableValuesModel(projectId = projectId)
  val updatedValues: ProjectAcceleratorVariableValuesModel =
      ProjectAcceleratorVariableValuesModel(projectId = projectId, whatNeedsToBeTrue = "updated")

  val updateFunc: (ProjectAcceleratorDetailsModel) -> ProjectAcceleratorDetailsModel = mockk()

  private val service: ProjectAcceleratorDetailsService by lazy {
    ProjectAcceleratorDetailsService(
        acceleratorProjectVariableValuesService,
        projectAcceleratorDetailsStore,
    )
  }

  @BeforeEach
  fun setup() {
    every { updateFunc(existingDetails) } returns updatedDetails

    every { projectAcceleratorDetailsStore.fetchOneById(projectId, existingValues) } returns
        existingDetails
    every { projectAcceleratorDetailsStore.fetchOneById(projectId, updatedValues) } returns
        updatedDetails
    every { projectAcceleratorDetailsStore.update(projectId, any(), any()) } returns Unit

    every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns existingValues
    every { acceleratorProjectVariableValuesService.writeValues(projectId, any()) } returns Unit
  }

  @Test
  fun `updates both accelerator details and project variable values`() {
    service.update(projectId, updateFunc)

    verify(exactly = 1) {
      projectAcceleratorDetailsStore.update(projectId, existingValues, updateFunc)
    }
    verify(exactly = 1) {
      acceleratorProjectVariableValuesService.writeValues(projectId, updatedValues)
    }
  }
}
