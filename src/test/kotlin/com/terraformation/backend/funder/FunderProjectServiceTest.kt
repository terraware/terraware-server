package com.terraformation.backend.funder

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.funder.db.PublishedProjectDetailsStore
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class FunderProjectServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  private val systemUser by lazy { SystemUser(usersDao) }

  val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService = mockk()
  val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore = mockk()
  val publishedProjectDetailsStore: PublishedProjectDetailsStore = mockk()
  lateinit var projectId: ProjectId

  private val service: FunderProjectService by lazy {
    FunderProjectService(
        acceleratorProjectVariableValuesService = acceleratorProjectVariableValuesService,
        projectAcceleratorDetailsStore = projectAcceleratorDetailsStore,
        systemUser = systemUser,
        publishedProjectDetailsStore = publishedProjectDetailsStore,
    )
  }

  @BeforeEach
  fun setup() {
    insertOrganization()
    projectId = insertProject()

    val existingValues = ProjectAcceleratorVariableValuesModel(projectId = projectId)
    val existingDetails = FunderProjectDetailsModel(projectId = projectId)

    every { acceleratorProjectVariableValuesService.fetchValues(projectId) } returns existingValues
    every {
      projectAcceleratorDetailsStore.fetchOneByIdForFunder(projectId, existingValues)
    } returns existingDetails
  }

  @Test
  fun `throws error if user can't read project funder details`() {
    val funderId = insertUser(type = UserType.Funder)
    switchToUser(funderId)
    assertThrows<ProjectNotFoundException> { service.fetchByProjectId(projectId) }
  }

  @Test
  fun `does not throw error if user can read project funder details`() {
    insertFundingEntity()
    insertFundingEntityProject()
    val funderId = insertUser(type = UserType.Funder)
    insertFundingEntityUser()
    switchToUser(funderId)

    // actual values are tested in other tests
    assertDoesNotThrow { service.fetchByProjectId(projectId) }
  }
}
