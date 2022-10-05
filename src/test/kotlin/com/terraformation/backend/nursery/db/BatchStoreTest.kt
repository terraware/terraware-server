package com.terraformation.backend.nursery.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.api.CreateBatchRequestPayload
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class BatchStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  override val tablesToResetSequences = listOf(BATCHES, BATCH_QUANTITY_HISTORY)

  private val clock: Clock = mockk()
  private val store: BatchStore by lazy {
    BatchStore(
        batchesDao,
        batchQuantityHistoryDao,
        clock,
        dslContext,
        IdentifierGenerator(clock, dslContext),
        ParentStore(dslContext),
    )
  }

  private val speciesId = SpeciesId(1)

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertFacility(name = "Nursery", type = FacilityType.Nursery)
    insertSpecies(speciesId)

    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC
    every { user.canCreateBatch(any()) } returns true
    every { user.canReadBatch(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
  }

  @Nested
  inner class CreateBatch {
    @Test
    fun `creates new batches`() {
      val inputRow =
          CreateBatchRequestPayload(
                  addedDate = LocalDate.of(2022, 1, 2),
                  facilityId = facilityId,
                  germinatingQuantity = 0,
                  notes = "notes",
                  notReadyQuantity = 1,
                  readyByDate = LocalDate.of(2022, 3, 4),
                  readyQuantity = 2,
                  speciesId = speciesId,
              )
              .toRow()

      val expectedBatch =
          BatchesRow(
              addedDate = LocalDate.of(2022, 1, 2),
              batchNumber = "19700101000",
              createdBy = user.userId,
              createdTime = clock.instant(),
              facilityId = facilityId,
              germinatingQuantity = 0,
              id = BatchId(1),
              latestObservedGerminatingQuantity = 0,
              latestObservedNotReadyQuantity = 1,
              latestObservedReadyQuantity = 2,
              latestObservedTime = clock.instant(),
              modifiedBy = user.userId,
              modifiedTime = clock.instant(),
              notes = "notes",
              notReadyQuantity = 1,
              organizationId = organizationId,
              readyByDate = LocalDate.of(2022, 3, 4),
              readyQuantity = 2,
              speciesId = speciesId,
              version = 1)

      val expectedHistory =
          listOf(
              BatchQuantityHistoryRow(
                  batchId = BatchId(1),
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  historyTypeId = BatchQuantityHistoryType.Observed,
                  id = BatchQuantityHistoryId(1),
                  germinatingQuantity = 0,
                  notReadyQuantity = 1,
                  readyQuantity = 2))

      val returnedBatch = store.create(inputRow)
      val writtenBatch = batchesDao.fetchOneById(BatchId(1))
      val writtenHistory = batchQuantityHistoryDao.findAll()

      assertEquals(expectedBatch, returnedBatch, "Batch as returned by function")
      assertEquals(expectedBatch, writtenBatch, "Batch as written to database")
      assertEquals(expectedHistory, writtenHistory, "Inserted history row")
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canCreateBatch(facilityId) } returns false
      every { user.canReadFacility(facilityId) } returns true

      assertThrows<AccessDeniedException> { store.create(makeBatchesRow()) }
    }

    @Test
    fun `throws exception if facility is not a nursery`() {
      val seedBankFacilityId = FacilityId(2)
      insertFacility(seedBankFacilityId, type = FacilityType.SeedBank)

      assertThrows<FacilityTypeMismatchException> {
        store.create(makeBatchesRow().copy(facilityId = seedBankFacilityId))
      }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns database row`() {
      val batchId = insertBatch(speciesId = speciesId)

      val expected = batchesDao.fetchOneById(batchId)
      val actual = store.fetchOneById(batchId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      val batchId = insertBatch(speciesId = speciesId)

      every { user.canReadBatch(batchId) } returns false

      assertThrows<BatchNotFoundException> { store.fetchOneById(batchId) }
    }
  }

  @Nested
  inner class GetSpeciesSummary {
    @Test
    fun `does not include germinating quantities in total withdrawn`() {
      val batchId = insertBatch(speciesId = speciesId)
      val withdrawalId = insertWithdrawal()
      insertBatchWithdrawal(
          batchId = batchId,
          germinatingQuantityWithdrawn = 1,
          notReadyQuantityWithdrawn = 2,
          readyQuantityWithdrawn = 4,
          withdrawalId = withdrawalId,
      )

      val summary = store.getSpeciesSummary(speciesId)

      assertEquals(6, summary.totalWithdrawn, "Total withdrawn")
    }

    @Test
    fun `does not include germinating quantities in loss rate`() {
      val batchId =
          insertBatch(
              germinatingQuantity = 10,
              notReadyQuantity = 1,
              readyQuantity = 1,
              speciesId = speciesId,
          )
      val withdrawalId = insertWithdrawal(purpose = WithdrawalPurpose.Dead)
      insertBatchWithdrawal(
          batchId = batchId,
          germinatingQuantityWithdrawn = 20,
          notReadyQuantityWithdrawn = 2,
          readyQuantityWithdrawn = 3,
          withdrawalId = withdrawalId,
      )

      val summary = store.getSpeciesSummary(speciesId)

      // 5 dead withdrawals / 7 total past + current seedlings = 71.4%
      assertEquals(71, summary.lossRate, "Loss rate")
    }

    @Test
    fun `rounds loss rate to nearest integer`() {
      val batchId = insertBatch(speciesId = speciesId, readyQuantity = 197)
      val withdrawalId = insertWithdrawal(purpose = WithdrawalPurpose.Dead)
      insertBatchWithdrawal(
          batchId = batchId, withdrawalId = withdrawalId, notReadyQuantityWithdrawn = 3)

      val summary = store.getSpeciesSummary(speciesId)

      assertEquals(2, summary.lossRate, "Should round 1.5% up to 2%")
    }

    @Test
    fun `includes nurseries that have fully-withdrawn batches`() {
      val facilityId2 = FacilityId(2)
      insertFacility(facilityId2, name = "Other Nursery", type = FacilityType.Nursery)
      insertBatch(speciesId = speciesId, facilityId = facilityId)
      insertBatch(speciesId = speciesId, facilityId = facilityId, readyQuantity = 1)
      insertBatch(speciesId = speciesId, facilityId = facilityId2)

      val summary = store.getSpeciesSummary(speciesId)

      assertEquals(listOf(facilityId, facilityId2), summary.nurseries.map { it.id })
      assertEquals(listOf("Nursery", "Other Nursery"), summary.nurseries.map { it.name })
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canReadSpecies(speciesId) } returns false

      assertThrows<SpeciesNotFoundException> { store.getSpeciesSummary(speciesId) }
    }
  }

  private fun makeBatchesRow() =
      BatchesRow(
          addedDate = LocalDate.now(clock),
          createdBy = user.userId,
          createdTime = clock.instant(),
          facilityId = facilityId,
          germinatingQuantity = 0,
          latestObservedGerminatingQuantity = 0,
          latestObservedNotReadyQuantity = 1,
          latestObservedReadyQuantity = 2,
          latestObservedTime = clock.instant(),
          modifiedBy = user.userId,
          modifiedTime = clock.instant(),
          notReadyQuantity = 1,
          organizationId = organizationId,
          readyQuantity = 2,
          speciesId = speciesId,
      )
}
