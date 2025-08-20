package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.nursery.event.NurserySeedlingBatchReadyEvent
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreFetchEstimatedReadyTest : BatchStoreTest() {
  @Test
  fun `returns rows within specified ready by date range`() {
    val facilityId = insertFacility(type = FacilityType.Nursery, name = "baby plant nursery")
    val batchId1 = insertBatch(speciesId = speciesId, readyByDate = LocalDate.of(2022, 11, 3))
    val batchId2 = insertBatch(speciesId = speciesId, readyByDate = LocalDate.of(2022, 11, 4))
    insertBatch(speciesId = speciesId, readyByDate = LocalDate.of(2022, 11, 10))

    val expected =
        batchesDao.fetchById(batchId1, batchId2).map {
          NurserySeedlingBatchReadyEvent(
              it.id!!,
              it.batchNumber!!,
              it.speciesId!!,
              "baby plant nursery",
          )
        }
    val actual =
        store.fetchEstimatedReady(facilityId, LocalDate.of(2022, 11, 1), LocalDate.of(2022, 11, 6))

    assertEquals(expected.toSet(), actual.toSet())
  }

  @Test
  fun `filters results by facility ID`() {
    insertFacility(type = FacilityType.Nursery)
    insertBatch(speciesId = speciesId, readyByDate = LocalDate.of(2022, 11, 3))
    val otherFacilityId = insertFacility(type = FacilityType.Nursery)

    val expected = emptyList<NurserySeedlingBatchReadyEvent>()
    val actual =
        store.fetchEstimatedReady(
            otherFacilityId,
            LocalDate.of(2022, 11, 1),
            LocalDate.of(2022, 11, 6),
        )

    assertEquals(expected, actual)
  }
}
