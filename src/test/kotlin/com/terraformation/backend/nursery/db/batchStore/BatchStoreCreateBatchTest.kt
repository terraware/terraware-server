package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.SubLocationAtWrongFacilityException
import com.terraformation.backend.db.SubLocationNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.BatchDetailsHistoryId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistorySubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchSubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.nursery.api.CreateBatchRequestPayload
import com.terraformation.backend.nursery.model.ExistingBatchModel
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreCreateBatchTest : BatchStoreTest() {
  @Test
  fun `creates new batches`() {
    val subLocationId1 = insertSubLocation()
    val subLocationId2 = insertSubLocation()

    val inputModel =
        CreateBatchRequestPayload(
                addedDate = LocalDate.of(2022, 1, 2),
                facilityId = facilityId,
                germinatingQuantity = 0,
                notes = "notes",
                notReadyQuantity = 1,
                readyByDate = LocalDate.of(2022, 3, 4),
                readyQuantity = 2,
                speciesId = speciesId,
                subLocationIds = setOf(subLocationId1, subLocationId2),
                substrate = BatchSubstrate.Other,
                substrateNotes = "My substrate",
                treatment = SeedTreatment.Chemical,
            )
            .toModel()

    val expectedRow =
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
            substrateId = BatchSubstrate.Other,
            substrateNotes = "My substrate",
            treatmentId = SeedTreatment.Chemical,
            version = 1)
    val expectedModel = ExistingBatchModel(expectedRow, setOf(subLocationId1, subLocationId2))

    val expectedQuantityHistory =
        listOf(
            BatchQuantityHistoryRow(
                batchId = BatchId(1),
                createdBy = user.userId,
                createdTime = clock.instant(),
                historyTypeId = BatchQuantityHistoryType.Observed,
                id = BatchQuantityHistoryId(1),
                germinatingQuantity = 0,
                notReadyQuantity = 1,
                readyQuantity = 2,
                version = 1,
            ),
        )

    val expectedSubLocations =
        setOf(
            BatchSubLocationsRow(
                batchId = BatchId(1), subLocationId = subLocationId1, facilityId = facilityId),
            BatchSubLocationsRow(
                batchId = BatchId(1), subLocationId = subLocationId2, facilityId = facilityId),
        )

    val expectedDetailsHistory =
        listOf(
            BatchDetailsHistoryRow(
                batchId = BatchId(1),
                createdBy = user.userId,
                createdTime = clock.instant(),
                id = BatchDetailsHistoryId(1),
                notes = "notes",
                readyByDate = LocalDate.of(2022, 3, 4),
                substrateId = BatchSubstrate.Other,
                substrateNotes = "My substrate",
                treatmentId = SeedTreatment.Chemical,
                version = 1))

    val expectedDetailsHistorySubLocations =
        setOf(
            BatchDetailsHistorySubLocationsRow(
                batchDetailsHistoryId = BatchDetailsHistoryId(1),
                subLocationId = subLocationId1,
                subLocationName = "Location 1",
            ),
            BatchDetailsHistorySubLocationsRow(
                batchDetailsHistoryId = BatchDetailsHistoryId(1),
                subLocationId = subLocationId2,
                subLocationName = "Location 2"))

    val returnedModel = store.create(inputModel)
    val writtenBatch = batchesDao.fetchOneById(BatchId(1))
    val writtenDetailsHistory = batchDetailsHistoryDao.findAll()
    val writtenDetailsHistorySubLocations = batchDetailsHistorySubLocationsDao.findAll().toSet()
    val writtenQuantityHistory = batchQuantityHistoryDao.findAll()
    val writtenSubLocations = batchSubLocationsDao.findAll().toSet()

    assertEquals(expectedModel, returnedModel, "Batch as returned by function")
    assertEquals(expectedRow, writtenBatch, "Batch as written to database")
    assertEquals(expectedDetailsHistory, writtenDetailsHistory, "Inserted details history row")
    assertEquals(
        expectedDetailsHistorySubLocations,
        writtenDetailsHistorySubLocations,
        "Inserted details history sub-locations")
    assertEquals(expectedQuantityHistory, writtenQuantityHistory, "Inserted quantity history row")
    assertEquals(expectedSubLocations, writtenSubLocations, "Inserted sub-locations")
  }

  @Test
  fun `includes facility number in batch number`() {
    val secondFacilityId = insertFacility(2, type = FacilityType.Nursery, facilityNumber = 2)

    val inputModel =
        CreateBatchRequestPayload(
                addedDate = LocalDate.of(2022, 1, 2),
                facilityId = secondFacilityId,
                germinatingQuantity = 0,
                notReadyQuantity = 1,
                readyQuantity = 2,
                speciesId = speciesId,
            )
            .toModel()

    val batch = store.create(inputModel)

    assertEquals("70-2-2-001", batch.batchNumber)
  }

  @Test
  fun `throws exception if facility is not a nursery`() {
    val seedBankFacilityId = FacilityId(2)
    insertFacility(seedBankFacilityId, type = FacilityType.SeedBank)

    assertThrows<FacilityTypeMismatchException> {
      store.create(makeNewBatchModel().copy(facilityId = seedBankFacilityId))
    }
  }

  @Test
  fun `throws exception if project is not from same organization as nursery`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(otherOrganizationId)
    val projectId = insertProject(organizationId = otherOrganizationId)

    assertThrows<ProjectInDifferentOrganizationException> {
      store.create(makeNewBatchModel().copy(projectId = projectId))
    }
  }

  @Test
  fun `throws exception if sub-location does not exist`() {
    assertThrows<SubLocationNotFoundException> {
      store.create(makeNewBatchModel().copy(subLocationIds = setOf(SubLocationId(12345))))
    }
  }

  @Test
  fun `throws exception if sub-location is not at same facility`() {
    val otherFacilityId = insertFacility(2, type = FacilityType.Nursery)
    val otherSubLocationId = insertSubLocation(facilityId = otherFacilityId)

    assertThrows<SubLocationAtWrongFacilityException> {
      store.create(makeNewBatchModel().copy(subLocationIds = setOf(otherSubLocationId)))
    }
  }
}
