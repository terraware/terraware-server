package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.seeds
import java.time.LocalDate
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreStateTest : AccessionStoreTest() {
  @Test
  fun `update does not allow state to be changed back to Awaiting Check-In`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId, isManualState = true, state = AccessionState.Processing))

    val updated = store.updateAndFetch(initial.copy(state = AccessionState.AwaitingCheckIn))

    Assertions.assertEquals(AccessionState.Processing, updated.state)
  }

  @Test
  fun `update throws exception if caller tries to manually change to a v1-only state`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId, isManualState = true, state = AccessionState.Processing))

    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(state = AccessionState.Dried))
    }
  }

  @Test
  fun `state transitions to Processing when seed count entered`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(100)))
    val fetched = store.fetchOneById(initial.id!!)

    Assertions.assertEquals(AccessionState.Processing, fetched.state)
    Assertions.assertEquals(LocalDate.now(clock), fetched.processingStartDate)

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.Processing))
            .fetchInto(AccessionStateHistoryRow::class.java)

    Assertions.assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = AccessionId(1),
                newStateId = AccessionState.Processing,
                oldStateId = AccessionState.AwaitingCheckIn,
                reason = "Seed count/weight has been entered",
                updatedBy = user.userId,
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `fetchTimedStateTransitionCandidates matches correct dates based on state`() {
    val today = LocalDate.now(clock)
    val yesterday = today.minusDays(1)
    val tomorrow = today.plusDays(1)
    val twoWeeksAgo = today.minusDays(14)

    val shouldMatch =
        listOf(
            AccessionsRow(
                number = "ProcessingTimePassed",
                stateId = AccessionState.Processing,
                processingStartDate = twoWeeksAgo),
            AccessionsRow(
                number = "ProcessingToDrying",
                stateId = AccessionState.Processing,
                dryingStartDate = today),
            AccessionsRow(
                number = "ProcessedToDrying",
                stateId = AccessionState.Processed,
                dryingStartDate = today),
            AccessionsRow(
                number = "DryingToDried", stateId = AccessionState.Drying, dryingEndDate = today),
            AccessionsRow(
                number = "DryingToStorage",
                stateId = AccessionState.Drying,
                storageStartDate = today),
            AccessionsRow(
                number = "DriedToStorage",
                stateId = AccessionState.Dried,
                storageStartDate = yesterday),
        )

    val shouldNotMatch =
        listOf(
            AccessionsRow(
                number = "NoSeedCountYet",
                stateId = AccessionState.Pending,
                processingStartDate = twoWeeksAgo),
            AccessionsRow(
                number = "ProcessingTimeNotUpYet",
                stateId = AccessionState.Processing,
                processingStartDate = yesterday),
            AccessionsRow(
                number = "ProcessedToStorage",
                stateId = AccessionState.Processed,
                storageStartDate = today),
            AccessionsRow(
                number = "DriedToStorageTomorrow",
                stateId = AccessionState.Dried,
                storageStartDate = tomorrow),
        )

    (shouldMatch + shouldNotMatch).forEach { insertAccession(it) }

    val expected = shouldMatch.map { it.number!! }.toSortedSet()
    val actual =
        store.fetchTimedStateTransitionCandidates().map { it.accessionNumber!! }.toSortedSet()

    Assertions.assertEquals(expected, actual)
  }

  @Test
  fun `absence of deviceInfo causes source to be set to Web`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    Assertions.assertEquals(DataSource.Web, initial.source)
  }

  @Test
  fun `update rejects future storageStartDate`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(storageStartDate = LocalDate.now(clock).plusDays(1)))
    }
  }
}
