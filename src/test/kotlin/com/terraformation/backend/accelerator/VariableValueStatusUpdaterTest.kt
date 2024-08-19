package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.VariableValueUpdatedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.pojos.VariableWorkflowHistoryRow
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import com.terraformation.backend.documentproducer.db.VariableWorkflowStore
import com.terraformation.backend.mockUser
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VariableValueStatusUpdaterTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }

  private val variableWorkflowStore: VariableWorkflowStore by lazy {
    VariableWorkflowStore(clock, dslContext, eventPublisher, variablesDao)
  }

  private val updater: VariableValueStatusUpdater by lazy {
    VariableValueStatusUpdater(systemUser, variableWorkflowStore)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
  }

  @Nested
  inner class UpdateVariableStatus {
    @Test
    fun `updates the variable workflow details status with a new history entry`() {
      val projectId = insertProject()
      val variableId = insertNumberVariable(id = insertVariable(type = VariableType.Number))
      val valueId = insertValue(variableId = variableId, numberValue = BigDecimal(10))

      insertVariableWorkflowHistory(
          feedback = null,
          internalComment = null,
          maxVariableValueId = valueId,
          projectId = projectId,
          status = VariableWorkflowStatus.NotSubmitted,
          variableId = variableId,
      )

      insertVariableWorkflowHistory(
          feedback = "Looks good",
          internalComment = "This is very good",
          maxVariableValueId = valueId,
          projectId = projectId,
          status = VariableWorkflowStatus.Approved,
          variableId = variableId,
      )

      updater.on(VariableValueUpdatedEvent(projectId = projectId, variableId = variableId))

      val now = clock.instant

      val actual = fetchLatestWorkflowEntry(projectId, variableId)
      val expected =
          VariableWorkflowHistoryRow(
              createdBy = systemUser.userId,
              createdTime = now,
              feedback = null,
              id = null,
              internalComment = null,
              projectId = projectId,
              variableId = variableId,
              variableWorkflowStatusId = VariableWorkflowStatus.InReview,
              maxVariableValueId = valueId)

      assertEquals(
          expected,
          actual,
          "The variable's workflow details submission status was not updated to 'In Review'")
    }
  }

  private fun fetchLatestWorkflowEntry(
      projectId: ProjectId,
      variableId: VariableId
  ): VariableWorkflowHistoryRow =
      with(VARIABLE_WORKFLOW_HISTORY) {
        dslContext
            .select(asterisk())
            .from(VARIABLE_WORKFLOW_HISTORY)
            .where(VARIABLE_ID.eq(variableId))
            .and(PROJECT_ID.eq(projectId))
            .orderBy(ID.desc())
            .limit(1)
            .fetchOneInto(VariableWorkflowHistoryRow::class.java)!!
            .copy(id = null)
      }
}
