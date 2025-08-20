package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.seedbank.seeds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreStateTest : AccessionStoreTest() {
  @Test
  fun `update does not allow state to be changed back to Awaiting Check-In`() {
    val initial = store.create(accessionModel(state = AccessionState.Processing))

    val updated = store.updateAndFetch(initial.copy(state = AccessionState.AwaitingCheckIn))

    assertEquals(AccessionState.Processing, updated.state)
  }

  @Test
  fun `update throws exception if caller tries to manually change to a v1-only state`() {
    val initial = store.create(accessionModel(state = AccessionState.Processing))

    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(state = AccessionState.Dried))
    }
  }

  @Test
  fun `state changes cause history entries to be inserted`() {
    val initial = store.create(accessionModel(remaining = seeds(10), state = AccessionState.Drying))
    store.update(initial.copy(state = AccessionState.InStorage))
    val fetched = store.fetchOneById(initial.id!!)

    assertEquals(AccessionState.InStorage, fetched.state)

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.InStorage))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = initial.id!!,
                newStateId = AccessionState.InStorage,
                oldStateId = AccessionState.Drying,
                reason = "Accession has been edited",
                updatedBy = user.userId,
                updatedTime = clock.instant(),
            )
        ),
        historyRecords,
    )
  }

  @Test
  fun `absence of deviceInfo causes source to be set to Web`() {
    val initial = store.create(accessionModel())
    assertEquals(DataSource.Web, initial.source)
  }
}
