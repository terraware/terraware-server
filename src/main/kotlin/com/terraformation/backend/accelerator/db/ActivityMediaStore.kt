package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ActivityMediaModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class ActivityMediaStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun fetchOne(activityId: ActivityId, fileId: FileId): ActivityMediaModel {
    requirePermissions { readActivity(activityId) }

    return fetchByCondition(
            ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId)
                .and(ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId))
        )
        .firstOrNull() ?: throw FileNotFoundException(fileId)
  }

  fun updateMedia(
      activityId: ActivityId,
      fileId: FileId,
      applyFunc: (ActivityMediaModel) -> ActivityMediaModel,
  ) {
    requirePermissions { updateActivity(activityId) }

    val existing = fetchOne(activityId, fileId)
    val updated = applyFunc(existing)

    dslContext.transaction { _ ->
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
            .where(ACTIVITY_ID.eq(activityId))
            .and(FILE_ID.eq(fileId))
            .execute()
      }

      with(FILES) {
        dslContext
            .update(FILES)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .where(ID.eq(fileId))
            .execute()
      }
    }
  }

  private fun fetchByCondition(condition: Condition): List<ActivityMediaModel> {
    return dslContext
        .select(ACTIVITY_MEDIA_FILES.asterisk(), FILES.CREATED_BY, FILES.CREATED_TIME)
        .from(ACTIVITY_MEDIA_FILES)
        .join(FILES)
        .on(ACTIVITY_MEDIA_FILES.FILE_ID.eq(FILES.ID))
        .where(condition)
        .orderBy(ACTIVITY_MEDIA_FILES.FILE_ID)
        .fetch { ActivityMediaModel.of(it) }
  }
}
