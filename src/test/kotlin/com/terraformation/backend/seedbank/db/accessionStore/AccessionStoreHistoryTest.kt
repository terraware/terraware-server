package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionQuantityHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AccessionStoreHistoryTest : AccessionStoreTest() {
  @Test
  fun `update creates quantity history row if remaining quantity is edited`() {
    val initial =
        store.create(AccessionModel(facilityId = facilityId, state = AccessionState.Processing))

    store.update(initial.copy(latestObservedQuantityCalculated = false, remaining = seeds(10)))

    assertEquals(
        listOf(
            AccessionQuantityHistoryRow(
                accessionId = initial.id,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                historyTypeId = AccessionQuantityHistoryType.Observed,
                id = AccessionQuantityHistoryId(1),
                remainingQuantity = BigDecimal.TEN,
                remainingUnitsId = SeedQuantityUnits.Seeds)),
        accessionQuantityHistoryDao.findAll())
  }

  @Test
  fun `update does not create quantity history row if remaining quantity is not edited`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId, remaining = seeds(10), state = AccessionState.Processing))
    val initialHistory = accessionQuantityHistoryDao.findAll()

    store.update(initial.copy(latestObservedQuantityCalculated = false, remaining = seeds(10)))

    assertEquals(initialHistory, accessionQuantityHistoryDao.findAll())
  }

  @Test
  fun `returns history models in correct order`() {
    // The sequence of operations here:
    //
    // January 1: Accession created
    // January 1: Accession checked in (causes state to go to Awaiting Processing)
    // January 2: Seed quantity of 100 seeds entered and state set to Processing
    // January 3: 1 seed withdrawn
    // January 4: Viability test created with 29 seeds sown (causes a withdrawal to be created)
    // January 5: 50 seeds withdrawn with a withdrawal date of January 3 (causes state to go to
    //            Used Up)

    val createTime = Instant.EPOCH
    val checkInTime = createTime.plusSeconds(60)
    val processTime = checkInTime.plus(1, ChronoUnit.DAYS)
    val firstWithdrawalTime = processTime.plus(1, ChronoUnit.DAYS)
    val secondWithdrawalTime = firstWithdrawalTime.plus(1, ChronoUnit.DAYS)
    val backdatedWithdrawalTime = secondWithdrawalTime.plus(1, ChronoUnit.DAYS)

    val createUserId = UserId(20)
    val checkInUserId = UserId(30)
    val processUserId = UserId(40)
    val firstWithdrawerUserId = UserId(50)
    val viabilityTesterUserId = UserId(60)

    insertUser(createUserId, firstName = "First", lastName = "Last")
    insertUser(checkInUserId, firstName = null, lastName = null)
    insertUser(processUserId, firstName = "Bono", lastName = null)
    insertUser(firstWithdrawerUserId, firstName = "First", lastName = "Withdrawer")
    insertUser(viabilityTesterUserId, firstName = "Viability", lastName = "Tester")

    every { clock.instant() } returns createTime
    every { user.userId } returns createUserId

    val initial = store.create(AccessionModel(facilityId = facilityId))

    every { clock.instant() } returns checkInTime
    every { user.userId } returns checkInUserId

    store.checkIn(initial.id!!)

    every { clock.instant() } returns processTime
    every { user.userId } returns processUserId

    val withSeedQuantity =
        store.updateAndFetch(
            initial.copy(remaining = seeds(100), state = AccessionState.Processing))

    every { clock.instant() } returns firstWithdrawalTime

    val withFirstWithdrawal =
        store.updateAndFetch(
            withSeedQuantity.copy(
                withdrawals =
                    listOf(
                        WithdrawalModel(
                            date = LocalDate.ofInstant(firstWithdrawalTime, ZoneOffset.UTC),
                            purpose = WithdrawalPurpose.Nursery,
                            withdrawn = seeds(1),
                            withdrawnByUserId = firstWithdrawerUserId))))

    every { clock.instant() } returns secondWithdrawalTime

    val withSecondWithdrawal =
        store.updateAndFetch(
            withFirstWithdrawal.copy(
                viabilityTests =
                    listOf(
                        ViabilityTestModel(
                            seedsTested = 29,
                            startDate = LocalDate.ofInstant(secondWithdrawalTime, ZoneOffset.UTC),
                            testType = ViabilityTestType.Lab,
                            withdrawnByUserId = viabilityTesterUserId))))

    every { clock.instant() } returns backdatedWithdrawalTime

    store.updateAndFetch(
        withSecondWithdrawal.copy(
            withdrawals =
                withSecondWithdrawal.withdrawals +
                    WithdrawalModel(
                        // The date of the FIRST withdrawal, not the date this new model is
                        // being created; this should come after the viability testing withdrawal
                        // in the reverse-time-ordered history.
                        date = LocalDate.ofInstant(firstWithdrawalTime, ZoneOffset.UTC),
                        staffResponsible = "Backdated Withdrawer",
                        withdrawn = seeds(70))))

    // V1 COMPATIBILITY: Test the fallback to the staffResponsible field for withdrawals without
    // user IDs.
    withdrawalsDao.update(
        withdrawalsDao
            .fetchByStaffResponsible("Backdated Withdrawer")
            .first()
            .copy(withdrawnBy = null))

    val expected =
        listOf(
            AccessionHistoryModel(
                createdTime = backdatedWithdrawalTime,
                date = LocalDate.ofInstant(backdatedWithdrawalTime, ZoneOffset.UTC),
                description = "updated the status to Used Up",
                fullName = "Bono",
                type = AccessionHistoryType.StateChanged,
                userId = processUserId,
            ),
            AccessionHistoryModel(
                createdTime = secondWithdrawalTime,
                date = LocalDate.ofInstant(secondWithdrawalTime, ZoneOffset.UTC),
                description = "withdrew 29 seeds for viability testing",
                fullName = "Viability Tester",
                type = AccessionHistoryType.ViabilityTesting,
                userId = viabilityTesterUserId,
            ),
            AccessionHistoryModel(
                createdTime = backdatedWithdrawalTime,
                date = LocalDate.ofInstant(firstWithdrawalTime, ZoneOffset.UTC),
                description = "withdrew 70 seeds",
                fullName = "Backdated Withdrawer",
                type = AccessionHistoryType.Withdrawal,
                userId = null,
            ),
            AccessionHistoryModel(
                createdTime = firstWithdrawalTime,
                date = LocalDate.ofInstant(firstWithdrawalTime, ZoneOffset.UTC),
                description = "withdrew 1 seed for nursery",
                fullName = "First Withdrawer",
                type = AccessionHistoryType.Withdrawal,
                userId = firstWithdrawerUserId,
            ),
            AccessionHistoryModel(
                createdTime = processTime,
                date = LocalDate.ofInstant(processTime, ZoneOffset.UTC),
                description = "updated the status to Processing",
                fullName = "Bono",
                type = AccessionHistoryType.StateChanged,
                userId = processUserId,
            ),
            AccessionHistoryModel(
                createdTime = processTime,
                date = LocalDate.ofInstant(processTime, ZoneOffset.UTC),
                description = "updated the quantity to 100 seeds",
                fullName = "Bono",
                type = AccessionHistoryType.QuantityUpdated,
                userId = processUserId,
            ),
            AccessionHistoryModel(
                createdTime = checkInTime,
                date = LocalDate.ofInstant(checkInTime, ZoneOffset.UTC),
                description = "updated the status to Awaiting Processing",
                fullName = null,
                type = AccessionHistoryType.StateChanged,
                userId = checkInUserId,
            ),
            AccessionHistoryModel(
                createdTime = createTime,
                date = LocalDate.ofInstant(createTime, ZoneOffset.UTC),
                description = "created accession",
                fullName = "First Last",
                type = AccessionHistoryType.Created,
                userId = createUserId,
            ),
        )

    val actual = store.fetchHistory(initial.id!!)

    assertEquals(expected, actual)
  }

  @Test
  fun `state history row is inserted at creation time`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = AccessionId(1),
                newStateId = AccessionState.AwaitingCheckIn,
                reason = "Accession created",
                updatedBy = user.userId,
                updatedTime = clock.instant())),
        historyRecords)
  }
}
