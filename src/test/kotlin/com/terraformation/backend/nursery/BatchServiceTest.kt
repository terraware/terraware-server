package com.terraformation.backend.nursery

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.tracking.db.DeliveryStore
import io.mockk.every
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val service: BatchService by lazy {
    val parentStore = ParentStore(dslContext)
    BatchService(
        BatchStore(
            batchDetailsHistoryDao,
            batchDetailsHistorySubLocationsDao,
            batchesDao,
            batchQuantityHistoryDao,
            batchWithdrawalsDao,
            clock,
            dslContext,
            TestEventPublisher(),
            facilitiesDao,
            IdentifierGenerator(clock, dslContext),
            parentStore,
            projectsDao,
            subLocationsDao,
            nurseryWithdrawalsDao),
        DeliveryStore(clock, deliveriesDao, dslContext, parentStore, plantingsDao),
        dslContext)
  }

  private val plantingSiteId by lazy { insertPlantingSite() }
  private val speciesId1 by lazy { insertSpecies(1) }
  private val speciesId2 by lazy { insertSpecies(2) }

  @BeforeEach
  fun setUp() {
    every { user.canCreateDelivery(any()) } returns true
    every { user.canReadBatch(any()) } returns true
    every { user.canUpdateBatch(any()) } returns true

    insertUser()
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
  }

  @Test
  fun `withdraw requires planting site ID for outplanting withdrawals`() {
    val batchId = insertBatch(speciesId = speciesId1, readyQuantity = 1)

    assertThrows<IllegalArgumentException> {
      service.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId,
                          germinatingQuantityWithdrawn = 0,
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 1)),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.EPOCH))
    }
  }

  @Test
  fun `withdraw does not require planting site ID for non-outplanting withdrawals`() {
    val batchId = insertBatch(speciesId = speciesId1, readyQuantity = 1)

    assertDoesNotThrow {
      service.withdraw(
          NewWithdrawalModel(
              batchWithdrawals =
                  listOf(
                      BatchWithdrawalModel(
                          batchId,
                          germinatingQuantityWithdrawn = 0,
                          notReadyQuantityWithdrawn = 0,
                          readyQuantityWithdrawn = 1)),
              facilityId = facilityId,
              id = null,
              purpose = WithdrawalPurpose.Other,
              withdrawnDate = LocalDate.EPOCH))
    }
  }

  @Test
  fun `withdraw from multiple batches of same species creates planting with total quantity`() {
    val species1Batch1 =
        insertBatch(
            speciesId = speciesId1,
            germinatingQuantity = 20,
            notReadyQuantity = 20,
            readyQuantity = 20)
    val species1Batch2 =
        insertBatch(
            speciesId = speciesId1,
            germinatingQuantity = 20,
            notReadyQuantity = 20,
            readyQuantity = 20)
    val species2Batch1 =
        insertBatch(
            speciesId = speciesId2,
            germinatingQuantity = 20,
            notReadyQuantity = 20,
            readyQuantity = 20)

    val withdrawal =
        service.withdraw(
            NewWithdrawalModel(
                batchWithdrawals =
                    listOf(
                        BatchWithdrawalModel(
                            species1Batch1,
                            germinatingQuantityWithdrawn = 1,
                            notReadyQuantityWithdrawn = 2,
                            readyQuantityWithdrawn = 4),
                        BatchWithdrawalModel(
                            species1Batch2,
                            germinatingQuantityWithdrawn = 0,
                            notReadyQuantityWithdrawn = 0,
                            readyQuantityWithdrawn = 8),
                        BatchWithdrawalModel(
                            species2Batch1,
                            germinatingQuantityWithdrawn = 0,
                            notReadyQuantityWithdrawn = 0,
                            readyQuantityWithdrawn = 16),
                    ),
                facilityId = facilityId,
                id = null,
                purpose = WithdrawalPurpose.OutPlant,
                withdrawnDate = LocalDate.EPOCH,
            ),
            plantingSiteId = plantingSiteId)

    assertNotNull(withdrawal.deliveryId, "Should have created delivery")

    val deliveriesRow = deliveriesDao.fetchOneById(withdrawal.deliveryId!!)!!
    assertEquals(plantingSiteId, deliveriesRow.plantingSiteId, "Delivery planting site ID")

    val plantingsRows = plantingsDao.findAll()
    assertEquals(2, plantingsRows.size, "Should have created a planting for each of 2 species")
    assertEquals(
        15, plantingsRows.first { it.speciesId == speciesId1 }.numPlants, "Species 1 quantity")
    assertEquals(
        16, plantingsRows.first { it.speciesId == speciesId2 }.numPlants, "Species 2 quantity")
  }
}
