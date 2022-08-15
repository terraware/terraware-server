package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.ViabilityTestType
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.milligrams
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class WithdrawalStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private lateinit var store: WithdrawalStore

  private val clock: Clock = mockk()

  private val accessionId = AccessionId(9999)
  private val viabilityTestId = ViabilityTestId(9998)

  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(WITHDRAWALS)

  @BeforeEach
  fun setup() {
    store = WithdrawalStore(dslContext, clock, Messages())

    every { clock.instant() } returns Instant.now()

    insertSiteData()

    // Insert a minimal accession in a state that allows withdrawals.
    with(ACCESSIONS) {
      dslContext
          .insertInto(ACCESSIONS)
          .set(ID, accessionId)
          .set(CREATED_BY, user.userId)
          .set(CREATED_TIME, Instant.now())
          .set(FACILITY_ID, facilityId)
          .set(MODIFIED_BY, user.userId)
          .set(MODIFIED_TIME, Instant.now())
          .set(STATE_ID, AccessionState.InStorage)
          .execute()
    }
  }

  @Test
  fun `fetches existing withdrawals`() {
    val pojos =
        listOf(
            WithdrawalsRow(
                accessionId = accessionId,
                date = LocalDate.of(2021, 1, 1),
                notes = "notes 1",
                purposeId = WithdrawalPurpose.Nursery,
                remainingGrams = BigDecimal(".1"),
                remainingQuantity = BigDecimal(100),
                remainingUnitsId = SeedQuantityUnits.Milligrams,
                staffResponsible = "staff 1",
                destination = "dest 1",
                createdTime = Instant.EPOCH,
                updatedTime = Instant.now(),
                withdrawnGrams = BigDecimal.TEN,
                withdrawnQuantity = BigDecimal(10000),
                withdrawnUnitsId = SeedQuantityUnits.Milligrams,
            ),
            WithdrawalsRow(
                accessionId = accessionId,
                date = LocalDate.of(2021, 1, 2),
                notes = "notes 2",
                purposeId = null,
                remainingGrams = BigDecimal(15),
                remainingQuantity = BigDecimal(15000),
                remainingUnitsId = SeedQuantityUnits.Milligrams,
                staffResponsible = "staff 2",
                destination = "dest 2",
                createdTime = Instant.EPOCH.plusSeconds(30),
                updatedTime = Instant.now(),
                withdrawnGrams = null,
                withdrawnQuantity = BigDecimal(2),
                withdrawnUnitsId = SeedQuantityUnits.Seeds,
            ),
        )

    val expected =
        setOf(
            WithdrawalModel(
                id = WithdrawalId(1),
                accessionId = accessionId,
                date = pojos[0].date!!,
                notes = pojos[0].notes,
                purpose = pojos[0].purposeId,
                remaining = milligrams(100),
                destination = pojos[0].destination,
                staffResponsible = pojos[0].staffResponsible,
                withdrawn = milligrams(10000),
            ),
            WithdrawalModel(
                id = WithdrawalId(2),
                accessionId = accessionId,
                date = pojos[1].date!!,
                notes = pojos[1].notes,
                purpose = pojos[1].purposeId,
                remaining = milligrams(15000),
                destination = pojos[1].destination,
                staffResponsible = pojos[1].staffResponsible,
                withdrawn = seeds(2),
            ),
        )

    withdrawalsDao.insert(pojos)

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `creates new withdrawals`() {
    val newWithdrawal =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            remaining = grams(10),
            staffResponsible = "staff 1",
            withdrawn = seeds(1),
        )

    val expected =
        setOf(
            WithdrawalModel(
                id = WithdrawalId(1),
                accessionId = accessionId,
                date = newWithdrawal.date,
                destination = newWithdrawal.destination,
                notes = newWithdrawal.notes,
                purpose = newWithdrawal.purpose,
                remaining = grams(10),
                staffResponsible = newWithdrawal.staffResponsible,
                withdrawn = seeds(1),
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(newWithdrawal))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `rejects new viability testing withdrawals without test IDs`() {
    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
            remaining = grams(4),
            withdrawn = grams(1))

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(desired))
    }
  }

  @Test
  fun `rejects test IDs on non-viability-testing withdrawals`() {
    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            viabilityTestId = viabilityTestId,
            purpose = WithdrawalPurpose.Other,
            remaining = grams(4),
            withdrawn = grams(1))

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(desired))
    }
  }

  @Test
  fun `accepts new viability testing withdrawals with test IDs`() {
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            id = viabilityTestId,
            accessionId = accessionId,
            testType = ViabilityTestType.Lab,
            remainingQuantity = BigDecimal(10),
            remainingUnitsId = SeedQuantityUnits.Grams))

    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            viabilityTestId = viabilityTestId,
            purpose = WithdrawalPurpose.ViabilityTesting,
            remaining = grams(4),
            withdrawn = seeds(1))

    val expected =
        setOf(
            WithdrawalModel(
                id = WithdrawalId(1),
                accessionId = accessionId,
                date = desired.date,
                destination = desired.destination,
                viabilityTestId = viabilityTestId,
                notes = desired.notes,
                purpose = desired.purpose,
                remaining = grams(4),
                staffResponsible = desired.staffResponsible,
                withdrawn = seeds(1),
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(desired))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `does not allow modifying test IDs on existing viability testing withdrawals`() {
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            id = viabilityTestId,
            accessionId = accessionId,
            testType = ViabilityTestType.Lab,
            remainingQuantity = BigDecimal(10),
            remainingUnitsId = SeedQuantityUnits.Grams))

    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            viabilityTestId = viabilityTestId,
            purpose = WithdrawalPurpose.ViabilityTesting,
            remaining = grams(4),
            withdrawn = seeds(1))
    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val inserted = store.fetchWithdrawals(accessionId).first()

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(
          accessionId,
          listOf(inserted),
          listOf(inserted.copy(viabilityTestId = ViabilityTestId(viabilityTestId.value + 1))))
    }
  }

  @Test
  fun `updates existing withdrawals`() {
    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            remaining = grams(4),
            staffResponsible = "staff 1",
            withdrawn = grams(1),
        )
    val desired =
        initial.copy(
            id = WithdrawalId(1),
            destination = "updated dest",
            notes = "updated notes",
            purpose = null,
            withdrawn = grams(2))

    val expected =
        setOf(
            WithdrawalModel(
                id = WithdrawalId(1),
                accessionId = accessionId,
                date = desired.date,
                destination = desired.destination,
                notes = desired.notes,
                purpose = desired.purpose,
                remaining = grams(4),
                staffResponsible = desired.staffResponsible,
                withdrawn = grams(2),
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    store.updateWithdrawals(accessionId, afterInsert, listOf(desired))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `rejects attempts to change existing withdrawal purpose to viability testing`() {
    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            purpose = WithdrawalPurpose.Other,
            remaining = grams(4),
            withdrawn = grams(1),
        )
    val desired = initial.copy(id = WithdrawalId(1), purpose = WithdrawalPurpose.ViabilityTesting)

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    assertThrows<IllegalArgumentException>("Cannot switch purpose to viability testing") {
      store.updateWithdrawals(accessionId, afterInsert, listOf(desired))
    }
  }

  @Test
  fun `rejects zero weights`() {
    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(
          accessionId,
          emptyList(),
          listOf(
              WithdrawalModel(
                  date = LocalDate.now(),
                  purpose = WithdrawalPurpose.Other,
                  remaining = grams(4),
                  withdrawn = grams(0))))
    }
  }

  @Test
  fun `rejects negative weights`() {
    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(
          accessionId,
          emptyList(),
          listOf(
              WithdrawalModel(
                  date = LocalDate.now(),
                  purpose = WithdrawalPurpose.Other,
                  remaining = grams(4),
                  withdrawn = grams(-1),
              ),
          ),
      )
    }
  }
}
