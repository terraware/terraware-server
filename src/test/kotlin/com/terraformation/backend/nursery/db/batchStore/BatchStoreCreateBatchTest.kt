package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.SubLocationAtWrongFacilityException
import com.terraformation.backend.db.SubLocationNotFoundException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SubLocationId
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
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class BatchStoreCreateBatchTest : BatchStoreTest() {

  @Test
  fun `creates new batches`() {
    val subLocationId1 = insertSubLocation()
    val subLocationId2 = insertSubLocation()

    val addedDate = LocalDate.of(2022, 1, 2)
    clock.instant = addedDate.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant()

    val inputModel =
        CreateBatchRequestPayload(
                addedDate = addedDate,
                facilityId = facilityId,
                germinatingQuantity = 0,
                germinationStartedDate = LocalDate.of(2022, 2, 20),
                hardeningOffQuantity = 4,
                notes = "notes",
                activeGrowthQuantity = 1,
                readyByDate = LocalDate.of(2022, 3, 4),
                readyQuantity = 2,
                seedsSownDate = LocalDate.of(2022, 2, 12),
                speciesId = speciesId,
                subLocationIds = setOf(subLocationId1, subLocationId2),
                substrate = BatchSubstrate.Other,
                substrateNotes = "My substrate",
                treatment = SeedTreatment.Chemical,
            )
            .toModel()

    val returnedModel = store.create(inputModel)

    val expectedRow =
        BatchesRow(
            addedDate = addedDate,
            activeGrowthQuantity = 1,
            batchNumber = "22-2-1-001",
            createdBy = user.userId,
            createdTime = clock.instant(),
            facilityId = facilityId,
            germinatingQuantity = 0,
            germinationStartedDate = LocalDate.of(2022, 2, 20),
            hardeningOffQuantity = 4,
            id = returnedModel.id,
            latestObservedActiveGrowthQuantity = 1,
            latestObservedGerminatingQuantity = 0,
            latestObservedReadyQuantity = 2,
            latestObservedHardeningOffQuantity = 4,
            latestObservedTime = addedDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
            lossRate = 0,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            notes = "notes",
            organizationId = organizationId,
            readyByDate = LocalDate.of(2022, 3, 4),
            readyQuantity = 2,
            seedsSownDate = LocalDate.of(2022, 2, 12),
            speciesId = speciesId,
            substrateId = BatchSubstrate.Other,
            substrateNotes = "My substrate",
            treatmentId = SeedTreatment.Chemical,
            version = 1)
    val expectedModel = ExistingBatchModel(expectedRow, setOf(subLocationId1, subLocationId2), 0)

    val expectedQuantityHistory =
        listOf(
            BatchQuantityHistoryRow(
                batchId = returnedModel.id,
                activeGrowthQuantity = 1,
                createdBy = user.userId,
                createdTime = clock.instant(),
                historyTypeId = BatchQuantityHistoryType.Observed,
                germinatingQuantity = 0,
                readyQuantity = 2,
                hardeningOffQuantity = 4,
                version = 1,
            ),
        )

    val expectedSubLocations =
        setOf(
            BatchSubLocationsRow(
                batchId = returnedModel.id,
                subLocationId = subLocationId1,
                facilityId = facilityId),
            BatchSubLocationsRow(
                batchId = returnedModel.id,
                subLocationId = subLocationId2,
                facilityId = facilityId),
        )

    val expectedDetailsHistory =
        listOf(
            BatchDetailsHistoryRow(
                batchId = returnedModel.id,
                createdBy = user.userId,
                createdTime = clock.instant(),
                notes = "notes",
                readyByDate = LocalDate.of(2022, 3, 4),
                substrateId = BatchSubstrate.Other,
                substrateNotes = "My substrate",
                treatmentId = SeedTreatment.Chemical,
                version = 1))

    val expectedDetailsHistorySubLocations =
        setOf(
            BatchDetailsHistorySubLocationsRow(
                subLocationId = subLocationId1,
                subLocationName = "Location 1",
            ),
            BatchDetailsHistorySubLocationsRow(
                subLocationId = subLocationId2, subLocationName = "Location 2"))

    val writtenBatch = batchesDao.fetchOneById(returnedModel.id)
    val writtenDetailsHistory = batchDetailsHistoryDao.findAll().map { it.copy(id = null) }
    val writtenDetailsHistorySubLocations =
        batchDetailsHistorySubLocationsDao
            .findAll()
            .map { it.copy(batchDetailsHistoryId = null) }
            .toSet()
    val writtenQuantityHistory = batchQuantityHistoryDao.findAll().map { it.copy(id = null) }
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
    val secondFacilityId = insertFacility(type = FacilityType.Nursery, facilityNumber = 2)

    val inputModel =
        CreateBatchRequestPayload(
                addedDate = LocalDate.of(2022, 1, 2),
                facilityId = secondFacilityId,
                germinatingQuantity = 0,
                activeGrowthQuantity = 1,
                readyQuantity = 2,
                hardeningOffQuantity = 4,
                speciesId = speciesId,
            )
            .toModel()

    val batch = store.create(inputModel)

    assertEquals("70-2-2-001", batch.batchNumber)
  }

  @Test
  fun `uses added date midnight at facility as latest observed time if it is in the past`() {
    val timeZone = ZoneId.of("America/New_York")
    val newYorkFacilityId = insertFacility(type = FacilityType.Nursery, timeZone = timeZone)
    val addedDate = LocalDate.of(2022, 1, 2)
    val startOfAddedDate = addedDate.atStartOfDay(timeZone).toInstant()
    val now = addedDate.plusDays(1).atStartOfDay(timeZone).toInstant()

    clock.instant = now

    val inputModel =
        CreateBatchRequestPayload(
                addedDate = addedDate,
                facilityId = newYorkFacilityId,
                germinatingQuantity = 0,
                activeGrowthQuantity = 1,
                readyQuantity = 2,
                hardeningOffQuantity = 4,
                speciesId = speciesId,
            )
            .toModel()

    val batch = store.create(inputModel)

    assertEquals(startOfAddedDate, batch.latestObservedTime)
  }

  @Test
  fun `uses current time as latest observed time if added date is today at facility`() {
    val timeZone = ZoneId.of("America/New_York")
    val newYorkFacilityId = insertFacility(type = FacilityType.Nursery, timeZone = timeZone)
    val addedDate = LocalDate.of(2022, 1, 2)
    val now = addedDate.atTime(23, 59).atZone(timeZone).toInstant()

    clock.instant = now

    val inputModel =
        CreateBatchRequestPayload(
                addedDate = addedDate,
                facilityId = newYorkFacilityId,
                germinatingQuantity = 0,
                activeGrowthQuantity = 1,
                readyQuantity = 2,
                hardeningOffQuantity = 4,
                speciesId = speciesId,
            )
            .toModel()

    val batch = store.create(inputModel)

    assertEquals(now, batch.latestObservedTime)
  }

  @Test
  fun `throws exception if facility is not a nursery`() {
    val seedBankFacilityId = insertFacility(type = FacilityType.SeedBank)

    assertThrows<FacilityTypeMismatchException> {
      store.create(makeNewBatchModel().copy(facilityId = seedBankFacilityId))
    }
  }

  @Test
  fun `throws exception if project is not from same organization as nursery`() {
    insertOrganization()
    val projectId = insertProject()

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
    insertFacility(type = FacilityType.Nursery)
    val otherSubLocationId = insertSubLocation()

    assertThrows<SubLocationAtWrongFacilityException> {
      store.create(makeNewBatchModel().copy(subLocationIds = setOf(otherSubLocationId)))
    }
  }
}
