package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.nursery.model.ExistingBatchModel
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreFetchTest : BatchStoreTest() {
  @Test
  fun `returns model from database row`() {
    val accessionNumber = "2023-01-01"
    val accessionId = insertAccession(facilityId = facilityId, number = accessionNumber)

    val batchId =
        insertBatch(
            BatchesRow(
                accessionId = accessionId,
                notes = "Notes",
                substrateId = BatchSubstrate.Moss,
                substrateNotes = "Substrate Notes",
                treatmentId = SeedTreatment.Chemical,
                treatmentNotes = "Treatment Notes",
            ),
            batchNumber = "23-2-1-008",
            germinatingQuantity = 64,
            notReadyQuantity = 128,
            readyByDate = LocalDate.of(2023, 6, 7),
            readyQuantity = 256,
            speciesId = speciesId,
        )

    val subLocationId1 = insertSubLocation()
    insertBatchSubLocation()
    val subLocationId2 = insertSubLocation()
    insertBatchSubLocation()

    insertWithdrawal()
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 1, notReadyQuantityWithdrawn = 2, readyQuantityWithdrawn = 4)
    insertWithdrawal()
    insertBatchWithdrawal(
        germinatingQuantityWithdrawn = 8,
        notReadyQuantityWithdrawn = 16,
        readyQuantityWithdrawn = 32)

    val expected =
        ExistingBatchModel(
            accessionId = accessionId,
            accessionNumber = accessionNumber,
            addedDate = LocalDate.EPOCH,
            batchNumber = "23-2-1-008",
            facilityId = facilityId,
            germinatingQuantity = 64,
            id = batchId,
            latestObservedGerminatingQuantity = 64,
            latestObservedNotReadyQuantity = 128,
            latestObservedReadyQuantity = 256,
            latestObservedTime = Instant.EPOCH,
            lossRate = 0,
            notes = "Notes",
            notReadyQuantity = 128,
            organizationId = organizationId,
            readyByDate = LocalDate.of(2023, 6, 7),
            readyQuantity = 256,
            speciesId = speciesId,
            subLocationIds = setOf(subLocationId1, subLocationId2),
            substrate = BatchSubstrate.Moss,
            substrateNotes = "Substrate Notes",
            treatment = SeedTreatment.Chemical,
            treatmentNotes = "Treatment Notes",
            totalWithdrawn = 63,
            version = 1,
        )

    val actual = store.fetchOneById(batchId)

    assertEquals(expected, actual)
  }
}
