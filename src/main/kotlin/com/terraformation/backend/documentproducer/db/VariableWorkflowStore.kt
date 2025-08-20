package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLE_VARIABLES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.tables.references.VARIABLES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_VALUES
import com.terraformation.backend.db.docprod.tables.references.VARIABLE_WORKFLOW_HISTORY
import com.terraformation.backend.documentproducer.event.QuestionsDeliverableReviewedEvent
import com.terraformation.backend.documentproducer.model.ExistingVariableWorkflowHistoryModel
import com.terraformation.backend.documentproducer.model.VariableWorkflowModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class VariableWorkflowStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  /**
   * Returns the current workflow information for the variables in a project. Internal comment is
   * only populated if the current user has permission to read it.
   */
  fun fetchCurrentForProject(
      projectId: ProjectId
  ): Map<VariableId, ExistingVariableWorkflowHistoryModel> {
    requirePermissions { readProject(projectId) }

    return fetchCurrentByCondition(VARIABLE_WORKFLOW_HISTORY.PROJECT_ID.eq(projectId)).associateBy {
      it.variableId
    }
  }

  fun fetchProjectVariableHistory(
      projectId: ProjectId,
      variableId: VariableId,
  ): List<ExistingVariableWorkflowHistoryModel> {
    requirePermissions { readInternalVariableWorkflowDetails(projectId) }

    return with(VARIABLE_WORKFLOW_HISTORY) {
      dslContext
          .select(
              CREATED_BY,
              CREATED_TIME,
              FEEDBACK,
              ID,
              INTERNAL_COMMENT,
              MAX_VARIABLE_VALUE_ID,
              PROJECT_ID,
              VARIABLE_ID,
              VARIABLE_WORKFLOW_STATUS_ID,
              currentVariableIdField,
          )
          .from(VARIABLE_WORKFLOW_HISTORY)
          .join(VARIABLES)
          .on(VARIABLE_WORKFLOW_HISTORY.VARIABLE_ID.eq(VARIABLES.ID))
          .where(PROJECT_ID.eq(projectId))
          .and(
              VARIABLES.STABLE_ID.eq(
                  DSL.select(VARIABLES.STABLE_ID).from(VARIABLES).where(VARIABLES.ID.eq(variableId))
              )
          )
          .orderBy(CREATED_TIME.desc(), ID.desc())
          .fetch { ExistingVariableWorkflowHistoryModel.of(it, currentVariableIdField) }
    }
  }

  fun update(
      projectId: ProjectId,
      variableId: VariableId,
      updateFunc: (VariableWorkflowModel) -> VariableWorkflowModel,
  ): ExistingVariableWorkflowHistoryModel {
    requirePermissions { updateInternalVariableWorkflowDetails(projectId) }

    if (!dslContext.fetchExists(PROJECTS, PROJECTS.ID.eq(projectId))) {
      throw ProjectNotFoundException(projectId)
    }

    if (!dslContext.fetchExists(VARIABLES, VARIABLES.ID.eq(variableId))) {
      throw VariableNotFoundException(variableId)
    }

    return dslContext.transactionResult { _ ->
      val existing =
          with(VARIABLE_WORKFLOW_HISTORY) {
            fetchCurrentByCondition(
                    DSL.and(
                        PROJECT_ID.eq(projectId),
                        VARIABLES.STABLE_ID.eq(
                            DSL.select(VARIABLES.STABLE_ID)
                                .from(VARIABLES)
                                .where(VARIABLES.ID.eq(variableId))
                        ),
                    )
                )
                .firstOrNull()
          }

      val updated =
          updateFunc(
              VariableWorkflowModel(
                  status = existing?.status ?: VariableWorkflowStatus.NotSubmitted,
                  feedback = existing?.feedback,
                  internalComment = existing?.internalComment,
              )
          )

      val newModel =
          with(VARIABLE_WORKFLOW_HISTORY) {
            dslContext
                .insertInto(VARIABLE_WORKFLOW_HISTORY)
                .set(CREATED_BY, currentUser().userId)
                .set(CREATED_TIME, clock.instant())
                .set(FEEDBACK, updated.feedback)
                .set(INTERNAL_COMMENT, updated.internalComment)
                .set(
                    MAX_VARIABLE_VALUE_ID,
                    DSL.field(DSL.select(DSL.max(VARIABLE_VALUES.ID)).from(VARIABLE_VALUES)),
                )
                .set(PROJECT_ID, projectId)
                .set(VARIABLE_ID, variableId)
                .set(VARIABLE_WORKFLOW_STATUS_ID, updated.status)
                .returningResult()
                .fetchOne { ExistingVariableWorkflowHistoryModel.of(it) }
          }

      if (
          existing == null ||
              updated.status != existing.status ||
              updated.feedback != existing.feedback
      ) {
        // Notify a reviewable event, if changed
        val deliverableIds =
            with(DELIVERABLE_VARIABLES) {
              dslContext
                  .select(DELIVERABLE_ID)
                  .from(DELIVERABLE_VARIABLES)
                  .where(VARIABLE_ID.eq(variableId))
                  .fetch(DELIVERABLE_ID.asNonNullable())
            }

        deliverableIds.forEach { deliverableId ->
          eventPublisher.publishEvent(QuestionsDeliverableReviewedEvent(deliverableId, projectId))
        }
      }

      newModel!!
    }
  }

  /**
   * Subquery field for the ID of the most recent variable that has the same stable ID as the
   * variable referenced by [VARIABLE_WORKFLOW_HISTORY]. This is needed because old history entries
   * might refer to variables that have subsequently been replaced, and we want to include them in
   * the history of the latest version of the variable.
   */
  private val currentVariableIdField: Field<VariableId?> by lazy {
    DSL.field(
        DSL.select(DSL.max(VARIABLES.ID))
            .from(VARIABLES)
            .where(
                VARIABLES.STABLE_ID.eq(
                    DSL.select(VARIABLES.STABLE_ID)
                        .from(VARIABLES)
                        .where(VARIABLES.ID.eq(VARIABLE_WORKFLOW_HISTORY.VARIABLE_ID))
                )
            )
    )
  }

  private fun fetchCurrentByCondition(
      condition: Condition
  ): List<ExistingVariableWorkflowHistoryModel> {
    return with(VARIABLE_WORKFLOW_HISTORY) {
      dslContext
          .select(
              CREATED_BY,
              CREATED_TIME,
              FEEDBACK,
              ID,
              INTERNAL_COMMENT,
              MAX_VARIABLE_VALUE_ID,
              PROJECT_ID,
              VARIABLE_ID,
              VARIABLE_WORKFLOW_STATUS_ID,
              currentVariableIdField,
          )
          .distinctOn(PROJECT_ID, VARIABLES.STABLE_ID)
          .from(VARIABLE_WORKFLOW_HISTORY)
          .join(VARIABLES)
          .on(VARIABLE_WORKFLOW_HISTORY.VARIABLE_ID.eq(VARIABLES.ID))
          .where(condition)
          .orderBy(PROJECT_ID, VARIABLES.STABLE_ID, ID.desc())
          .fetch { ExistingVariableWorkflowHistoryModel.of(it, currentVariableIdField) }
    }
  }
}
