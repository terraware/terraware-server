package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.nursery.db.BatchNotFoundException
import io.mockk.every
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class BatchStorePermissionTest : BatchStoreTest() {
  @Test
  fun `createBatch throws exception if no permission to create batches at facility`() {
    every { user.canCreateBatch(facilityId) } returns false
    every { user.canReadFacility(facilityId) } returns true

    assertThrows<AccessDeniedException> { store.create(makeNewBatchModel()) }
  }

  @Test
  fun `fetchOneById throws exception if no permission to read batch`() {
    val batchId = insertBatch(speciesId = speciesId)

    every { user.canReadBatch(batchId) } returns false

    assertThrows<BatchNotFoundException> { store.fetchOneById(batchId) }
  }

  @Test
  fun `getSpeciesSummary throws exception if no permission to read species`() {
    every { user.canReadSpecies(speciesId) } returns false

    assertThrows<SpeciesNotFoundException> { store.getSpeciesSummary(speciesId) }
  }

  @Test
  fun `updateDetails throws exception if no permission to update batch`() {
    val batchId = BatchId(1)

    every { user.canUpdateBatch(batchId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateDetails(batchId = batchId, version = 1) { it }
    }
  }

  @Test
  fun `updateQuantities throws exception if no permission to update batch`() {
    val batchId = BatchId(1)

    every { user.canUpdateBatch(batchId) } returns false

    assertThrows<AccessDeniedException> {
      store.updateQuantities(
          batchId = batchId,
          version = 1,
          germinating = 1,
          activeGrowth = 1,
          hardeningOff = 1,
          ready = 1,
          historyType = BatchQuantityHistoryType.Observed)
    }
  }
}
