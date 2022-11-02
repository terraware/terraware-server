package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreFetchEstimatedReadyTest : BatchStoreTest() {
  @Test
  fun `returns rows within specified ready by date range`() {
    val facilityId = FacilityId(1000)
    insertFacility(id = facilityId, type = FacilityType.Nursery, name = "baby plant nursery")
    val batchId1 =
        insertBatch(
            speciesId = speciesId, facilityId = facilityId, readyByDate = LocalDate.of(2022, 11, 3))
    val batchId2 =
        insertBatch(
            speciesId = speciesId, facilityId = facilityId, readyByDate = LocalDate.of(2022, 11, 4))
    insertBatch(
        speciesId = speciesId, facilityId = facilityId, readyByDate = LocalDate.of(2022, 11, 10))

    val expected =
        batchesDao.fetchById(batchId1, batchId2).map {
          NurserySeedlingBatchReadyEvent(
              it.id!!, it.batchNumber!!, it.speciesId!!, "baby plant nursery")
        }
    val actual =
        store.fetchEstimatedReady(
            LocalDateTime.of(2022, 11, 1, 0, 0).atZone(ZoneId.systemDefault()),
            LocalDateTime.of(2022, 11, 6, 0, 0).atZone(ZoneId.systemDefault()))

    assertEquals(expected, actual)
  }
}
