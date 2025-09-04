package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingActivityModel
import com.terraformation.backend.accelerator.model.NewActivityModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.tables.Activities.Companion.ACTIVITIES
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class ActivityStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
) {
  fun create(model: NewActivityModel): ExistingActivityModel {
    requirePermissions { createActivity(model.projectId) }

    if (!parentStore.isProjectInCohort(model.projectId)) {
      throw ProjectNotInCohortException(model.projectId)
    }

    val now = clock.instant()
    val userId = currentUser().userId

    val activityId =
        dslContext
            .insertInto(ACTIVITIES)
            .set(ACTIVITIES.ACTIVITY_DATE, model.activityDate)
            .set(ACTIVITIES.ACTIVITY_TYPE_ID, model.activityType)
            .set(ACTIVITIES.CREATED_BY, userId)
            .set(ACTIVITIES.CREATED_TIME, now)
            .set(ACTIVITIES.DESCRIPTION, model.description)
            .set(ACTIVITIES.IS_HIGHLIGHT, false)
            .set(ACTIVITIES.MODIFIED_BY, userId)
            .set(ACTIVITIES.MODIFIED_TIME, now)
            .set(ACTIVITIES.PROJECT_ID, model.projectId)
            .returningResult(ACTIVITIES.ID)
            .fetchOne(ACTIVITIES.ID)!!

    return ExistingActivityModel(
        activityDate = model.activityDate,
        activityType = model.activityType,
        createdBy = userId,
        createdTime = now,
        description = model.description,
        id = activityId,
        isHighlight = false,
        modifiedBy = userId,
        modifiedTime = now,
        projectId = model.projectId,
    )
  }

  fun delete(activityId: ActivityId) {
    requirePermissions { deleteActivity(activityId) }

    // TODO: Don't let end users delete published activities once publication is implemented

    val rowsDeleted =
        dslContext.deleteFrom(ACTIVITIES).where(ACTIVITIES.ID.eq(activityId)).execute()
    if (rowsDeleted == 0) {
      throw ActivityNotFoundException(activityId)
    }
  }

  fun update(
      activityId: ActivityId,
      applyFunc: (ExistingActivityModel) -> ExistingActivityModel,
  ) {
    requirePermissions { updateActivity(activityId) }

    val existingModel = fetchOneById(activityId)
    val updatedModel = applyFunc(existingModel)
    val now = clock.instant()

    // TODO: Don't let end users edit published activities once publication is implemented

    val activitiesRecord = dslContext.fetchSingle(ACTIVITIES, ACTIVITIES.ID.eq(activityId))

    with(activitiesRecord) {
      activityDate = updatedModel.activityDate
      activityTypeId = updatedModel.activityType
      description = updatedModel.description
      modifiedBy = currentUser().userId
      modifiedTime = now

      if (currentUser().canManageActivity(activityId)) {
        isHighlight = updatedModel.isHighlight

        if (!existingModel.isVerified && updatedModel.isVerified) {
          verifiedBy = currentUser().userId
          verifiedTime = now
        } else if (existingModel.isVerified && !updatedModel.isVerified) {
          verifiedBy = null
          verifiedTime = null
        }
      }

      update()
    }
  }

  fun fetchOneById(activityId: ActivityId): ExistingActivityModel {
    requirePermissions { readActivity(activityId) }

    return fetchByCondition(ACTIVITIES.ID.eq(activityId)).firstOrNull()
        ?: throw ActivityNotFoundException(activityId)
  }

  fun fetchByProjectId(projectId: ProjectId): List<ExistingActivityModel> {
    requirePermissions { listActivities(projectId) }

    return fetchByCondition(ACTIVITIES.PROJECT_ID.eq(projectId))
  }

  private fun fetchByCondition(condition: Condition): List<ExistingActivityModel> {
    return with(ACTIVITIES) {
      dslContext
          .select(
              ACTIVITY_DATE,
              ACTIVITY_TYPE_ID,
              CREATED_BY,
              CREATED_TIME,
              DESCRIPTION,
              ID,
              IS_HIGHLIGHT,
              MODIFIED_BY,
              MODIFIED_TIME,
              PROJECT_ID,
              VERIFIED_BY,
              VERIFIED_TIME,
          )
          .from(ACTIVITIES)
          .where(condition)
          .orderBy(ID)
          .fetch { record -> ExistingActivityModel.of(record) }
    }
  }
}
