package com.terraformation.backend.eventlog

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.db.SimpleUserStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.api.AccessionPhotoSubjectPayload
import com.terraformation.backend.eventlog.api.AccessionSubjectPayload
import com.terraformation.backend.eventlog.api.CreatedActionPayload
import com.terraformation.backend.eventlog.api.CreatedFieldPayload
import com.terraformation.backend.eventlog.api.DeletedActionPayload
import com.terraformation.backend.eventlog.api.EventLogEntryPayload
import com.terraformation.backend.eventlog.api.FieldUpdatedActionPayload
import com.terraformation.backend.eventlog.api.ObservationPlotMediaSubjectPayload
import com.terraformation.backend.eventlog.api.PlantingSeasonAllocatedSpeciesSubjectPayload
import com.terraformation.backend.eventlog.api.ViabilityTestSubjectPayload
import com.terraformation.backend.eventlog.api.WithdrawalSubjectPayload
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.file.api.MediaKind
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.use
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesCreatedEventV1
import com.terraformation.backend.seedbank.event.AccessionCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionDeletedEvent
import com.terraformation.backend.seedbank.event.AccessionPhotoAddedEvent
import com.terraformation.backend.seedbank.event.AccessionPhotoDeletedEvent
import com.terraformation.backend.seedbank.event.AccessionPhotoReplacedEvent
import com.terraformation.backend.seedbank.event.AccessionUpdatedEvent
import com.terraformation.backend.seedbank.event.AccessionUpdatedEventValues
import com.terraformation.backend.seedbank.event.AccessionUploadedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestDeletedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEventValues
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEvent
import com.terraformation.backend.seedbank.event.WithdrawalDeletedEvent
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEvent
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEventValues
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEventValues
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventLogPayloadTransformerTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val messages = Messages()
  private val simpleUserStore: SimpleUserStore by lazy { SimpleUserStore(dslContext, messages) }
  private val transformer: EventLogPayloadTransformer by lazy {
    EventLogPayloadTransformer(dslContext, messages, simpleUserStore)
  }

  private val fileId = FileId(1)
  private val monitoringPlotId = MonitoringPlotId(2)
  private val observationId = ObservationId(3)
  private val organizationId = OrganizationId(4)
  private val plantingSeasonId = PlantingSeasonId(5)
  private val plantingSiteId = PlantingSiteId(6)
  private lateinit var deletedUserId: UserId
  private lateinit var knownUserId: UserId

  @BeforeEach
  fun setUp() {
    deletedUserId = insertUser(firstName = "X", lastName = "Y", deletedTime = Instant.EPOCH)
    knownUserId = insertUser(firstName = "Known", lastName = "User")

    insertOrganization()
    insertOrganizationUser(userId = user.userId)
    insertOrganizationUser(userId = knownUserId)
  }

  @Test
  fun `maps create and update events to payloads`() {
    val uploadEntry = eventLogEntry(knownUserId, uploadedEvent())
    val editEntry =
        eventLogEntry(
            deletedUserId,
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
            isOriginal = true,
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
                userId = deletedUserId,
                userName = "Former User",
            ),
        )

    val actual = transformer.eventsToPayloads(listOf(uploadEntry, editEntry))

    assertEquals(expected, actual)
  }

  @Test
  fun `localizes field names of FieldUpdated actions`() {
    val uploadEntry = eventLogEntry(knownUserId, uploadedEvent())
    val editEntry =
        eventLogEntry(
            deletedUserId,
            ObservationMediaFileEditedEvent(
                changedFrom = ObservationMediaFileEditedEventValues(caption = "a"),
                changedTo = ObservationMediaFileEditedEventValues(caption = "b"),
                fileId = fileId,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
            ),
        )

    val payloads =
        Locales.SPANISH.use { transformer.eventsToPayloads(listOf(uploadEntry, editEntry)) }

    assertEquals(2, payloads.size, "Number of payloads")
    assertEquals(
        "leyenda",
        (payloads[1].action as FieldUpdatedActionPayload).fieldName,
        "Field name",
    )
  }

  @Test
  fun `localizes special usernames of FieldUpdated actions`() {
    val uploadEntry = eventLogEntry(deletedUserId, uploadedEvent())

    val payloads = Locales.SPANISH.use { transformer.eventsToPayloads(listOf(uploadEntry)) }

    assertEquals("Usuario anterior", payloads[0].userName, "Username of nonexistent user")
  }

  @Test
  fun `marks subject as deleted in all events when delete event exists`() {
    val uploadEntry = eventLogEntry(knownUserId, uploadedEvent())
    val deleteEntry =
        eventLogEntry(
            deletedUserId,
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
            isOriginal = true,
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
                userId = deletedUserId,
                userName = "Former User",
            ),
        )

    val actual = transformer.eventsToPayloads(listOf(uploadEntry, deleteEntry))

    assertEquals(expected, actual)
  }

  @Test
  fun `FieldsCreatedPersistentEvent includes initial field values in CreatedActionPayload`() {
    val speciesId = insertSpecies(scientificName = "Quercus robur")

    val entry =
        eventLogEntry(
            knownUserId,
            PlantingSeasonAllocatedSpeciesCreatedEventV1(
                organizationId = inserted.organizationId,
                plantingSeasonId = plantingSeasonId,
                plantingSiteId = plantingSiteId,
                quantity = 42,
                speciesId = speciesId,
            ),
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action =
                    CreatedActionPayload(listOf(CreatedFieldPayload("quantity", listOf("42")))),
                subject =
                    PlantingSeasonAllocatedSpeciesSubjectPayload(
                        fullText = "Allocated species Quercus robur",
                        plantingSeasonId = plantingSeasonId,
                        plantingSiteId = plantingSiteId,
                        scientificName = "Quercus robur",
                        shortText = "Allocated species",
                        speciesId = speciesId,
                    ),
                timestamp = entry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            )
        )

    assertEquals(expected, transformer.eventsToPayloads(listOf(entry)))
  }

  @Test
  fun `maps Accession created and updated events to payloads`() {
    val accessionId = AccessionId(10)
    val facilityId = FacilityId(11)

    val createEntry =
        eventLogEntry(
            knownUserId,
            AccessionCreatedEvent(
                accessionNumber = "XYZ-1",
                dataSource = DataSource.Web,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )
    val updateEntry =
        eventLogEntry(
            knownUserId,
            AccessionUpdatedEvent(
                changedFrom = AccessionUpdatedEventValues(collectionSiteName = "old"),
                changedTo = AccessionUpdatedEventValues(collectionSiteName = "new"),
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )

    val subject =
        AccessionSubjectPayload(
            accessionId = accessionId,
            deleted = null,
            facilityId = facilityId,
            fullText = "Accession XYZ-1",
            shortText = "Accession",
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action = CreatedActionPayload(),
                subject = subject,
                timestamp = createEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action =
                    FieldUpdatedActionPayload(
                        fieldName = "collection site name",
                        changedFrom = listOf("old"),
                        changedTo = listOf("new"),
                    ),
                subject = subject,
                timestamp = updateEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
        )

    assertEquals(expected, transformer.eventsToPayloads(listOf(createEntry, updateEntry)))
  }

  @Test
  fun `maps Accession uploaded and deleted events and marks subject deleted`() {
    val accessionId = AccessionId(10)
    val facilityId = FacilityId(11)

    val uploadEntry =
        eventLogEntry(
            knownUserId,
            AccessionUploadedEvent(
                accessionNumber = "XYZ-2",
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )
    val deleteEntry =
        eventLogEntry(
            knownUserId,
            AccessionDeletedEvent(
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )

    val subject =
        AccessionSubjectPayload(
            accessionId = accessionId,
            deleted = true,
            facilityId = facilityId,
            fullText = "Accession XYZ-2",
            shortText = "Accession",
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
                userId = knownUserId,
                userName = "Known User",
            ),
        )

    assertEquals(expected, transformer.eventsToPayloads(listOf(uploadEntry, deleteEntry)))
  }

  @Test
  fun `maps Withdrawal events to payloads`() {
    val accessionId = AccessionId(10)
    val facilityId = FacilityId(11)
    val withdrawalId = WithdrawalId(20)

    val createEntry =
        eventLogEntry(
            knownUserId,
            WithdrawalCreatedEvent(
                date = LocalDate.of(2021, 1, 1),
                withdrawalId = withdrawalId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )
    val updateEntry =
        eventLogEntry(
            knownUserId,
            WithdrawalUpdatedEvent(
                changedFrom = WithdrawalUpdatedEventValues(notes = "a"),
                changedTo = WithdrawalUpdatedEventValues(notes = "b"),
                withdrawalId = withdrawalId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )
    val deleteEntry =
        eventLogEntry(
            knownUserId,
            WithdrawalDeletedEvent(
                withdrawalId = withdrawalId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )

    val subject =
        WithdrawalSubjectPayload(
            accessionId = accessionId,
            deleted = true,
            facilityId = facilityId,
            fullText = "Withdrawal 20",
            shortText = "Withdrawal",
            withdrawalId = withdrawalId,
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action = CreatedActionPayload(),
                subject = subject,
                timestamp = createEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action =
                    FieldUpdatedActionPayload(
                        fieldName = "notes",
                        changedFrom = listOf("a"),
                        changedTo = listOf("b"),
                    ),
                subject = subject,
                timestamp = updateEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action = DeletedActionPayload(),
                subject = subject,
                timestamp = deleteEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
        )

    assertEquals(
        expected,
        transformer.eventsToPayloads(listOf(createEntry, updateEntry, deleteEntry)),
    )
  }

  @Test
  fun `maps ViabilityTest events to payloads`() {
    val accessionId = AccessionId(10)
    val facilityId = FacilityId(11)
    val viabilityTestId = ViabilityTestId(30)

    val createEntry =
        eventLogEntry(
            knownUserId,
            ViabilityTestCreatedEvent(
                testType = ViabilityTestType.Lab,
                viabilityTestId = viabilityTestId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )
    val updateEntry =
        eventLogEntry(
            knownUserId,
            ViabilityTestUpdatedEvent(
                changedFrom = ViabilityTestUpdatedEventValues(seedsTested = 1),
                changedTo = ViabilityTestUpdatedEventValues(seedsTested = 2),
                viabilityTestId = viabilityTestId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )
    val deleteEntry =
        eventLogEntry(
            knownUserId,
            ViabilityTestDeletedEvent(
                viabilityTestId = viabilityTestId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )

    val subject =
        ViabilityTestSubjectPayload(
            accessionId = accessionId,
            deleted = true,
            facilityId = facilityId,
            fullText = "Viability test 30",
            shortText = "Viability test",
            viabilityTestId = viabilityTestId,
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action = CreatedActionPayload(),
                subject = subject,
                timestamp = createEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action =
                    FieldUpdatedActionPayload(
                        fieldName = "seeds tested",
                        changedFrom = listOf("1"),
                        changedTo = listOf("2"),
                    ),
                subject = subject,
                timestamp = updateEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action = DeletedActionPayload(),
                subject = subject,
                timestamp = deleteEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
        )

    assertEquals(
        expected,
        transformer.eventsToPayloads(listOf(createEntry, updateEntry, deleteEntry)),
    )
  }

  @Test
  fun `maps AccessionPhoto added and deleted events and marks subject deleted`() {
    val accessionId = AccessionId(10)
    val facilityId = FacilityId(11)
    val photoFileId = FileId(60)

    val addEntry =
        eventLogEntry(
            knownUserId,
            AccessionPhotoAddedEvent(
                filename = "new.jpg",
                contentType = "image/jpeg",
                fileId = photoFileId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )
    val deleteEntry =
        eventLogEntry(
            knownUserId,
            AccessionPhotoDeletedEvent(
                filename = "new.jpg",
                fileId = photoFileId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )

    val subject =
        AccessionPhotoSubjectPayload(
            accessionId = accessionId,
            deleted = true,
            facilityId = facilityId,
            fileId = photoFileId,
            fullText = "Photo new.jpg",
            shortText = "Photo",
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action = CreatedActionPayload(),
                subject = subject,
                timestamp = addEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
            EventLogEntryPayload(
                action = DeletedActionPayload(),
                subject = subject,
                timestamp = deleteEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            ),
        )

    assertEquals(expected, transformer.eventsToPayloads(listOf(addEntry, deleteEntry)))
  }

  @Test
  fun `maps AccessionPhotoReplaced event to a photo field update action`() {
    val accessionId = AccessionId(10)
    val facilityId = FacilityId(11)
    val newFileId = FileId(50)
    val replacedFileId = FileId(49)

    val replacedEntry =
        eventLogEntry(
            knownUserId,
            AccessionPhotoReplacedEvent(
                filename = "pic.jpg",
                replacedFileId = replacedFileId,
                fileId = newFileId,
                accessionId = accessionId,
                facilityId = facilityId,
                organizationId = organizationId,
            ),
        )

    val expected =
        listOf(
            EventLogEntryPayload(
                action =
                    FieldUpdatedActionPayload(
                        fieldName = "photo",
                        changedFrom = listOf(replacedFileId.toString()),
                        changedTo = listOf(newFileId.toString()),
                    ),
                subject =
                    AccessionPhotoSubjectPayload(
                        accessionId = accessionId,
                        deleted = null,
                        facilityId = facilityId,
                        fileId = newFileId,
                        fullText = "Photo pic.jpg",
                        shortText = "Photo",
                    ),
                timestamp = replacedEntry.createdTime,
                userId = knownUserId,
                userName = "Known User",
            )
        )

    assertEquals(expected, transformer.eventsToPayloads(listOf(replacedEntry)))
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
