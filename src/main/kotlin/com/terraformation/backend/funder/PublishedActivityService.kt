package com.terraformation.backend.funder

import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.funder.db.PublishedActivityStore
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class PublishedActivityService(
    private val muxService: MuxService,
    private val publishedActivityStore: PublishedActivityStore,
    private val thumbnailService: ThumbnailService,
) {
  fun readMedia(
      activityId: ActivityId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readPublishedActivity(activityId) }

    publishedActivityStore.ensureFileExists(activityId, fileId)

    return thumbnailService.readFile(fileId, maxWidth, maxHeight)
  }

  fun getMuxStreamInfo(activityId: ActivityId, fileId: FileId): MuxStreamModel {
    requirePermissions { readPublishedActivity(activityId) }

    publishedActivityStore.ensureFileExists(activityId, fileId)

    return muxService.getMuxStream(fileId)
  }

  /**
   * Deletes the published version of an activity, including its media files, when the original
   * activity is deleted.
   */
  @EventListener
  fun on(event: ActivityDeletionStartedEvent) {
    @Suppress("DEPRECATION") publishedActivityStore.deletePublishedActivity(event.activityId)
  }

  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    @Suppress("DEPRECATION") publishedActivityStore.deletePublishedActivities(event.organizationId)
  }

  @EventListener
  fun on(event: ProjectDeletionStartedEvent) {
    @Suppress("DEPRECATION") publishedActivityStore.deletePublishedActivities(event.projectId)
  }
}
