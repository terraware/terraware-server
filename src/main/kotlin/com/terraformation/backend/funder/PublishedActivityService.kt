package com.terraformation.backend.funder

import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.funder.db.PublishedActivityStore
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class PublishedActivityService(
    private val publishedActivityStore: PublishedActivityStore,
) {
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
