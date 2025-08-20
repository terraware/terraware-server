package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.seedbank.tables.pojos.WithdrawalsRow
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.milligrams
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class WithdrawalStoreTest : DatabaseTest(), RunsAsUser {
  override val user: IndividualUser = mockUser()

  private lateinit var store: WithdrawalStore

  private val clock = TestClock()

  private lateinit var accessionId: AccessionId
  private lateinit var organizationId: OrganizationId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setup() {
    organizationId = insertOrganization()
    insertFacility()
    speciesId = insertSpecies()
    accessionId = insertAccession(stateId = AccessionState.InStorage)

    store = WithdrawalStore(dslContext, clock, Messages(), ParentStore(dslContext))

    clock.instant = Instant.ofEpochSecond(1000)
    every { user.canReadAccession(any()) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadOrganizationUser(organizationId, any()) } returns true
    every { user.canSetWithdrawalUser(any()) } returns true
  }

  @Test
  fun `fetches existing withdrawals`() {
    val otherUserId = insertUser(firstName = "Other", lastName = "User")

    val batchId1 = insertBatch(readyQuantity = 1, speciesId = speciesId)
    val batchId2 = insertBatch(readyQuantity = 1, speciesId = speciesId)

    val pojos =
        listOf(
            WithdrawalsRow(
                accessionId = accessionId,
                batchId = batchId1,
                date = LocalDate.of(2021, 1, 1),
                notes = "notes 1",
                purposeId = WithdrawalPurpose.Nursery,
                staffResponsible = "staff 1",
                destination = "dest 1",
                createdTime = Instant.EPOCH,
                updatedTime = clock.instant(),
                withdrawnBy = user.userId,
                withdrawnGrams = BigDecimal.TEN,
                withdrawnQuantity = BigDecimal(10000),
                withdrawnUnitsId = SeedQuantityUnits.Milligrams,
            ),
            WithdrawalsRow(
                accessionId = accessionId,
                batchId = batchId2,
                date = LocalDate.of(2021, 1, 2),
                notes = "notes 2",
                purposeId = null,
                staffResponsible = "staff 2",
                destination = "dest 2",
                createdTime = Instant.ofEpochSecond(30),
                updatedTime = clock.instant(),
                withdrawnBy = otherUserId,
                withdrawnGrams = null,
                withdrawnQuantity = BigDecimal(2),
                withdrawnUnitsId = SeedQuantityUnits.Seeds,
            ),
        )

    val expected =
        setOf(
            WithdrawalModel(
                accessionId = accessionId,
                batchId = batchId1,
                createdTime = Instant.EPOCH,
                date = pojos[0].date!!,
                destination = pojos[0].destination,
                notes = pojos[0].notes,
                purpose = pojos[0].purposeId,
                staffResponsible = pojos[0].staffResponsible,
                withdrawn = milligrams(10000),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
            WithdrawalModel(
                accessionId = accessionId,
                batchId = batchId2,
                createdTime = Instant.ofEpochSecond(30),
                date = pojos[1].date!!,
                destination = pojos[1].destination,
                notes = pojos[1].notes,
                purpose = pojos[1].purposeId,
                staffResponsible = pojos[1].staffResponsible,
                withdrawn = seeds(2),
                withdrawnByName = "Other User",
                withdrawnByUserId = otherUserId,
            ),
        )

    withdrawalsDao.insert(pojos)

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.map { it.copy(id = null) }.toSet())
  }

  @Test
  fun `creates new withdrawals`() {
    val newWithdrawal =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            staffResponsible = "staff 1",
            withdrawn = seeds(1),
        )

    val expected =
        listOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = newWithdrawal.date,
                destination = newWithdrawal.destination,
                notes = newWithdrawal.notes,
                purpose = newWithdrawal.purpose,
                staffResponsible = newWithdrawal.staffResponsible,
                withdrawn = seeds(1),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(newWithdrawal))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.map { it.copy(id = null) })
  }

  @Test
  fun `creates new nursery transfers`() {
    insertSpecies()
    insertFacility(type = FacilityType.Nursery)
    val batchId = insertBatch()

    val newWithdrawal =
        WithdrawalModel(
            batchId = batchId,
            date = LocalDate.now(),
            notes = "notes 1",
            purpose = WithdrawalPurpose.Nursery,
            staffResponsible = "staff 1",
            withdrawn = seeds(1),
        )

    val expected =
        listOf(
            WithdrawalModel(
                accessionId = accessionId,
                batchId = batchId,
                createdTime = clock.instant(),
                date = newWithdrawal.date,
                notes = newWithdrawal.notes,
                purpose = newWithdrawal.purpose,
                staffResponsible = newWithdrawal.staffResponsible,
                withdrawn = seeds(1),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(newWithdrawal))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.map { it.copy(id = null) })
  }

  @Test
  fun `allows new withdrawals to be attributed to other organization users`() {
    val otherUserId = insertUser(firstName = "Other", lastName = "User")
    insertOrganizationUser(otherUserId, organizationId)

    val newWithdrawal =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            staffResponsible = "staff 1",
            withdrawn = seeds(1),
            withdrawnByUserId = otherUserId,
        )

    val expected =
        listOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = newWithdrawal.date,
                destination = newWithdrawal.destination,
                notes = newWithdrawal.notes,
                purpose = newWithdrawal.purpose,
                staffResponsible = newWithdrawal.staffResponsible,
                withdrawn = seeds(1),
                withdrawnByName = "Other User",
                withdrawnByUserId = otherUserId,
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(newWithdrawal))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.map { it.copy(id = null) })
  }

  @Test
  fun `throws exception if user sets withdrawn by to another user and has no permission to read user`() {
    val otherUserId = insertUser()

    every { user.canReadOrganizationUser(organizationId, otherUserId) } returns false

    val newWithdrawal =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            staffResponsible = "staff 1",
            withdrawn = seeds(1),
            withdrawnByUserId = otherUserId,
        )

    assertThrows<UserNotFoundException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(newWithdrawal))
    }
  }

  @Test
  fun `ignores withdrawnByUserId on new withdrawal if user has no permission to set withdrawal users`() {
    val otherUserId = insertUser()

    every { user.canSetWithdrawalUser(any()) } returns false

    val newWithdrawal =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            staffResponsible = "staff 1",
            withdrawn = seeds(1),
            withdrawnByUserId = otherUserId,
        )

    val expected =
        listOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = LocalDate.now(),
                destination = "dest 1",
                notes = "notes 1",
                purpose = WithdrawalPurpose.Other,
                staffResponsible = "staff 1",
                withdrawn = seeds(1),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(newWithdrawal))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.map { it.copy(id = null) })
  }

  @Test
  fun `rejects new viability testing withdrawals without test IDs`() {
    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
            withdrawn = grams(1),
        )

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(desired))
    }
  }

  @Test
  fun `rejects test IDs on non-viability-testing withdrawals`() {
    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.Other,
            viabilityTestId = ViabilityTestId(9999),
            withdrawn = grams(1),
        )

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(desired))
    }
  }

  @Test
  fun `accepts new viability testing withdrawals with test IDs`() {
    val viabilityTestsRow =
        ViabilityTestsRow(accessionId = accessionId, testType = ViabilityTestType.Lab)
    viabilityTestsDao.insert(viabilityTestsRow)
    val viabilityTestId = viabilityTestsRow.id!!

    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
            viabilityTestId = viabilityTestId,
            withdrawn = seeds(1),
        )

    val expected =
        listOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = desired.date,
                destination = desired.destination,
                notes = desired.notes,
                purpose = desired.purpose,
                staffResponsible = desired.staffResponsible,
                viabilityTestId = viabilityTestId,
                withdrawn = seeds(1),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(desired))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.map { it.copy(id = null) })
  }

  @Test
  fun `does not allow modifying test IDs on existing viability testing withdrawals`() {
    val viabilityTestsRow =
        ViabilityTestsRow(accessionId = accessionId, testType = ViabilityTestType.Lab)
    viabilityTestsDao.insert(viabilityTestsRow)
    val viabilityTestId = viabilityTestsRow.id!!

    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
            viabilityTestId = viabilityTestId,
            withdrawn = seeds(1),
        )
    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val inserted = store.fetchWithdrawals(accessionId).first()

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(
          accessionId,
          listOf(inserted),
          listOf(inserted.copy(viabilityTestId = ViabilityTestId(viabilityTestId.value + 1))),
      )
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
            staffResponsible = "staff 1",
            withdrawn = grams(1),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    val desired =
        initial.copy(
            id = afterInsert[0].id!!,
            destination = "updated dest",
            notes = "updated notes",
            purpose = null,
            withdrawn = grams(2),
        )

    val expected =
        listOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = desired.date,
                destination = desired.destination,
                id = afterInsert[0].id!!,
                notes = desired.notes,
                purpose = desired.purpose,
                staffResponsible = desired.staffResponsible,
                withdrawn = grams(2),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
        )

    store.updateWithdrawals(accessionId, afterInsert, listOf(desired))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual)
  }

  @Test
  fun `update ignores withdrawnByUserId if user has no permission to set withdrawal users`() {
    val otherUserId = insertUser(firstName = "Other", lastName = "User")

    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            notes = "notes 1",
            purpose = WithdrawalPurpose.Other,
            staffResponsible = "staff 1",
            withdrawn = grams(1),
            withdrawnByUserId = otherUserId,
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    val desired =
        initial.copy(
            id = afterInsert[0].id!!,
            destination = "updated dest",
            notes = "updated notes",
            purpose = null,
            withdrawn = grams(2),
            withdrawnByUserId = user.userId,
        )

    val expected =
        listOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = desired.date,
                destination = desired.destination,
                id = afterInsert[0].id,
                notes = desired.notes,
                purpose = desired.purpose,
                staffResponsible = desired.staffResponsible,
                withdrawn = grams(2),
                withdrawnByName = "Other User",
                withdrawnByUserId = otherUserId,
            ),
        )

    every { user.canSetWithdrawalUser(accessionId) } returns false

    store.updateWithdrawals(accessionId, afterInsert, listOf(desired))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual)
  }

  @Test
  fun `rejects attempts to change existing withdrawal purpose to viability testing`() {
    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            destination = "dest 1",
            purpose = WithdrawalPurpose.Other,
            withdrawn = grams(1),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    val desired =
        initial.copy(id = afterInsert[0].id!!, purpose = WithdrawalPurpose.ViabilityTesting)

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
                  withdrawn = grams(0),
              )
          ),
      )
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
                  withdrawn = grams(-1),
              ),
          ),
      )
    }
  }

  @Test
  fun `fetches history descriptions based on purpose and quantity`() {
    val batchId = insertBatch()
    val viabilityTestsRow1 =
        ViabilityTestsRow(accessionId = accessionId, testType = ViabilityTestType.Lab)
    val viabilityTestsRow2 = viabilityTestsRow1.copy()
    viabilityTestsDao.insert(viabilityTestsRow1)
    viabilityTestsDao.insert(viabilityTestsRow2)
    val viabilityTestId1 = viabilityTestsRow1.id!!
    val viabilityTestId2 = viabilityTestsRow2.id!!

    // with quantity, no purpose
    insertSeedbankWithdrawal(withdrawnQuantity = BigDecimal.ONE)

    // with quantity, all 4 purposes
    insertSeedbankWithdrawal(withdrawnQuantity = BigDecimal(2), purpose = WithdrawalPurpose.Other)
    insertSeedbankWithdrawal(
        withdrawnQuantity = BigDecimal(3),
        purpose = WithdrawalPurpose.ViabilityTesting,
        viabilityTestId = viabilityTestId1,
    )
    insertSeedbankWithdrawal(
        withdrawnQuantity = BigDecimal(4),
        purpose = WithdrawalPurpose.OutPlanting,
    )
    insertSeedbankWithdrawal(withdrawnQuantity = BigDecimal(5), purpose = WithdrawalPurpose.Nursery)

    // no quantity, all 4 purposes
    insertSeedbankWithdrawal(purpose = WithdrawalPurpose.Other)
    insertSeedbankWithdrawal(
        purpose = WithdrawalPurpose.ViabilityTesting,
        viabilityTestId = viabilityTestId2,
    )
    insertSeedbankWithdrawal(purpose = WithdrawalPurpose.OutPlanting)
    insertSeedbankWithdrawal(purpose = WithdrawalPurpose.Nursery)

    // no quantity, no purpose
    insertSeedbankWithdrawal()

    val accessionHistoryDefaults =
        AccessionHistoryModel(
            batchId = batchId,
            createdTime = Instant.EPOCH,
            date = LocalDate.EPOCH,
            description = "withdrew 1 gram",
            fullName = user.fullName,
            type = AccessionHistoryType.Withdrawal,
            userId = user.userId,
        )

    val expected =
        listOf(
            accessionHistoryDefaults.copy(
                description = "withdrew 1 gram",
                type = AccessionHistoryType.Withdrawal,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew 2 grams for other",
                type = AccessionHistoryType.Withdrawal,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew 3 grams for viability testing",
                type = AccessionHistoryType.ViabilityTesting,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew 4 grams for outplanting",
                type = AccessionHistoryType.Withdrawal,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew 5 grams for nursery",
                type = AccessionHistoryType.Withdrawal,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew seeds for other",
                type = AccessionHistoryType.Withdrawal,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew seeds for viability testing",
                type = AccessionHistoryType.ViabilityTesting,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew seeds for outplanting",
                type = AccessionHistoryType.Withdrawal,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew seeds for nursery",
                type = AccessionHistoryType.Withdrawal,
            ),
            accessionHistoryDefaults.copy(
                description = "withdrew seeds",
                type = AccessionHistoryType.Withdrawal,
            ),
        )

    val actual = store.fetchHistory(accessionId)

    assertEquals(expected, actual, "descriptions should be based on purpose and quantity")
  }
}
