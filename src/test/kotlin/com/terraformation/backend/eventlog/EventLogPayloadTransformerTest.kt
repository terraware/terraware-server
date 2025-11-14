package com.terraformation.backend.eventlog

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.SimpleUserStore
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.api.CreatedActionPayload
import com.terraformation.backend.eventlog.api.DeletedActionPayload
import com.terraformation.backend.eventlog.api.EventLogEntryPayload
import com.terraformation.backend.eventlog.api.FieldUpdatedActionPayload
import com.terraformation.backend.eventlog.api.ObservationPlotMediaSubjectPayload
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.file.api.MediaKind
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEventValues
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventLogPayloadTransformerTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val messages = Messages()
  private val simpleUserStore: SimpleUserStore = mockk()
  private val transformer = EventLogPayloadTransformer(messages, simpleUserStore)

  private val fileId = FileId(1)
  private val monitoringPlotId = MonitoringPlotId(2)
  private val observationId = ObservationId(3)
  private val organizationId = OrganizationId(4)
  private val plantingSiteId = PlantingSiteId(5)
  private val knownUserId = UserId(6)
  private val unknownUserId = UserId(7)

  @BeforeEach
  fun setUp() {
    every { simpleUserStore.fetchSimpleUsersById(any()) }
        .answers { mapOf(knownUserId to SimpleUserModel(knownUserId, "Known User")) }
  }

  @Test
  fun `maps create and update events to payloads`() {
    val uploadEntry = eventLogEntry(knownUserId, uploadedEvent())
    val editEntry =
        eventLogEntry(
            unknownUserId,
            ObservationMediaFileEditedEvent(
                changedFrom = ObservationMediaFileEditedEventValues(caption = "before"),
                changedTo = ObservationMediaFileEditedEventValues(caption = "after"),
                fileId = fileId,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
            ),
        )

    val subject =
        ObservationPlotMediaSubjectPayload(
            deleted = null,
            fileId = fileId,
            fullText = "Photo $fileId",
            mediaKind = MediaKind.Photo,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            plantingSiteId = plantingSiteId,
            shortText = "Photo",
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action = CreatedActionPayload(),
                subject = subject,
                timestamp = uploadEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action =
                    FieldUpdatedActionPayload(
                        fieldName = "caption",
                        changedFrom = listOf("before"),
                        changedTo = listOf("after"),
                    ),
                subject = subject,
                timestamp = editEntry.createdTime,
                userId = unknownUserId,
                userName = "Former User",
            ),
        )

    val actual = transformer.eventsToPayloads(listOf(uploadEntry, editEntry))

    assertEquals(expected, actual)
  }

  @Test
  fun `marks subject as deleted in all events when delete event exists`() {
    val uploadEntry = eventLogEntry(knownUserId, uploadedEvent())
    val deleteEntry =
        eventLogEntry(
            unknownUserId,
            ObservationMediaFileDeletedEvent(
                fileId = fileId,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
            ),
        )

    val subject =
        ObservationPlotMediaSubjectPayload(
            deleted = true, // This will be set on the created action, not just the deleted one
            fileId = fileId,
            fullText = "Photo $fileId",
            mediaKind = MediaKind.Photo,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            plantingSiteId = plantingSiteId,
            shortText = "Photo",
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action = CreatedActionPayload(),
                subject = subject,
                timestamp = uploadEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action = DeletedActionPayload(),
                subject = subject,
                timestamp = deleteEntry.createdTime,
                userId = unknownUserId,
                userName = "Former User",
            ),
        )

    val actual = transformer.eventsToPayloads(listOf(uploadEntry, deleteEntry))

    assertEquals(expected, actual)
  }

  private fun uploadedEvent(): ObservationMediaFileUploadedEvent =
      ObservationMediaFileUploadedEvent(
          caption = "caption",
          contentType = "image/png",
          fileId = fileId,
          geolocation = null,
          isOriginal = true,
          monitoringPlotId = monitoringPlotId,
          observationId = observationId,
          organizationId = organizationId,
          plantingSiteId = plantingSiteId,
          position = ObservationPlotPosition.SouthwestCorner,
          type = ObservationMediaType.Plot,
      )

  private var nextEventLogId: Long = 1

  private fun eventLogEntry(
      userId: UserId,
      event: PersistentEvent,
  ): EventLogEntry<PersistentEvent> =
      EventLogEntry(
          createdBy = userId,
          createdTime = Instant.ofEpochSecond(nextEventLogId),
          event = event,
          id = EventLogId(nextEventLogId++),
      )
}
