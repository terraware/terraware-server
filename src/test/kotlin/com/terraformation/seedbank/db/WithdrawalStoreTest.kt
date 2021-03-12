package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.CreateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.UpdateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.WithdrawalPayload
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.daos.WithdrawalDao
import com.terraformation.seedbank.db.tables.pojos.Withdrawal
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.model.WithdrawalModel
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
  private lateinit var store: WithdrawalStore
  private lateinit var withdrawalDao: WithdrawalDao

  private val clock: Clock = mockk()

  private val accessionId = 9999L
  private val emptyAccessionFields = CreateAccessionRequestPayload()

  override val sequencesToReset: List<String>
    get() = listOf("withdrawal_id_seq")

  @BeforeEach
  fun setup() {
    store = WithdrawalStore(dslContext, clock)
    withdrawalDao = WithdrawalDao(dslContext.configuration())

    every { clock.instant() } returns Instant.now()

    // Insert a minimal accession so we can use its ID. The actual contents are irrelevant.
    dslContext
        .insertInto(ACCESSION)
        .set(ACCESSION.ID, accessionId)
        .set(ACCESSION.CREATED_TIME, Instant.now())
        .set(ACCESSION.SITE_MODULE_ID, config.siteModuleId)
        .set(ACCESSION.STATE_ID, AccessionState.InStorage)
        .execute()
  }

  @Test
  fun `fetches existing withdrawals`() {
    val pojos =
        listOf(
            Withdrawal(
                accessionId = accessionId,
                date = LocalDate.of(2021, 1, 1),
                seedsWithdrawn = 1,
                gramsWithdrawn = BigDecimal.TEN,
                notes = "notes 1",
                purposeId = WithdrawalPurpose.Broadcast,
                staffResponsible = "staff 1",
                destination = "dest 1",
                createdTime = Instant.EPOCH,
                updatedTime = Instant.now(),
            ),
            Withdrawal(
                accessionId = accessionId,
                date = LocalDate.of(2021, 1, 2),
                seedsWithdrawn = 2,
                gramsWithdrawn = BigDecimal.ONE,
                notes = "notes 2",
                purposeId = WithdrawalPurpose.Other,
                staffResponsible = "staff 2",
                destination = "dest 2",
                createdTime = Instant.EPOCH.plusSeconds(30),
                updatedTime = Instant.now(),
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
                seedsWithdrawn = pojos[0].seedsWithdrawn!!,
                gramsWithdrawn = pojos[0].gramsWithdrawn,
                destination = pojos[0].destination,
                staffResponsible = pojos[0].staffResponsible,
            ),
            WithdrawalModel(
                id = 2,
                accessionId = accessionId,
                date = pojos[1].date!!,
                notes = pojos[1].notes,
                purpose = pojos[1].purposeId!!,
                seedsWithdrawn = pojos[1].seedsWithdrawn!!,
                gramsWithdrawn = pojos[1].gramsWithdrawn,
                destination = pojos[1].destination,
                staffResponsible = pojos[1].staffResponsible,
            ),
        )

    withdrawalDao.insert(pojos)

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual?.toSet())
  }

  @Test
  fun `creates new withdrawals`() {
    val newWithdrawal =
        WithdrawalPayload(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            seedsWithdrawn = 1,
            staffResponsible = "staff 1",
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
                seedsWithdrawn = newWithdrawal.seedsWithdrawn!!,
                staffResponsible = newWithdrawal.staffResponsible,
            ),
        )

    store.updateWithdrawals(accessionId, emptyAccessionFields, emptyList(), listOf(newWithdrawal))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual?.toSet())
  }

  @Test
  fun `updates existing withdrawals`() {
    val initial =
        WithdrawalPayload(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            seedsWithdrawn = 1,
            staffResponsible = "staff 1",
        )
    val desired =
        initial.copy(
            id = 1L, seedsWithdrawn = 2, destination = "updated dest", notes = "updated notes")

    val expected =
        setOf(
            WithdrawalModel(
                id = 1,
                accessionId = accessionId,
                date = desired.date,
                destination = desired.destination,
                notes = desired.notes,
                purpose = desired.purpose,
                seedsWithdrawn = desired.seedsWithdrawn!!,
                staffResponsible = desired.staffResponsible,
            ),
        )

    store.updateWithdrawals(accessionId, emptyAccessionFields, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    store.updateWithdrawals(accessionId, emptyAccessionFields, afterInsert, listOf(desired))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual?.toSet())
  }

  @Test
  fun `rejects weight-based withdrawals if accession is missing seed weight data`() {
    val desiredWithdrawals =
        listOf(
            WithdrawalPayload(
                date = LocalDate.now(),
                purpose = WithdrawalPurpose.Other,
                gramsWithdrawn = BigDecimal.ONE))

    assertThrows<IllegalArgumentException>("No subset weight or subset count") {
      store.updateWithdrawals(
          accessionId, CreateAccessionRequestPayload(), emptyList(), desiredWithdrawals)
    }

    assertThrows<IllegalArgumentException>("Subset weight but no subset count") {
      store.updateWithdrawals(
          accessionId,
          UpdateAccessionRequestPayload(subsetWeightGrams = BigDecimal.ONE),
          emptyList(),
          desiredWithdrawals)
    }

    assertThrows<IllegalArgumentException>("Subset count but no subset weight") {
      store.updateWithdrawals(
          accessionId,
          UpdateAccessionRequestPayload(subsetCount = 1),
          emptyList(),
          desiredWithdrawals)
    }
  }

  @Test
  fun `rejects negative and zero weights`() {
    val accession =
        UpdateAccessionRequestPayload(subsetCount = 100, subsetWeightGrams = BigDecimal("2.00"))

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(
          accessionId,
          accession,
          emptyList(),
          listOf(
              WithdrawalPayload(
                  date = LocalDate.now(),
                  purpose = WithdrawalPurpose.Other,
                  gramsWithdrawn = BigDecimal("0.00000"))))
    }

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(
          accessionId,
          accession,
          emptyList(),
          listOf(
              WithdrawalPayload(
                  date = LocalDate.now(),
                  purpose = WithdrawalPurpose.Other,
                  gramsWithdrawn = BigDecimal("-100"))))
    }
  }

  @Test
  fun `computes seed count based on accession weight data and rounds up`() {
    val accession =
        UpdateAccessionRequestPayload(subsetCount = 100, subsetWeightGrams = BigDecimal("2.00"))
    val newWithdrawal =
        WithdrawalPayload(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.Other,
            gramsWithdrawn = BigDecimal("4.11"))
    val expected =
        listOf(
            WithdrawalModel(
                id = 1,
                accessionId = accessionId,
                date = newWithdrawal.date,
                purpose = newWithdrawal.purpose,
                gramsWithdrawn = newWithdrawal.gramsWithdrawn,
                // 4.11 * 100 / 2.0 = 205.5
                seedsWithdrawn = 206))

    store.updateWithdrawals(accessionId, accession, emptyList(), listOf(newWithdrawal))

    val actual = store.fetchWithdrawals(accessionId)
    assertEquals(expected, actual)
  }
}
