package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.seedbank.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.milligrams
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class WithdrawalStoreTest : DatabaseTest(), RunsAsUser {
  override val user: IndividualUser = mockUser()

  private lateinit var store: WithdrawalStore

  private val clock = TestClock()

  private val accessionId = AccessionId(9999)
  private val viabilityTestId = ViabilityTestId(9998)

  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(WITHDRAWALS)

  @BeforeEach
  fun setup() {
    store = WithdrawalStore(dslContext, clock, Messages(), ParentStore(dslContext))

    clock.instant = Instant.ofEpochSecond(1000)
    every { user.canReadAccession(any()) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadOrganizationUser(organizationId, any()) } returns true
    every { user.canSetWithdrawalUser(any()) } returns true

    insertSiteData()

    // Insert a minimal accession in a state that allows withdrawals.
    with(ACCESSIONS) {
      dslContext
          .insertInto(ACCESSIONS)
          .set(ID, accessionId)
          .set(CREATED_BY, user.userId)
          .set(CREATED_TIME, clock.instant())
          .set(DATA_SOURCE_ID, DataSource.FileImport)
          .set(FACILITY_ID, facilityId)
          .set(MODIFIED_BY, user.userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(STATE_ID, AccessionState.InStorage)
          .execute()
    }
  }

  @Test
  fun `fetches existing withdrawals`() {
    val otherUserId = UserId(10)
    insertUser(otherUserId, firstName = "Other", lastName = "User")

    val pojos =
        listOf(
            WithdrawalsRow(
                accessionId = accessionId,
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
                createdTime = Instant.EPOCH,
                date = pojos[0].date!!,
                destination = pojos[0].destination,
                id = WithdrawalId(1),
                notes = pojos[0].notes,
                purpose = pojos[0].purposeId,
                staffResponsible = pojos[0].staffResponsible,
                withdrawn = milligrams(10000),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = Instant.ofEpochSecond(30),
                date = pojos[1].date!!,
                destination = pojos[1].destination,
                id = WithdrawalId(2),
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
            staffResponsible = "staff 1",
            withdrawn = seeds(1),
        )

    val expected =
        setOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = newWithdrawal.date,
                destination = newWithdrawal.destination,
                id = WithdrawalId(1),
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

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `allows new withdrawals to be attributed to other organization users`() {
    val otherUserId = UserId(20)
    insertUser(otherUserId, firstName = "Other", lastName = "User")
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
        setOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = newWithdrawal.date,
                destination = newWithdrawal.destination,
                id = WithdrawalId(1),
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

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `throws exception if user sets withdrawn by to another user and has no permission to read user`() {
    val otherUserId = UserId(20)
    insertUser(otherUserId)

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
    val otherUserId = UserId(20)
    insertUser(otherUserId)

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
                id = WithdrawalId(1),
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

    assertEquals(expected, actual)
  }

  @Test
  fun `rejects new viability testing withdrawals without test IDs`() {
    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
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
            purpose = WithdrawalPurpose.Other,
            viabilityTestId = viabilityTestId,
            withdrawn = grams(1))

    assertThrows<IllegalArgumentException> {
      store.updateWithdrawals(accessionId, emptyList(), listOf(desired))
    }
  }

  @Test
  fun `accepts new viability testing withdrawals with test IDs`() {
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            id = viabilityTestId, accessionId = accessionId, testType = ViabilityTestType.Lab))

    val desired =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
            viabilityTestId = viabilityTestId,
            withdrawn = seeds(1))

    val expected =
        setOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = desired.date,
                destination = desired.destination,
                id = WithdrawalId(1),
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

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `does not allow modifying test IDs on existing viability testing withdrawals`() {
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            id = viabilityTestId, accessionId = accessionId, testType = ViabilityTestType.Lab))

    val initial =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
            viabilityTestId = viabilityTestId,
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
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = desired.date,
                destination = desired.destination,
                id = WithdrawalId(1),
                notes = desired.notes,
                purpose = desired.purpose,
                staffResponsible = desired.staffResponsible,
                withdrawn = grams(2),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    store.updateWithdrawals(accessionId, afterInsert, listOf(desired))

    val actual = store.fetchWithdrawals(accessionId)

    assertEquals(expected, actual.toSet())
  }

  @Test
  fun `update ignores withdrawnByUserId if user has no permission to set withdrawal users`() {
    val otherUserId = UserId(20)
    insertUser(otherUserId, firstName = "Other", lastName = "User")

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
    val desired =
        initial.copy(
            id = WithdrawalId(1),
            destination = "updated dest",
            notes = "updated notes",
            purpose = null,
            withdrawn = grams(2),
            withdrawnByUserId = user.userId,
        )

    val expected =
        setOf(
            WithdrawalModel(
                accessionId = accessionId,
                createdTime = clock.instant(),
                date = desired.date,
                destination = desired.destination,
                id = WithdrawalId(1),
                notes = desired.notes,
                purpose = desired.purpose,
                staffResponsible = desired.staffResponsible,
                withdrawn = grams(2),
                withdrawnByName = "Other User",
                withdrawnByUserId = otherUserId,
            ),
        )

    store.updateWithdrawals(accessionId, emptyList(), listOf(initial))
    val afterInsert = store.fetchWithdrawals(accessionId)

    every { user.canSetWithdrawalUser(accessionId) } returns false

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
                  date = LocalDate.now(), purpose = WithdrawalPurpose.Other, withdrawn = grams(0))))
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
}
