package com.terraformation.backend.eventlog.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.OrganizationCreatedEvent
import com.terraformation.backend.customer.event.OrganizationPersistentEvent
import com.terraformation.backend.customer.event.OrganizationRenamedEvent
import com.terraformation.backend.customer.event.ProjectCreatedEvent
import com.terraformation.backend.customer.event.ProjectPersistentEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.EventLogPayloadContext
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.file.api.MediaKind
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFilePersistentEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import io.swagger.v3.oas.annotations.media.Schema
import kotlin.reflect.KClass

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface EventSubjectPayload {
  @get:Schema(
      description =
          "If this is true, the entity referred to by this subject has been deleted. This " +
              "property will be omitted if the entity still exists, i.e., this property will " +
              "always be true if it exists."
  )
  val deleted: Boolean?
    get() = null

  @get:Schema(
      description =
          "A localized extended human-readable description of the subject of the event, suitable " +
              "for display in cases where events for many subjects are being shown in the same " +
              "list.",
      example = "Project Backyard Garden",
  )
  val fullText: String

  @get:Schema(
      description =
          "A localized short human-readable name (often a single word) for the subject of the " +
              "event, suitable for display in cases where only events for a single subject are " +
              "being shown or where the subject doesn't need to be distinguished from others " +
              "of the same type.",
      example = "Project",
  )
  val shortText: String
}

@JsonTypeName("ObservationPlotMedia")
data class ObservationPlotMediaSubjectPayload(
    override val deleted: Boolean?,
    val fileId: FileId,
    override val fullText: String,
    val mediaKind: MediaKind,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: ObservationMediaFilePersistentEvent,
        context: EventLogPayloadContext,
    ): ObservationPlotMediaSubjectPayload {
      val createEvent =
          context.first<ObservationMediaFileUploadedEvent> { it.fileId == event.fileId }
      val deleteEvent =
          context.firstOrNull<ObservationMediaFileDeletedEvent> { it.fileId == event.fileId }

      val mediaKind = MediaKind.forMimeType(createEvent.contentType)
      val mediaKindName = mediaKind.getDisplayName(currentUser().locale)
      val fullText =
          context.subjectFullText<ObservationPlotMediaSubjectPayload>(
              mediaKindName,
              event.fileId.value,
          )

      return ObservationPlotMediaSubjectPayload(
          deleted = if (deleteEvent != null) true else null,
          fileId = event.fileId,
          fullText = fullText,
          mediaKind = mediaKind,
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          shortText = mediaKindName,
      )
    }
  }
}

@JsonTypeName("Organization")
data class OrganizationSubjectPayload(
    override val fullText: String,
    val organizationId: OrganizationId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: OrganizationPersistentEvent,
        context: EventLogPayloadContext,
    ): OrganizationSubjectPayload {
      val name = getPreviousName(event, context)

      return OrganizationSubjectPayload(
          fullText = context.subjectFullText<OrganizationSubjectPayload>(name),
          organizationId = event.organizationId,
          shortText = context.subjectShortText<OrganizationSubjectPayload>(),
      )
    }

    fun getPreviousName(
        event: OrganizationPersistentEvent,
        context: EventLogPayloadContext,
    ): String {
      val lastRename =
          context.lastEventBefore<OrganizationRenamedEvent>(event) {
            it.organizationId == event.organizationId
          }
      return lastRename?.changedTo?.name
          ?: context
              .first<OrganizationCreatedEvent> { it.organizationId == event.organizationId }
              .name
    }
  }
}

@JsonTypeName("Project")
data class ProjectSubjectPayload(
    override val fullText: String,
    val projectId: ProjectId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: ProjectPersistentEvent,
        context: EventLogPayloadContext,
    ): ProjectSubjectPayload {
      val name = getPreviousName(event, context)

      return ProjectSubjectPayload(
          fullText = context.subjectFullText<ProjectSubjectPayload>(name),
          projectId = event.projectId,
          shortText = context.subjectShortText<ProjectSubjectPayload>(),
      )
    }

    fun getPreviousName(event: ProjectPersistentEvent, context: EventLogPayloadContext): String {
      val lastRename =
          context.lastEventBefore<ProjectRenamedEvent>(event) { it.projectId == event.projectId }
      return lastRename?.name
          ?: context.first<ProjectCreatedEvent> { it.projectId == event.projectId }.name
    }
  }
}

/**
 * Types of subjects that can be returned by the event log query API. Each subject maps to an
 * interface that's implemented by events related to that subject.
 *
 * The entries in this enum must match the [JsonTypeName] annotations on the subject payload
 * classes.
 */
enum class EventSubjectName(val eventInterface: KClass<out PersistentEvent>) {
  ObservationPlotMedia(ObservationMediaFilePersistentEvent::class),
  Organization(OrganizationPersistentEvent::class),
  Project(ProjectPersistentEvent::class),
}
