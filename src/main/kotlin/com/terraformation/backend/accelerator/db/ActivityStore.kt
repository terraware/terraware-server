package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.accelerator.model.ExistingActivityModel
import com.terraformation.backend.accelerator.model.NewActivityModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.tables.Activities.Companion.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.forMultiset
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class ActivityStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
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
        media = emptyList(),
        modifiedBy = userId,
        modifiedTime = now,
        projectId = model.projectId,
    )
  }

  fun delete(activityId: ActivityId) {
    requirePermissions { deleteActivity(activityId) }

    // Check that the activity exists.
    fetchOneById(activityId, false)

    // TODO: Don't let end users delete published activities once publication is implemented

    dslContext.transaction { _ ->
      eventPublisher.publishEvent(ActivityDeletionStartedEvent(activityId))

      dslContext.deleteFrom(ACTIVITIES).where(ACTIVITIES.ID.eq(activityId)).execute()
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

  fun fetchOneById(activityId: ActivityId, includeMedia: Boolean = false): ExistingActivityModel {
    requirePermissions { readActivity(activityId) }

    return fetchByCondition(ACTIVITIES.ID.eq(activityId), includeMedia).firstOrNull()
        ?: throw ActivityNotFoundException(activityId)
  }

  fun fetchByProjectId(
      projectId: ProjectId,
      includeMedia: Boolean = false,
  ): List<ExistingActivityModel> {
    requirePermissions { listActivities(projectId) }

    return fetchByCondition(ACTIVITIES.PROJECT_ID.eq(projectId), includeMedia)
  }

  private val geolocationField = ACTIVITY_MEDIA_FILES.GEOLOCATION.forMultiset()

  private val mediaMultiset =
      DSL.multiset(
              DSL.select(
                      ACTIVITY_MEDIA_FILES.ACTIVITY_MEDIA_TYPE_ID,
                      ACTIVITY_MEDIA_FILES.CAPTION,
                      ACTIVITY_MEDIA_FILES.CAPTURED_DATE,
                      ACTIVITY_MEDIA_FILES.FILE_ID,
                      ACTIVITY_MEDIA_FILES.IS_COVER_PHOTO,
                      FILES.CREATED_BY,
                      FILES.CREATED_TIME,
                      geolocationField,
                  )
                  .from(ACTIVITY_MEDIA_FILES)
                  .join(FILES)
                  .on(ACTIVITY_MEDIA_FILES.FILE_ID.eq(FILES.ID))
                  .where(ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(ACTIVITIES.ID))
                  .orderBy(ACTIVITY_MEDIA_FILES.FILE_ID)
          )
          .convertFrom { result -> result.map { ActivityMediaModel.of(it, geolocationField) } }

  private fun fetchByCondition(
      condition: Condition,
      includeMedia: Boolean,
  ): List<ExistingActivityModel> {
    return with(ACTIVITIES) {
      if (includeMedia) {
        dslContext
            .select(asterisk(), mediaMultiset)
            .from(ACTIVITIES)
            .where(condition)
            .orderBy(ID)
            .fetch { ExistingActivityModel.of(it, mediaMultiset) }
      } else {
        dslContext.select(asterisk()).from(ACTIVITIES).where(condition).orderBy(ID).fetch {
          ExistingActivityModel.of(it, null)
        }
      }
    }
  }
}
