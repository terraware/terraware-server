package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.seedbank.event.AccessionCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionStateChangedEvent
import com.terraformation.backend.seedbank.event.AccessionStateChangedEventValues
import com.terraformation.backend.seedbank.event.AccessionUpdatedEvent
import com.terraformation.backend.seedbank.event.AccessionUpdatedEventValues
import com.terraformation.backend.seedbank.event.AccessionUploadedEvent
import com.terraformation.backend.seedbank.seeds
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class AccessionStoreEventTest : AccessionStoreTest() {
  @Nested
  inner class Create {
    @Test
    fun `create publishes AccessionCreatedEvent for a web accession`() {
      val model = create(accessionModel())

      publisher.assertEventPublished(
          AccessionCreatedEvent(
              accessionNumber = model.accessionNumber!!,
              dataSource = DataSource.Web,
              accessionId = model.id!!,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
      publisher.assertEventNotPublished<AccessionUploadedEvent>()
    }

    @Test
    fun `create publishes AccessionUploadedEvent for a file import`() {
      val model = create(accessionModel(source = DataSource.FileImport))

      publisher.assertEventPublished(
          AccessionUploadedEvent(
              accessionNumber = model.accessionNumber!!,
              accessionId = model.id!!,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
      publisher.assertEventNotPublished<AccessionCreatedEvent>()
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `update publishes AccessionUpdatedEvent carrying only the changed field`() {
      val model =
          create(accessionModel(processingNotes = "old notes", state = AccessionState.Processing))
      publisher.clear()

      store.update(model.copy(processingNotes = "new notes"))

      publisher.assertEventPublished(
          AccessionUpdatedEvent(
              changedFrom = AccessionUpdatedEventValues(processingNotes = "old notes"),
              changedTo = AccessionUpdatedEventValues(processingNotes = "new notes"),
              accessionId = model.id!!,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
    }

    @Test
    fun `update publishes AccessionUpdatedEvent when a previously null field becomes populated`() {
      val model = create(accessionModel(processingNotes = null, state = AccessionState.Processing))
      publisher.clear()

      store.update(model.copy(processingNotes = "notes"))

      publisher.assertEventPublished(
          AccessionUpdatedEvent(
              changedFrom = AccessionUpdatedEventValues(processingNotes = null),
              changedTo = AccessionUpdatedEventValues(processingNotes = "notes"),
              accessionId = model.id!!,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
    }

    @Test
    fun `update does not publish AccessionUpdatedEvent when no tracked field changed`() {
      val model =
          create(accessionModel(processingNotes = "notes", state = AccessionState.Processing))
      publisher.clear()

      store.update(model)

      publisher.assertEventNotPublished<AccessionUpdatedEvent>()
    }

    @Test
    fun `state transition publishes AccessionStateChangedEvent`() {
      val model = create(accessionModel(remaining = seeds(10), state = AccessionState.Drying))
      publisher.clear()

      store.update(model.copy(state = AccessionState.InStorage))

      publisher.assertEventPublished(
          AccessionStateChangedEvent(
              changedFrom = AccessionStateChangedEventValues(state = AccessionState.Drying),
              changedTo = AccessionStateChangedEventValues(state = AccessionState.InStorage),
              reason = "Accession has been edited",
              accessionId = model.id!!,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
    }
  }

  @Nested
  inner class CheckIn {
    @Test
    fun `checkIn publishes AccessionStateChangedEvent`() {
      val accessionId = create(accessionModel()).id!!
      publisher.clear()

      store.checkIn(accessionId)

      publisher.assertEventPublished(
          AccessionStateChangedEvent(
              changedFrom =
                  AccessionStateChangedEventValues(state = AccessionState.AwaitingCheckIn),
              changedTo =
                  AccessionStateChangedEventValues(state = AccessionState.AwaitingProcessing),
              reason = "Accession has been checked in",
              accessionId = accessionId,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
    }
  }

  @Nested
  inner class AssignProject {
    @Test
    fun `assignProject publishes AccessionUpdatedEvent carrying only the project change`() {
      val projectId: ProjectId = insertProject()
      val accessionId = create(accessionModel()).id!!
      publisher.clear()

      store.assignProject(projectId, listOf(accessionId))

      publisher.assertEventPublished(
          AccessionUpdatedEvent(
              changedFrom = AccessionUpdatedEventValues(projectId = null),
              changedTo = AccessionUpdatedEventValues(projectId = projectId),
              accessionId = accessionId,
              facilityId = facilityId,
              organizationId = organizationId,
          )
      )
    }

    @Test
    fun `assignProject does not publish for accessions whose project did not change`() {
      val projectId: ProjectId = insertProject()
      val model = create(accessionModel(projectId = projectId))
      publisher.clear()

      store.assignProject(projectId, listOf(model.id!!))

      publisher.assertEventNotPublished<AccessionUpdatedEvent>()
    }
  }
}
