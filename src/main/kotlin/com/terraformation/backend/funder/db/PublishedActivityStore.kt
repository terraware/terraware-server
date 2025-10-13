package com.terraformation.backend.funder.db

import com.terraformation.backend.accelerator.db.ActivityNotFoundException
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITIES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITY_MEDIA_FILES
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.funder.model.PublishedActivityMediaModel
import com.terraformation.backend.funder.model.PublishedActivityModel
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class PublishedActivityStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun publish(activityId: ActivityId) {
    requirePermissions { manageActivity(activityId) }

    ensureVerified(activityId)

    dslContext.transaction { _ ->
      publishActivityDetails(activityId)
      publishMediaFileDeletions(activityId)
      publishCurrentMediaFiles(activityId)
    }
  }

  fun fetchByProjectId(
      projectId: ProjectId,
      includeMedia: Boolean,
  ): List<PublishedActivityModel> {
    requirePermissions { listPublishedActivities(projectId) }

    return fetchByCondition(PUBLISHED_ACTIVITIES.PROJECT_ID.eq(projectId), includeMedia)
  }

  fun ensureFileExists(activityId: ActivityId, fileId: FileId) {
    val fileExists =
        dslContext.fetchExists(
            PUBLISHED_ACTIVITY_MEDIA_FILES,
            PUBLISHED_ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId),
            PUBLISHED_ACTIVITY_MEDIA_FILES.FILE_ID.eq(fileId),
        )
    if (!fileExists) {
      throw FileNotFoundException(fileId)
    }
  }

  @Deprecated("Do not call this except when handling deletion of the underlying activity")
  fun deletePublishedActivity(activityId: ActivityId) {
    deletePublishedMediaFiles(PUBLISHED_ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId))

    dslContext
        .deleteFrom(PUBLISHED_ACTIVITIES)
        .where(PUBLISHED_ACTIVITIES.ACTIVITY_ID.eq(activityId))
        .execute()
  }

  @Deprecated("Do not call this except when handling deletion of the organization")
  fun deletePublishedActivities(organizationId: OrganizationId) {
    deletePublishedMediaFiles(
        PUBLISHED_ACTIVITY_MEDIA_FILES.publishedActivities.projects.ORGANIZATION_ID.eq(
            organizationId
        )
    )

    dslContext
        .deleteFrom(PUBLISHED_ACTIVITIES)
        .where(PUBLISHED_ACTIVITIES.projects.ORGANIZATION_ID.eq(organizationId))
        .execute()
  }

  @Deprecated("Do not call this except when handling deletion of the project")
  fun deletePublishedActivities(projectId: ProjectId) {
    deletePublishedMediaFiles(
        PUBLISHED_ACTIVITY_MEDIA_FILES.publishedActivities.PROJECT_ID.eq(projectId)
    )

    dslContext
        .deleteFrom(PUBLISHED_ACTIVITIES)
        .where(PUBLISHED_ACTIVITIES.PROJECT_ID.eq(projectId))
        .execute()
  }

  private val geolocationField = PUBLISHED_ACTIVITY_MEDIA_FILES.GEOLOCATION.forMultiset()

  private val mediaMultiset =
      with(PUBLISHED_ACTIVITY_MEDIA_FILES) {
        DSL.multiset(
                DSL.select(
                        ACTIVITY_MEDIA_TYPE_ID,
                        ACTIVITY_ID,
                        CAPTION,
                        CAPTURED_DATE,
                        FILE_ID,
                        IS_COVER_PHOTO,
                        IS_HIDDEN_ON_MAP,
                        LIST_POSITION,
                        geolocationField,
                    )
                    .from(PUBLISHED_ACTIVITY_MEDIA_FILES)
                    .where(ACTIVITY_ID.eq(PUBLISHED_ACTIVITIES.ACTIVITY_ID))
                    .orderBy(LIST_POSITION)
            )
            .convertFrom { result ->
              result.map { PublishedActivityMediaModel.of(it, geolocationField) }
            }
      }

  private fun fetchByCondition(
      condition: Condition,
      includeMedia: Boolean,
  ): List<PublishedActivityModel> {
    return with(PUBLISHED_ACTIVITIES) {
      if (includeMedia) {
        dslContext
            .select(asterisk(), mediaMultiset)
            .from(PUBLISHED_ACTIVITIES)
            .where(condition)
            .orderBy(ACTIVITY_DATE, ACTIVITY_ID)
            .fetch { PublishedActivityModel.of(it, mediaMultiset) }
      } else {
        dslContext
            .selectFrom(PUBLISHED_ACTIVITIES)
            .where(condition)
            .orderBy(ACTIVITY_DATE, ACTIVITY_ID)
            .fetch { PublishedActivityModel.of(it, null) }
      }
    }
  }

  private fun publishActivityDetails(activityId: ActivityId) {
    with(PUBLISHED_ACTIVITIES) {
      dslContext
          .insertInto(
              PUBLISHED_ACTIVITIES,
              ACTIVITY_ID,
              PROJECT_ID,
              ACTIVITY_TYPE_ID,
              ACTIVITY_DATE,
              DESCRIPTION,
              IS_HIGHLIGHT,
              PUBLISHED_BY,
              PUBLISHED_TIME,
          )
          .select(
              DSL.select(
                      ACTIVITIES.ID,
                      ACTIVITIES.PROJECT_ID,
                      ACTIVITIES.ACTIVITY_TYPE_ID,
                      ACTIVITIES.ACTIVITY_DATE,
                      ACTIVITIES.DESCRIPTION,
                      ACTIVITIES.IS_HIGHLIGHT,
                      DSL.value(currentUser().userId),
                      DSL.value(clock.instant()),
                  )
                  .from(ACTIVITIES)
                  .where(ACTIVITIES.ID.eq(activityId)),
          )
          .onDuplicateKeyUpdate()
          .set(ACTIVITY_TYPE_ID, DSL.excluded(ACTIVITY_TYPE_ID))
          .set(ACTIVITY_DATE, DSL.excluded(ACTIVITY_DATE))
          .set(DESCRIPTION, DSL.excluded(DESCRIPTION))
          .set(IS_HIGHLIGHT, DSL.excluded(IS_HIGHLIGHT))
          .set(PUBLISHED_BY, DSL.excluded(PUBLISHED_BY))
          .set(PUBLISHED_TIME, DSL.excluded(PUBLISHED_TIME))
          .execute()
    }
  }

  /**
   * Deletes media files from a published activity if they are no longer present on the original
   * activity.
   */
  private fun publishMediaFileDeletions(activityId: ActivityId) {
    deletePublishedMediaFiles(
        PUBLISHED_ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId)
            .and(
                PUBLISHED_ACTIVITY_MEDIA_FILES.FILE_ID.notIn(
                    DSL.select(ACTIVITY_MEDIA_FILES.FILE_ID)
                        .from(ACTIVITY_MEDIA_FILES)
                        .where(ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId))
                )
            )
    )
  }

  private fun publishCurrentMediaFiles(activityId: ActivityId) {
    with(PUBLISHED_ACTIVITY_MEDIA_FILES) {
      dslContext
          .insertInto(
              PUBLISHED_ACTIVITY_MEDIA_FILES,
              ACTIVITY_ID,
              ACTIVITY_MEDIA_TYPE_ID,
              CAPTION,
              CAPTURED_DATE,
              FILE_ID,
              GEOLOCATION,
              IS_COVER_PHOTO,
              IS_HIDDEN_ON_MAP,
              LIST_POSITION,
          )
          .select(
              DSL.select(
                      ACTIVITY_MEDIA_FILES.ACTIVITY_ID,
                      ACTIVITY_MEDIA_FILES.ACTIVITY_MEDIA_TYPE_ID,
                      ACTIVITY_MEDIA_FILES.CAPTION,
                      ACTIVITY_MEDIA_FILES.CAPTURED_DATE,
                      ACTIVITY_MEDIA_FILES.FILE_ID,
                      ACTIVITY_MEDIA_FILES.GEOLOCATION,
                      ACTIVITY_MEDIA_FILES.IS_COVER_PHOTO,
                      ACTIVITY_MEDIA_FILES.IS_HIDDEN_ON_MAP,
                      ACTIVITY_MEDIA_FILES.LIST_POSITION,
                  )
                  .from(ACTIVITY_MEDIA_FILES)
                  .where(ACTIVITY_MEDIA_FILES.ACTIVITY_ID.eq(activityId)),
          )
          .onConflict(FILE_ID)
          .doUpdate()
          .set(ACTIVITY_MEDIA_TYPE_ID, DSL.excluded(ACTIVITY_MEDIA_TYPE_ID))
          .set(CAPTION, DSL.excluded(CAPTION))
          .set(CAPTURED_DATE, DSL.excluded(CAPTURED_DATE))
          .set(GEOLOCATION, DSL.excluded(GEOLOCATION))
          .set(IS_COVER_PHOTO, DSL.excluded(IS_COVER_PHOTO))
          .set(IS_HIDDEN_ON_MAP, DSL.excluded(IS_HIDDEN_ON_MAP))
          .set(LIST_POSITION, DSL.excluded(LIST_POSITION))
          .execute()
    }
  }

  private fun deletePublishedMediaFiles(condition: Condition) {
    with(PUBLISHED_ACTIVITY_MEDIA_FILES) {
      val deletedFileIds =
          dslContext
              .deleteFrom(PUBLISHED_ACTIVITY_MEDIA_FILES)
              .where(condition)
              .returning(FILE_ID)
              .fetch(FILE_ID.asNonNullable())

      deletedFileIds.forEach { fileId ->
        eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
      }
    }
  }

  private fun ensureVerified(activityId: ActivityId) {
    val status =
        dslContext.fetchValue(ACTIVITIES.ACTIVITY_STATUS_ID, ACTIVITIES.ID.eq(activityId))
            ?: throw ActivityNotFoundException(activityId)
    if (status != ActivityStatus.Verified) {
      throw CannotPublishUnverifiedActivityException(activityId)
    }
  }
}
