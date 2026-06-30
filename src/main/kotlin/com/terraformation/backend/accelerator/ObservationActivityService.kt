package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ActivityMediaStore
import com.terraformation.backend.accelerator.db.ActivityStore
import com.terraformation.backend.accelerator.event.ActivityMediaUpdatedEvent
import com.terraformation.backend.accelerator.model.NewActivityModel
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_OBSERVATIONS
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.ObservationResultsStoreV2
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationCompletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.event.EventListener

@Named
class ObservationActivityService(
    val activityMediaStore: ActivityMediaStore,
    val activityStore: ActivityStore,
    val dslContext: DSLContext,
    val observationResultsStore: ObservationResultsStoreV2,
    val observationService: ObservationService,
    val observationStore: ObservationStore,
    val parentStore: ParentStore,
    val plantingSiteStore: PlantingSiteStore,
    val projectStore: ProjectStore,
    val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(event: ActivityMediaUpdatedEvent) {
    try {
      if (event.triggeredBy is ObservationMediaFileEditedEvent) {
        // The activity media file update was triggered by an observation media file update, so no
        // need to update the observation media file.
        return
      }

      if (event.activityType != ActivityType.Monitoring) {
        return
      }

      val observationId =
          dslContext.fetchValue(
              ACTIVITY_OBSERVATIONS.OBSERVATION_ID,
              ACTIVITY_OBSERVATIONS.ACTIVITY_ID.eq(event.activityId),
          ) ?: return

      with(OBSERVATION_MEDIA_FILES) {
        val monitoringPlotId =
            dslContext.fetchValue(
                MONITORING_PLOT_ID,
                OBSERVATION_ID.eq(observationId).and(FILE_ID.eq(event.fileId)),
            ) ?: return

        observationService.updateMediaFile(observationId, monitoringPlotId, event.fileId) {
          it.copy(caption = event.caption)
        }
      }
    } catch (e: Exception) {
      log.error("Unable to propagate observation activity media edit to observation data", e)
    }
  }

  @EventListener
  fun on(event: ObservationCompletedEvent) {
    systemUser.run {
      try {
        val observation = observationStore.fetchObservationById(event.observationId)
        val plantingSite =
            plantingSiteStore.fetchSiteById(
                observation.plantingSiteId,
                PlantingSiteDepth.Site,
                simplified = true,
            )
        val projectId = plantingSite.projectId

        if (projectId == null) {
          log.debug(
              "Planting site ${plantingSite.id} not associated with a project; no activity created"
          )
          return@run
        }

        val project = projectStore.fetchOneById(projectId)
        if (project.phase == null) {
          log.debug(
              "Planting site ${plantingSite.id} project is not in an accelerator phase; no activity created"
          )
          return@run
        }

        val timeZone = plantingSite.timeZone ?: parentStore.getEffectiveTimeZone(plantingSite.id)

        dslContext.transaction { _ ->
          val activity =
              activityStore.create(
                  NewActivityModel(
                      activityDate = observation.completedTime!!.atZone(timeZone).toLocalDate(),
                      activityType = ActivityType.Monitoring,
                      description = null,
                      projectId = projectId,
                  )
              )

          dslContext
              .insertInto(ACTIVITY_OBSERVATIONS)
              .set(ACTIVITY_OBSERVATIONS.ACTIVITY_ID, activity.id)
              .set(ACTIVITY_OBSERVATIONS.OBSERVATION_ID, event.observationId)
              .execute()

          val results = observationResultsStore.fetchOneById(event.observationId)

          val plotResults =
              if (observation.isAdHoc) {
                listOfNotNull(results.adHocPlot)
              } else {
                results.strata
                    .flatMap { stratum ->
                      stratum.substrata.flatMap { substratum -> substratum.monitoringPlots }
                    }
                    .sortedBy { it.monitoringPlotNumber }
              }

          var listPosition = 1

          plotResults.forEach { plotResult ->
            val mediaFiles =
                plotResult.media
                    .sortedBy { it.fileId }
                    .sortedBy { file ->
                      // Corner / explanation photos first, then quadrat photos, then the rest.
                      when (file.type) {
                        ObservationMediaType.Plot if file.position != null -> 0
                        ObservationMediaType.Explanation -> 0
                        ObservationMediaType.Quadrat -> 1
                        ObservationMediaType.Plot,
                        ObservationMediaType.Soil -> 2
                      }
                    }

            mediaFiles.forEach { file ->
              val activityMediaTypeId = activityMediaTypeFor(file.contentType)

              with(ACTIVITY_MEDIA_FILES) {
                dslContext
                    .insertInto(ACTIVITY_MEDIA_FILES)
                    .set(ACTIVITY_ID, activity.id)
                    .set(ACTIVITY_MEDIA_TYPE_ID, activityMediaTypeId)
                    .set(CAPTION, file.caption)
                    .set(FILE_ID, file.fileId)
                    .set(IS_COVER_PHOTO, false)
                    .set(IS_HIDDEN_ON_MAP, false)
                    .set(LIST_POSITION, listPosition++)
                    .execute()
              }
            }
          }
        }
      } catch (e: Exception) {
        log.error("Unable to create activity for completed observation", e)
      }
    }
  }

  @EventListener
  fun on(event: ObservationMediaFileDeletedEvent) {
    try {
      // This will be a no-op if the observation had no activity.
      activityMediaStore.deleteForTrigger(event.fileId)
    } catch (e: Exception) {
      log.error("Unable to delete activity media file for observation", e)
    }
  }

  @EventListener
  fun on(event: ObservationMediaFileEditedEvent) {
    try {
      val activityId =
          dslContext.fetchValue(
              ACTIVITY_OBSERVATIONS.ACTIVITY_ID,
              ACTIVITY_OBSERVATIONS.OBSERVATION_ID.eq(event.observationId),
          ) ?: return

      activityMediaStore.updateMedia(activityId, event.fileId, event) {
        it.copy(caption = event.changedTo.caption)
      }
    } catch (e: Exception) {
      log.error("Unable to propagate observation media edit to activity media", e)
    }
  }

  @EventListener
  fun on(event: ObservationMediaFileUploadedEvent) {
    try {
      val activityId =
          dslContext.fetchValue(
              ACTIVITY_OBSERVATIONS.ACTIVITY_ID,
              ACTIVITY_OBSERVATIONS.OBSERVATION_ID.eq(event.observationId),
          ) ?: return

      activityStore.withLockedActivity(activityId) {
        with(ACTIVITY_MEDIA_FILES) {
          dslContext
              .insertInto(ACTIVITY_MEDIA_FILES)
              .set(ACTIVITY_ID, activityId)
              .set(ACTIVITY_MEDIA_TYPE_ID, activityMediaTypeFor(event.contentType))
              .set(CAPTION, event.caption)
              .set(FILE_ID, event.fileId)
              .set(IS_COVER_PHOTO, false)
              .set(IS_HIDDEN_ON_MAP, false)
              .set(
                  LIST_POSITION,
                  DSL.select(DSL.coalesce(DSL.max(LIST_POSITION).plus(1), 1))
                      .from(ACTIVITY_MEDIA_FILES)
                      .where(ACTIVITY_ID.eq(activityId)),
              )
              .execute()
        }
      }
    } catch (e: Exception) {
      log.error("Unable to propagate new observation media file to activity media", e)
    }
  }

  private fun activityMediaTypeFor(contentType: String): ActivityMediaType =
      if (contentType.startsWith("video/")) {
        ActivityMediaType.Video
      } else {
        ActivityMediaType.Photo
      }
}
