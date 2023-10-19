package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.nursery.api.CreateBatchRequestPayload
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreCreateBatchTest : BatchStoreTest() {
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
            batchNumber = "70-2-1-001",
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
  fun `includes facility number in batch number`() {
    val secondFacilityId = insertFacility(2, type = FacilityType.Nursery, facilityNumber = 2)

    val inputRow =
        CreateBatchRequestPayload(
                addedDate = LocalDate.of(2022, 1, 2),
                facilityId = secondFacilityId,
                germinatingQuantity = 0,
                notReadyQuantity = 1,
                readyQuantity = 2,
                speciesId = speciesId,
            )
            .toRow()

    val batch = store.create(inputRow)

    assertEquals("70-2-2-001", batch.batchNumber)
  }

  @Test
  fun `throws exception if facility is not a nursery`() {
    val seedBankFacilityId = FacilityId(2)
    insertFacility(seedBankFacilityId, type = FacilityType.SeedBank)

    assertThrows<FacilityTypeMismatchException> {
      store.create(makeBatchesRow().copy(facilityId = seedBankFacilityId))
    }
  }

  @Test
  fun `throws exception if project is not from same organization as nursery`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(otherOrganizationId)
    val projectId = insertProject(organizationId = otherOrganizationId)

    assertThrows<ProjectInDifferentOrganizationException> {
      store.create(makeBatchesRow().copy(projectId = projectId))
    }
  }
}
