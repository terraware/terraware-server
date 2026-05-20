package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ActivityMediaUpdatedEvent
import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.accelerator.tables.records.ActivitiesRecord
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class ActivityMediaStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun fetchOne(activityId: ActivityId, fileId: FileId): ActivityMediaModel {
    requirePermissions { readActivity(activityId) }

    return fetchByCondition(
            ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId)
                .and(ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId))
        )
        .firstOrNull() ?: throw FileNotFoundException(fileId)
  }

  fun fetchByActivityId(activityId: ActivityId): List<ActivityMediaModel> {
    requirePermissions { readActivity(activityId) }

    return fetchByCondition(ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId))
  }

  fun fetchByOrganizationId(organizationId: OrganizationId): List<ActivityMediaModel> {
    requirePermissions { readOrganization(organizationId) }

    val user = currentUser()
    return fetchByCondition(
            ACTIVITY_MEDIA_FILES.activities.projects.ORGANIZATION_ID.eq(organizationId)
        )
        .filter { user.canReadActivity(it.activityId) }
  }

  fun fetchByProjectId(projectId: ProjectId): List<ActivityMediaModel> {
    requirePermissions { readProject(projectId) }

    val user = currentUser()
    return fetchByCondition(ACTIVITY_MEDIA_FILES.activities.PROJECT_ID.eq(projectId)).filter {
      user.canReadActivity(it.activityId)
    }
  }

  /**
   * Updates information about an activity media file.
   *
   * @param triggeredBy If this update is triggered by some other action, for example, editing an
   *   observation media file for an observation that has an activity, an object representing the
   *   triggering action. This will typically be an application event. Bypasses the permission check
   *   (the triggering action is assumed to enforce its own permissions).
   */
  fun updateMedia(
      activityId: ActivityId,
      fileId: FileId,
      triggeredBy: Any? = null,
      applyFunc: (ActivityMediaModel) -> ActivityMediaModel,
  ) {
    if (triggeredBy == null) {
      requirePermissions { updateActivity(activityId) }
    }

    val existing = fetchOne(activityId, fileId)
    val updated = applyFunc(existing)

    if (updated.isCoverPhoto && existing.type != ActivityMediaType.Photo) {
      throw IllegalArgumentException("Only photo files can be selected as cover photos")
    }

    withLockedActivity(activityId) { activitiesRecord ->
      with(ACTIVITY_MEDIA_FILES) {
        if (!existing.isCoverPhoto && updated.isCoverPhoto) {
          dslContext
              .update(ACTIVITY_MEDIA_FILES)
              .set(IS_COVER_PHOTO, false)
              .where(ACTIVITY_ID.eq(activityId))
              .execute()
        }

        dslContext
            .update(ACTIVITY_MEDIA_FILES)
            .set(CAPTION, updated.caption)
            .set(IS_COVER_PHOTO, updated.isCoverPhoto)
            .set(IS_HIDDEN_ON_MAP, updated.isHiddenOnMap)
            .where(ACTIVITY_ID.eq(activityId))
            .and(FILE_ID.eq(fileId))
            .execute()
      }

      val fileIds = fetchFileIds(activityId).toMutableList()
      val desiredIndex = (updated.listPosition - 1).coerceIn(0, fileIds.size - 1)
      val currentIndex = fileIds.indexOf(fileId)

      if (desiredIndex != currentIndex) {
        fileIds.removeAt(currentIndex)
        fileIds.add(desiredIndex, fileId)

        updateListPositions(activityId, fileIds)
      }

      markActivityModified(activityId)

      with(FILES) {
        dslContext
            .update(FILES)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .where(ID.eq(fileId))
            .execute()
      }

      eventPublisher.publishEvent(
          ActivityMediaUpdatedEvent(
              activityId = activityId,
              activityType = activitiesRecord.activityTypeId!!,
              caption = updated.caption,
              fileId = fileId,
              triggeredBy = triggeredBy,
          )
      )
    }
  }

  fun insertMediaFile(row: ActivityMediaFilesRow) {
    val activityId = row.activityId!!
    val fileId = row.fileId!!
    val listPosition = row.listPosition

    requirePermissions { updateActivity(activityId) }

    withLockedActivity(activityId) {
      val orderedFileIds = fetchFileIds(activityId).toMutableList()

      if (listPosition != null) {
        val newIndex = (listPosition - 1).coerceIn(0, orderedFileIds.size)
        orderedFileIds.add(newIndex, fileId)
      } else {
        orderedFileIds.add(fileId)
      }

      with(ACTIVITY_MEDIA_FILES) {
        dslContext
            .insertInto(ACTIVITY_MEDIA_FILES)
            .set(FILE_ID, fileId)
            .set(ACTIVITY_ID, activityId)
            .set(ACTIVITY_MEDIA_TYPE_ID, row.activityMediaTypeId)
            .set(IS_COVER_PHOTO, row.isCoverPhoto)
            .set(IS_HIDDEN_ON_MAP, row.isHiddenOnMap)
            .set(LIST_POSITION, 0) // We'll overwrite this with the real position
            .execute()
      }

      updateListPositions(activityId, orderedFileIds)

      markActivityModified(activityId)
    }
  }

  /**
   * Deletes an activity media file from the database. Does not delete it from the file store! You
   * probably want `ActivityMediaService.deleteMedia`, not this.
   */
  fun deleteFromDatabase(activityId: ActivityId, fileId: FileId) {
    requirePermissions { updateActivity(activityId) }

    withLockedActivity(activityId) {
      ensureFileExists(activityId, fileId)

      dslContext
          .deleteFrom(ACTIVITY_MEDIA_FILES)
          .where(ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId))
          .execute()

      updateListPositions(activityId, fetchFileIds(activityId))

      markActivityModified(activityId)

      eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
    }
  }

  /**
   * Deletes an activity media file from the database as part of a higher-level operation. The
   * higher-level operation is assumed to have done the necessary permission checks and is assumed
   * to publish [FileReferenceDeletedEvent] after calling this.
   *
   * Returns silently if the file doesn't exist on the activity.
   */
  fun deleteForTrigger(fileId: FileId) {
    val activityId =
        dslContext.fetchValue(
            ACTIVITY_MEDIA_FILES.ACTIVITY_ID,
            ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId),
        ) ?: return

    withLockedActivity(activityId) {
      val rowsDeleted =
          dslContext
              .deleteFrom(ACTIVITY_MEDIA_FILES)
              .where(ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId))
              .execute()

      if (rowsDeleted > 0) {
        updateListPositions(activityId, fetchFileIds(activityId))

        markActivityModified(activityId)
      }
    }
  }

  fun ensureFileExists(activityId: ActivityId, fileId: FileId) {
    val fileExists =
        dslContext.fetchExists(
            ACTIVITY_MEDIA_FILES,
            ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId),
            ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId),
        )
    if (!fileExists) {
      throw FileNotFoundException(fileId)
    }
  }

  /** Returns the file IDs for an activity in list position order. */
  private fun fetchFileIds(activityId: ActivityId): List<FileId> {
    return with(ACTIVITY_MEDIA_FILES) {
      dslContext
          .select(FILE_ID)
          .from(ACTIVITY_MEDIA_FILES)
          .where(ACTIVITY_ID.eq(activityId))
          .orderBy(LIST_POSITION)
          .fetch(FILE_ID.asNonNullable())
    }
  }

  private fun fetchByCondition(condition: Condition): List<ActivityMediaModel> {
    return dslContext
        .select(
            ACTIVITY_MEDIA_FILES.asterisk(),
            FILES.CAPTURED_LOCAL_TIME,
            FILES.CREATED_BY,
            FILES.CREATED_TIME,
            FILES.GEOLOCATION,
            FILES.STORAGE_URL,
            OBSERVATION_MEDIA_FILES.OBSERVATION_ID,
            OBSERVATION_MEDIA_FILES.POSITION_ID,
            OBSERVATION_MEDIA_FILES.TYPE_ID,
            MONITORING_PLOTS.PLOT_NUMBER,
        )
        .from(ACTIVITY_MEDIA_FILES)
        .join(FILES)
        .on(ACTIVITY_MEDIA_FILES.FILE_ID.eq(FILES.ID))
        .leftJoin(OBSERVATION_MEDIA_FILES)
        .on(ACTIVITY_MEDIA_FILES.FILE_ID.eq(OBSERVATION_MEDIA_FILES.FILE_ID))
        .leftJoin(MONITORING_PLOTS)
        .on(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
        .where(condition)
        .orderBy(ACTIVITY_MEDIA_FILES.FILE_ID)
        .fetch { ActivityMediaModel.of(it) }
  }

  private fun <T> withLockedActivity(activityId: ActivityId, func: (ActivitiesRecord) -> T): T {
    return dslContext.transactionResult { _ ->
      val record =
          dslContext
              .selectFrom(ACTIVITIES)
              .where(ACTIVITIES.ID.eq(activityId))
              .forUpdate()
              .fetchOne() ?: throw ActivityNotFoundException(activityId)

      func(record)
    }
  }

  /**
   * Sets the list positions of the files in an activity.
   *
   * @param fileIds The list of activity media file IDs in the desired order. The file whose ID is
   *   the first element of this list will have its list position set to 1, then 2 for the second
   *   element of this list, and so forth.
   */
  private fun updateListPositions(activityId: ActivityId, fileIds: List<FileId>) {
    val positionsMap = fileIds.mapIndexed { index, fileId -> fileId to index + 1 }.toMap()
    with(ACTIVITY_MEDIA_FILES) {
      dslContext
          .update(ACTIVITY_MEDIA_FILES)
          .set(LIST_POSITION, DSL.case_(FILE_ID.asNonNullable()).mapValues(positionsMap))
          .where(FILE_ID.`in`(fileIds))
          .and(ACTIVITY_ID.eq(activityId))
          .execute()
    }
  }

  private fun markActivityModified(activityId: ActivityId) {
    with(ACTIVITIES) {
      dslContext
          .update(ACTIVITIES)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .where(ID.eq(activityId))
          .execute()
    }
  }
}
