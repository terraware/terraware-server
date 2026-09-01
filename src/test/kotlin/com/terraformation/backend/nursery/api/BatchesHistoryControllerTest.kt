package com.terraformation.backend.nursery.api

import com.terraformation.backend.TestClock
import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.nursery.BatchService
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.BatchWithdrawalModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get

class BatchesHistoryControllerTest : ControllerIntegrationTest() {
  @Autowired private lateinit var batchService: BatchService
  @Autowired private lateinit var batchStore: BatchStore
  @Autowired override lateinit var clock: TestClock

  @Nested
  inner class GetBatchHistory {
    @Test
    fun `returns history entries for backdated withdrawals`() {
      clock.instant = Instant.parse("2026-01-01T00:00:00Z")

      insertOrganization()
      insertOrganizationUser()
      val speciesId = insertSpecies()
      val otherFacilityId = insertFacility(type = FacilityType.SeedBank)
      val otherBatchId =
          insertBatch(
              batchNumber = "26-2-1-001",
              germinatingQuantity = 1000,
          )
      val accessionId = insertAccession()
      val facilityId = insertFacility(type = FacilityType.Nursery)

      val batch =
          batchStore.create(
              NewBatchModel(
                  accessionId = accessionId,
                  activeGrowthQuantity = 0,
                  addedDate = LocalDate.parse("2026-01-01"),
                  facilityId = facilityId,
                  germinatingQuantity = 100,
                  hardeningOffQuantity = 0,
                  readyQuantity = 0,
                  speciesId = speciesId,
              )
          )
      val batchId = batch.id

      clock.instant += Duration.ofDays(2)

      batchStore.updateQuantities(
          batchId,
          1,
          89,
          0,
          0,
          0,
          BatchQuantityHistoryType.Observed,
      )

      clock.instant += Duration.ofDays(1)

      val backdatedWithdrawal =
          batchService.withdraw(
              NewWithdrawalModel(
                  batchWithdrawals =
                      listOf(
                          BatchWithdrawalModel(
                              batchId = batchId,
                              destinationBatchId = otherBatchId,
                              germinatingQuantityWithdrawn = 13,
                          )
                      ),
                  destinationFacilityId = otherFacilityId,
                  facilityId = facilityId,
                  id = null,
                  purpose = WithdrawalPurpose.NurseryTransfer,
                  withdrawnDate = LocalDate.of(2026, 1, 2),
              )
          )

      mockMvc
          .get("/api/v1/nursery/batches/$batchId/history")
          .andExpectJson(
              """
                {
                  "history": [
                    {
                      "createdBy": ${user.userId},
                      "createdTime": "2026-01-01T00:00:00Z",
                      "subLocations": [],
                      "version": 1,
                      "type": "DetailsEdited"
                    },
                    {
                      "activeGrowthQuantity": 0,
                      "createdBy": ${user.userId},
                      "createdTime": "2026-01-01T00:00:00Z",
                      "germinatingQuantity": 100,
                      "hardeningOffQuantity": 0,
                      "readyQuantity": 0,
                      "version": 1,
                      "notReadyQuantity": 0,
                      "type": "QuantityEdited"
                    },
                    {
                      "activeGrowthQuantity": 0,
                      "createdBy": ${user.userId},
                      "createdTime": "2026-01-03T00:00:00Z",
                      "germinatingQuantity": 89,
                      "hardeningOffQuantity": 0,
                      "readyQuantity": 0,
                      "version": 2,
                      "notReadyQuantity": 0,
                      "type": "QuantityEdited"
                    },
                    {
                      "type": "OutgoingWithdrawal",
                      "activeGrowthQuantityWithdrawn": 0,
                      "createdBy": ${user.userId},
                      "createdTime": "2026-01-04T00:00:00Z",
                      "germinatingQuantityWithdrawn": 13,
                      "hardeningOffQuantity": 0,
                      "notReadyQuantityWithdrawn": 0,
                      "purpose": "Nursery Transfer",
                      "readyQuantityWithdrawn": 0,
                      "withdrawalId": ${backdatedWithdrawal.id},
                      "withdrawnDate": "2026-01-02"
                    }
                  ],
                  "status": "ok"
                }
              """
                  .trimIndent(),
              strict = true,
          )
    }
  }
}
