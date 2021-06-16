package com.terraformation.seedbank.db

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.daos.GerminationTestsDao
import com.terraformation.seedbank.db.tables.daos.WithdrawalsDao
import com.terraformation.seedbank.db.tables.pojos.GerminationTestsRow
import com.terraformation.seedbank.db.tables.pojos.WithdrawalsRow
import com.terraformation.seedbank.db.tables.references.ACCESSIONS
import com.terraformation.seedbank.grams
import com.terraformation.seedbank.milligrams
import com.terraformation.seedbank.model.WithdrawalModel
import com.terraformation.seedbank.seeds
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired

internal class WithdrawalStoreTest : DatabaseTest() {
  @Autowired private lateinit var config: TerrawareServerConfig
  private lateinit var germinationTestsDao: GerminationTestsDao
  private lateinit var store: WithdrawalStore
  private lateinit var withdrawalsDao: WithdrawalsDao

  private val clock: Clock = mockk()

  private val accessionId = 9999L
  private val germinationTestId = 9998L

  override val sequencesToReset: List<String>
    get() = listOf("withdrawal_id_seq")

  @BeforeEach
  fun setup() {
    germinationTestsDao = GerminationTestsDao(dslContext.configuration())
    store = WithdrawalStore(dslContext, clock)
    withdrawalsDao = WithdrawalsDao(dslContext.configuration())

    every { clock.instant() } returns Instant.now()

    insertSiteData()

    // Insert a minimal accession and germination test so we can use their IDs.
    dslContext
        .insertInto(ACCESSIONS)
        .set(ACCESSIONS.ID, accessionId)
        .set(ACCESSIONS.CREATED_TIME, Instant.now())
        .set(ACCESSIONS.SITE_MODULE_ID, config.siteModuleId)
        .set(ACCESSIONS.STATE_ID, AccessionState.InStorage)
        .execute()
  }

  @Test
  fun `fetches existing withdrawals`() {
    val pojos =
        listOf(
            WithdrawalsRow(
                accessionId = accessionId,
                date = LocalDate.of(2021, 1, 1),
                notes = "notes 1",
                purposeId = WithdrawalPurpose.Broadcast,
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
                purposeId = WithdrawalPurpose.Other,
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
                id = 1,
                accessionId = accessionId,
                date = pojos[0].date!!,
                notes = pojos[0].notes,
                purpose = pojos[0].purposeId!!,
                remaining = milligrams(100),
                destination = pojos[0].destination,
                staffResponsible = pojos[0].staffResponsible,
                withdrawn = milligrams(10000),
            ),
            WithdrawalModel(
                id = 2,
                accessionId = accessionId,
                date = pojos[1].date!!,
                notes = pojos[1].notes,
                purpose = pojos[1].purposeId!!,
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
                id = 1,
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
  fun `rejects new germination testing withdrawals without test IDs`() {
    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.GerminationTesting,
            remaining = grams(4),
            withdrawn = grams(1))

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(desired))
    }
  }

  @Test
  fun `rejects test IDs on non-germination-testing withdrawals`() {
    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            germinationTestId = germinationTestId,
            purpose = WithdrawalPurpose.Other,
            remaining = grams(4),
            withdrawn = grams(1))

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(desired))
    }
  }

  @Test
  fun `accepts new germination testing withdrawals with test IDs`() {
    germinationTestsDao.insert(
        GerminationTestsRow(
            id = germinationTestId,
            accessionId = accessionId,
            testType = GerminationTestType.Lab,
            remainingQuantity = BigDecimal(10),
            remainingUnitsId = SeedQuantityUnits.Grams))

    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            germinationTestId = germinationTestId,
            purpose = WithdrawalPurpose.GerminationTesting,
            remaining = grams(4),
            withdrawn = seeds(1))

    val expected =
        setOf(
            WithdrawalModel(
                id = 1,
                accessionId = accessionId,
                date = desired.date,
                destination = desired.destination,
                germinationTestId = germinationTestId,
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
  fun `does not allow modifying test IDs on existing germination testing withdrawals`() {
    germinationTestsDao.insert(
        GerminationTestsRow(
            id = germinationTestId,
            accessionId = accessionId,
            testType = GerminationTestType.Lab,
            remainingQuantity = BigDecimal(10),
            remainingUnitsId = SeedQuantityUnits.Grams))

    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            germinationTestId = germinationTestId,
            purpose = WithdrawalPurpose.GerminationTesting,
            remaining = grams(4),
            withdrawn = seeds(1))
    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val inserted = store.fetchWithdrawals(accessionId).first()

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(
          accessionId,
          listOf(inserted),
          listOf(inserted.copy(germinationTestId = germinationTestId + 1)))
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
            id = 1L, destination = "updated dest", notes = "updated notes", withdrawn = grams(2))

    val expected =
        setOf(
            WithdrawalModel(
                id = 1,
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
  fun `rejects attempts to change existing withdrawal purpose to germination testing`() {
    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            purpose = WithdrawalPurpose.Other,
            remaining = grams(4),
            withdrawn = grams(1),
        )
    val desired = initial.copy(id = 1L, purpose = WithdrawalPurpose.GerminationTesting)

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    assertThrows<IllegalArgumentException>("Cannot switch purpose to germination testing") {
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
