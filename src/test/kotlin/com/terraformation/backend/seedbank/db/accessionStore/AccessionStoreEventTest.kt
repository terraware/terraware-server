package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.seedbank.event.AccessionCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionDeletedEvent
import com.terraformation.backend.seedbank.event.AccessionQuantityUpdatedEvent
import com.terraformation.backend.seedbank.event.AccessionQuantityUpdatedEventValues
import com.terraformation.backend.seedbank.event.AccessionStateChangedEvent
import com.terraformation.backend.seedbank.event.AccessionStateChangedEventValues
import com.terraformation.backend.seedbank.event.AccessionUpdatedEvent
import com.terraformation.backend.seedbank.event.AccessionUpdatedEventValues
import com.terraformation.backend.seedbank.event.AccessionUploadedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestCreatedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEvent
import com.terraformation.backend.seedbank.model.AccessionUpdateContext
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import com.terraformation.backend.seedbank.seeds
import java.time.Duration
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `manual remaining quantity edit publishes AccessionQuantityUpdatedEvent`() {
      val model = create(accessionModel(state = AccessionState.Processing))
      publisher.clear()

      store.update(
          model.copy(latestObservedQuantityCalculated = false, remaining = seeds(10)),
          AccessionUpdateContext(remainingQuantityNotes = "got more seeds"),
      )

      publisher.assertEventPublished(
          AccessionQuantityUpdatedEvent(
              changedFrom = AccessionQuantityUpdatedEventValues(quantity = null),
              changedTo = AccessionQuantityUpdatedEventValues(quantity = seeds(10)),
              notes = "got more seeds",
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
  inner class Delete {
    @Test
    fun `delete publishes AccessionDeletedEvent`() {
      val accessionId = create(accessionModel()).id!!
      publisher.clear()

      store.delete(accessionId)

      publisher.assertEventPublished(
          AccessionDeletedEvent(
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

  @Nested
  inner class ViabilityTests {
    @Test
    fun `adding a viability test publishes ViabilityTestCreatedEvent`() {
      val startDate = LocalDate.of(2021, 4, 1)
      val initial = create().andUpdate { it.copy(remaining = seeds(100)) }
      publisher.clear()

      val updated = initial.andUpdate {
        it.addViabilityTest(
            ViabilityTestModel(
                seedsTested = 5,
                startDate = startDate,
                testType = ViabilityTestType.Lab,
            )
        )
      }
      val test = updated.viabilityTests[0]

      publisher.assertEventPublished { event ->
        event is ViabilityTestCreatedEvent &&
            event.viabilityTestId == test.id &&
            event.testType == ViabilityTestType.Lab &&
            event.seedsTested == 5 &&
            event.startDate == startDate &&
            event.accessionId == initial.id &&
            event.facilityId == facilityId &&
            event.organizationId == organizationId
      }
    }

    @Test
    fun `attaching a viability test to a new withdrawal publishes exactly one ViabilityTestCreatedEvent`() {
      val createdEvents = mutableListOf<ViabilityTestCreatedEvent>()
      publisher.register<ViabilityTestCreatedEvent> { createdEvents.add(it) }

      val accession = createAccessionWithViabilityTest()
      val test = accession.viabilityTests[0]

      assertEquals(1, createdEvents.size, "Number of ViabilityTestCreatedEvent published")
      assertEquals(test.id, createdEvents.first().viabilityTestId, "Viability test ID")
    }

    @Test
    fun `editing a viability test publishes ViabilityTestUpdatedEvent`() {
      val initial =
          create()
              .andUpdate { it.copy(remaining = seeds(100)) }
              .andAdvanceClock(Duration.ofSeconds(1))
              .andUpdate {
                it.addViabilityTest(
                    ViabilityTestModel(seedsTested = 1, testType = ViabilityTestType.Lab)
                )
              }
      val testId = initial.viabilityTests[0].id!!
      publisher.clear()

      val desired =
          initial.copy(
              viabilityTests =
                  listOf(
                      ViabilityTestModel(
                          id = testId,
                          notes = "new notes",
                          seedsTested = 5,
                          testType = ViabilityTestType.Lab,
                      )
                  )
          )
      store.update(desired)

      publisher.assertEventPublished { event ->
        event is ViabilityTestUpdatedEvent &&
            event.viabilityTestId == testId &&
            event.accessionId == initial.id &&
            event.facilityId == facilityId &&
            event.organizationId == organizationId &&
            event.changedFrom.seedsTested == 1 &&
            event.changedTo.seedsTested == 5 &&
            event.changedTo.notes == "new notes"
      }
    }

    @Test
    fun `editing recordings publishes ViabilityTestUpdatedEvent when total germinated changes but rounded percent is unchanged`() {
      val startDate = LocalDate.of(2021, 4, 1)
      val initial =
          create()
              .andUpdate { it.copy(remaining = seeds(500)) }
              .andAdvanceClock(Duration.ofSeconds(1))
              .andUpdate {
                it.addViabilityTest(
                    ViabilityTestModel(
                        seedsTested = 200,
                        startDate = startDate,
                        testResults =
                            listOf(
                                ViabilityTestResultModel(
                                    recordingDate = startDate,
                                    seedsGerminated = 100,
                                )
                            ),
                        testType = ViabilityTestType.Lab,
                    )
                )
              }
      val test = initial.viabilityTests[0]
      val testId = test.id!!
      // Both 100/200 and 101/200 round down to a viability percent of 50.
      assertEquals(100, test.totalSeedsGerminated, "Initial total seeds germinated")
      assertEquals(50, test.viabilityPercent, "Initial viability percent")
      publisher.clear()

      val desired =
          initial.updateViabilityTest(testId) {
            it.copy(
                testResults =
                    listOf(
                        ViabilityTestResultModel(recordingDate = startDate, seedsGerminated = 101)
                    )
            )
          }
      store.update(desired)

      publisher.assertEventPublished { event ->
        event is ViabilityTestUpdatedEvent &&
            event.viabilityTestId == testId &&
            event.accessionId == initial.id &&
            event.facilityId == facilityId &&
            event.organizationId == organizationId &&
            event.changedFrom.totalSeedsGerminated == 100 &&
            event.changedTo.totalSeedsGerminated == 101 &&
            event.changedFrom.viabilityPercent == null &&
            event.changedTo.viabilityPercent == null
      }
    }
  }
}
