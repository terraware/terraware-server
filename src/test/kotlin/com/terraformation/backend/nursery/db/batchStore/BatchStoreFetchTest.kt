package com.terraformation.backend.nursery.db.batchStore

import com.terraformation.backend.nursery.model.ExistingBatchModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class BatchStoreFetchTest : BatchStoreTest() {
  @Test
  fun `returns model from database row`() {
    val batchId = insertBatch(speciesId = speciesId)

    val expected = ExistingBatchModel(batchesDao.fetchOneById(batchId)!!)
    val actual = store.fetchOneById(batchId)

    assertEquals(expected, actual)
  }
}
