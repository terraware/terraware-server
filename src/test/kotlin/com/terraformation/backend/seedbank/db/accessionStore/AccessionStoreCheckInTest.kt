package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class AccessionStoreCheckInTest : AccessionStoreTest() {
  @Test
  fun `checkIn of v2 accession transitions state to AwaitingProcessing`() {
    val initial = store.create(accessionModel())
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
                accessionId = initial.id,
                newStateId = AccessionState.AwaitingProcessing,
                oldStateId = AccessionState.AwaitingCheckIn,
                reason = "Accession has been checked in",
                updatedBy = user.userId,
                updatedTime = clock.instant(),
            )
        ),
        historyRecords,
    )
  }

  @Test
  fun `checkIn does not modify accession that is already checked in`() {
    val accessionId = create().id!!
    val checkInTime = Instant.ofEpochSecond(10)

    clock.instant = checkInTime
    store.checkIn(accessionId)

    clock.instant = Instant.ofEpochSecond(30)
    store.checkIn(accessionId)

    val updatedRow = accessionsDao.fetchOneById(accessionId)
    assertEquals(checkInTime, updatedRow?.modifiedTime, "Modified time")
  }

  @Test
  fun `throws exception if no permission to check in`() {
    insertOrganizationUser(role = Role.Contributor)

    val accessionId = create().id!!

    assertThrows<AccessDeniedException> { store.checkIn(accessionId) }
  }
}
