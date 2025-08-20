package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.seedbank.event.AccessionSpeciesChangedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreAccessionSpeciesTest : BatchStoreTest() {
  @Test
  fun `updates affected batches`() {
    val seedBankFacilityId = insertFacility(type = FacilityType.SeedBank)
    val accessionId = insertAccession(facilityId = seedBankFacilityId)
    val otherAccessionId = insertAccession(facilityId = seedBankFacilityId)
    val otherSpeciesId = insertSpecies()
    val newSpeciesId = insertSpecies()
    val batchId1 =
        insertBatch(
            BatchesRow(accessionId = accessionId, facilityId = facilityId, speciesId = speciesId)
        )
    val batchId2 =
        insertBatch(
            BatchesRow(accessionId = accessionId, facilityId = facilityId, speciesId = speciesId)
        )
    val otherSpeciesBatchId =
        insertBatch(
            BatchesRow(
                accessionId = accessionId,
                facilityId = facilityId,
                speciesId = otherSpeciesId,
            )
        )
    val otherAccessionBatchId =
        insertBatch(
            BatchesRow(
                accessionId = otherAccessionId,
                facilityId = facilityId,
                speciesId = speciesId,
            )
        )

    store.on(AccessionSpeciesChangedEvent(accessionId, speciesId, newSpeciesId))

    assertEquals(
        newSpeciesId,
        batchesDao.fetchOneById(batchId1)?.speciesId,
        "Batch 1 species should have changed",
    )
    assertEquals(
        newSpeciesId,
        batchesDao.fetchOneById(batchId2)?.speciesId,
        "Batch 2 species should have changed",
    )
    assertEquals(
        otherSpeciesId,
        batchesDao.fetchOneById(otherSpeciesBatchId)?.speciesId,
        "Non-matching species ID should not have changed",
    )
    assertEquals(
        speciesId,
        batchesDao.fetchOneById(otherAccessionBatchId)?.speciesId,
        "Species ID from different accession should not have changed",
    )

    assertIsEventListener<AccessionSpeciesChangedEvent>(store)
  }
}
