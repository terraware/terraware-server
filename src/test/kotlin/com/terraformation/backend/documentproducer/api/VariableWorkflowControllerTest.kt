package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.pojos.VariableWorkflowHistoryRow
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.put

class VariableWorkflowControllerTest : ControllerIntegrationTest() {
  @BeforeEach
  fun setUp() {
    insertOrganization()
  }

  private fun path(
      projectId: ProjectId = inserted.projectId,
      variableId: VariableId = inserted.variableId,
  ) = "/api/v1/document-producer/projects/$projectId/workflow/$variableId"

  @Nested
  inner class UpdateVariableWorkflowDetails {
    val payload =
        """
        {
          "feedback": "My feedback",
          "internalComment": "My comment",
          "status": "Needs Translation"
        }
        """
            .trimIndent()

    @Nested
    inner class WithPermission {
      @BeforeEach
      fun setUp() {
        insertUserGlobalRole(role = GlobalRole.TFExpert)
      }

      @Test
      fun `updates details`() {
        insertProject()
        insertTextVariable(insertVariable())
        val variableValueId = insertValue(variableId = inserted.variableId, textValue = "dummy")

        mockMvc.put(path()) { content = payload }.andExpect { status { isOk() } }

        assertEquals(
            listOf(
                VariableWorkflowHistoryRow(
                    createdBy = user.userId,
                    createdTime = Instant.EPOCH,
                    feedback = "My feedback",
                    internalComment = "My comment",
                    maxVariableValueId = variableValueId,
                    projectId = inserted.projectId,
                    variableId = inserted.variableId,
                    variableWorkflowStatusId = VariableWorkflowStatus.NeedsTranslation,
                )
            ),
            variableWorkflowHistoryDao.findAll().map { it.copy(id = null) },
            "Should have inserted new history row",
        )
      }

      @Test
      fun `returns error if project does not exist`() {
        insertTextVariable(insertVariable())

        mockMvc
            .put(path(projectId = ProjectId(1))) { content = payload }
            .andExpect { status { isNotFound() } }
      }

      @Test
      fun `returns error if variable does not exist`() {
        insertProject()

        mockMvc
            .put(path(variableId = VariableId(1))) { content = payload }
            .andExpect { status { isNotFound() } }
      }
    }

    @Test
    fun `returns error if no permission to update workflow details`() {
      insertOrganizationUser()
      insertProject()
      insertTextVariable(insertVariable())

      mockMvc.put(path()) { content = payload }.andExpect { status { isForbidden() } }
    }
  }
}
