package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.seedbank.model.AccessionModel
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AccessionStoreCheckInTest : AccessionStoreTest() {
  @Test
  fun `checkIn transitions state to Pending`() {
    every { clock.instant() } returns Instant.EPOCH.plusMillis(600)

    val initial = store.create(AccessionModel(facilityId = facilityId))
    val updated = store.checkIn(initial.id!!)

    assertEquals(AccessionState.Pending, updated.state)
    assertEquals(
        Instant.EPOCH,
        updated.checkedInTime,
        "Checked-in time should be truncated to 1-second accuracy")

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.Pending))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = AccessionId(1),
                newStateId = AccessionState.Pending,
                oldStateId = AccessionState.AwaitingCheckIn,
                reason = "Accession has been checked in",
                updatedBy = user.userId,
                updatedTime = clock.instant())),
        historyRecords)

    val expected = updated.copy(latestObservedQuantityCalculated = false)
    assertEquals(store.fetchOneById(initial.id!!), expected, "Return value should match database")
  }

  @Test
  fun `checkIn of v2 accession transitions state to AwaitingProcessing`() {
    val initial = store.create(AccessionModel(facilityId = facilityId, isManualState = true))
    val updated = store.checkIn(initial.id!!)

    assertEquals(AccessionState.AwaitingProcessing, updated.state, "Accession state")

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.AwaitingProcessing))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = AccessionId(1),
                newStateId = AccessionState.AwaitingProcessing,
                oldStateId = AccessionState.AwaitingCheckIn,
                reason = "Accession has been checked in",
                updatedBy = user.userId,
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `checkIn does not modify accession that is already checked in`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.checkIn(initial.id!!)

    every { clock.instant() } returns Instant.EPOCH.plusSeconds(30)
    val updated = store.checkIn(initial.id!!)

    assertEquals(Instant.EPOCH, updated.checkedInTime, "Checked-in time")
  }

  @Test
  fun `checkedInTime in model is ignored by update`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    store.update(initial.copy(checkedInTime = Instant.EPOCH, collectors = listOf("test")))
    val updated = store.fetchOneById(initial.id!!)

    assertEquals(AccessionState.AwaitingCheckIn, updated.state, "State")
    assertNull(updated.checkedInTime, "Checked-in time")
  }
}
